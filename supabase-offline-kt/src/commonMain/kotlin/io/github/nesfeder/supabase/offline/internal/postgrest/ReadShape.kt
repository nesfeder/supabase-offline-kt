@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The shape of a SELECT's response, as far as the query cache goes. */
internal sealed interface ReadShape {

    /** A JSON array of rows: the one shape the query cache holds. */
    data object RowList : ReadShape

    /**
     * Any other: one object, a count, a `HEAD`, CSV, GeoJSON, a query plan, or rows with nulls
     * stripped. Such a read bypasses the cache.
     *
     * @property kind what it asks for, for logs.
     */
    data class Uncacheable(val kind: String) : ReadShape
}

/** Learns a SELECT's [ReadShape] before anything is sent or read from the cache. */
internal fun interface ReadShapeProbe {

    /** The shape the SELECT [request] would ask the server for. */
    suspend fun shapeOf(columns: Columns, request: SelectRequestBuilder.() -> Unit): ReadShape
}

/**
 * The shape of a PostgREST read sent with [method] and these header values: supabase-kt writes
 * `single()`, `csv()` and the rest only into `Accept`, `head` into the method, and a count into `Prefer`.
 */
internal fun readShapeOf(method: HttpMethod, accept: List<String>, prefer: List<String>): ReadShape = when {
    method == HttpMethod.Head -> ReadShape.Uncacheable("head")
    prefer.flatMap { value -> value.split(',') }.any { preference -> preference.trim().startsWith("count=") } ->
        ReadShape.Uncacheable("count")
    accept.isNotEmpty() && accept.all { value -> value == JSON_ROWS } -> ReadShape.RowList
    accept.any { value -> value.startsWith(OBJECT) } -> ReadShape.Uncacheable("single object")
    accept.any { value -> value.startsWith(PLAN) } -> ReadShape.Uncacheable("query plan")
    accept.any { value -> "nulls=stripped" in value } -> ReadShape.Uncacheable("rows with nulls stripped")
    else -> ReadShape.Uncacheable("another media type")
}

/**
 * The [ReadShapeProbe] over supabase-kt's HTTP pipeline, as the dry-run check reads writes: the probe
 * builds the request under a [ReadRequestCapture], and the phase [installReadShapeCheck] put ahead of
 * every other records its method and headers and stops it. Building it resolves the access token.
 */
internal class PipelineReadShapeProbe(private val builder: PostgrestQueryBuilder) : ReadShapeProbe {

    /** A read whose request cannot be built, as when no token resolves, is taken as a row list. */
    override suspend fun shapeOf(columns: Columns, request: SelectRequestBuilder.() -> Unit): ReadShape {
        // Without the check the probe's request would really go out
        if (!builder.postgrest.supabaseClient.hasReadShapeCheck()) return ReadShape.RowList
        val capture = ReadRequestCapture()
        try {
            withContext(capture) {
                builder.select(columns) {
                    request()
                    // The stop comes back as a failed request, which supabase-kt would retry
                    noRetry()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The check's stop, or a request that could not be built; the capture tells them apart
        }
        return capture.shape ?: ReadShape.RowList
    }
}

/**
 * Carried in a coroutine context, it makes the phase [installReadShapeCheck] installs record the shape
 * of the PostgREST read sent from that coroutine, and stop it.
 */
internal class ReadRequestCapture : AbstractCoroutineContextElement(Key) {

    /** The key a capture is found under in a coroutine context. */
    companion object Key : CoroutineContext.Key<ReadRequestCapture>

    /** The shape of the read the capture saw, or `null` while it has seen none. */
    var shape: ReadShape? = null
}

/** Thrown by the read-shape check's phase to stop a request; caught by [PipelineReadShapeProbe]. */
private class ReadShapeCheckStop : Exception("Request stopped by the offline engine's read-shape check")

/** The request-pipeline phase the read-shape check runs in, ahead of every plugin's. */
private val ReadShapeCheckPhase = PipelinePhase("SupabaseOfflineReadShapeCheck")

/** Set on a client's HTTP client once the read-shape check is installed. */
private val ReadShapeCheckInstallation = AttributeKey<AtomicBoolean>("io.github.nesfeder.supabase.offline.ReadShapeCheck")

/**
 * Installs the read-shape check on this client's request pipeline, ahead of every other phase, once
 * however many threads call it. It acts only on a PostgREST read sent under a [ReadRequestCapture].
 *
 * @return `true` when this call installed it.
 */
@OptIn(SupabaseInternal::class)
internal fun SupabaseClient.installReadShapeCheck(): Boolean {
    val client = httpClient.httpClient
    val installation = client.attributes.computeIfAbsent(ReadShapeCheckInstallation) { AtomicBoolean(false) }
    if (!installation.compareAndSet(expectedValue = false, newValue = true)) return false
    client.requestPipeline.insertPhaseBefore(HttpRequestPipeline.Before, ReadShapeCheckPhase)
    client.requestPipeline.intercept(ReadShapeCheckPhase) {
        val capture = currentCoroutineContext()[ReadRequestCapture]
        if (capture != null && isPostgrestRead(context)) {
            capture.shape = readShapeOf(
                method = context.method,
                accept = context.headers.getAll(HttpHeaders.Accept).orEmpty(),
                prefer = context.headers.getAll(PostgrestQueryBuilder.HEADER_PREFER).orEmpty(),
            )
            throw ReadShapeCheckStop()
        }
    }
    return true
}

/** Whether the read-shape check is on this client's pipeline. */
@OptIn(SupabaseInternal::class)
private fun SupabaseClient.hasReadShapeCheck(): Boolean =
    httpClient.httpClient.attributes.getOrNull(ReadShapeCheckInstallation)?.load() == true

/** Whether [request] is a read of this client's PostgREST: a `GET` or `HEAD` under its URL. */
private fun SupabaseClient.isPostgrestRead(request: HttpRequestBuilder): Boolean {
    if (request.method != HttpMethod.Get && request.method != HttpMethod.Head) return false
    val postgrest = pluginManager.getPluginOrNull(Postgrest) ?: return false
    val base = postgrest.resolveUrl().trimEnd('/')
    val url = request.url.buildString()
    return url == base || url.startsWith("$base/") || url.startsWith("$base?")
}

/** The `Accept` value of a plain SELECT: a JSON array of rows. */
private const val JSON_ROWS = "application/json"

/** The media type `single()` and `maybeSingle()` ask for. */
private const val OBJECT = "application/vnd.pgrst.object+json"

/** The media type `explain()` asks for. */
private const val PLAN = "application/vnd.pgrst.plan"
