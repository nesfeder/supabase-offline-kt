@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
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
     * @property kind what it asks for, as a phrase for messages and logs.
     */
    data class Uncacheable(val kind: String) : ReadShape
}

/** Learns a SELECT's [ReadShape] before anything is sent or read from the cache. */
internal fun interface ReadShapeProbe {

    /**
     * The shape the SELECT [request] would ask the server for.
     *
     * @throws Exception the builder's own error when the request cannot be built, as sending it would.
     */
    suspend fun shapeOf(columns: Columns, request: SelectRequestBuilder.() -> Unit): ReadShape
}

/**
 * The shape of a PostgREST read sent with [method] and these header values: supabase-kt writes
 * `single()`, `csv()` and the rest only into `Accept`, `head` into the method, and a count into `Prefer`.
 */
internal fun readShapeOf(method: HttpMethod, accept: List<String>, prefer: List<String>): ReadShape = when {
    method == HttpMethod.Head -> ReadShape.Uncacheable("a HEAD request")
    prefer.flatMap { value -> value.split(',') }.any { preference -> preference.trim().startsWith("count=") } ->
        ReadShape.Uncacheable("a read with a row count")
    accept.isNotEmpty() && accept.all { value -> value == JSON_ROWS } -> ReadShape.RowList
    accept.any { value -> value.startsWith(OBJECT) } -> ReadShape.Uncacheable("a single object (single(), maybeSingle())")
    accept.any { value -> value.startsWith(PLAN) } -> ReadShape.Uncacheable("a query plan (explain())")
    accept.any { value -> "nulls=stripped" in value } -> ReadShape.Uncacheable("rows with nulls stripped (stripNulls())")
    else -> ReadShape.Uncacheable("another media type (csv(), geojson())")
}

/**
 * A second PostgREST plugin instance on [postgrest]'s client, configured as [postgrest] is, whose
 * requests carry a fixed token: building one asks neither the Auth plugin nor the app's own token
 * provider for a token, and sends nothing, since the read-shape check stops every request it builds.
 * What it copies is pinned by `ReadShapeProbeContractTest`.
 */
internal fun readShapePostgrestOf(postgrest: Postgrest): Postgrest {
    val source = postgrest.config
    // copy() carries the constructor's settings; the settings declared outside it are copied by hand
    val config = source.copy().also { copy ->
        copy.customUrl = source.customUrl
        copy.serializer = source.serializer
        copy.logLevel = source.logLevel
        copy.loggingFactory = source.loggingFactory
        copy.jwtToken = READ_SHAPE_CHECK_TOKEN
        copy.maxRetries = 0
    }
    return Postgrest.create(postgrest.supabaseClient, config)
}

/**
 * The [ReadShapeProbe] over supabase-kt's HTTP pipeline, as the dry-run check reads writes: the probe
 * builds the read through [postgrest], an instance from [readShapePostgrestOf], under a
 * [ReadRequestCapture]; the phase [installReadShapeCheck] put ahead of every other records its method
 * and headers and stops it. It fails closed: a read whose request cannot be built is not taken for a list.
 *
 * @param table the table read.
 * @param schema the schema it is read in.
 */
internal class PipelineReadShapeProbe(
    private val postgrest: Postgrest,
    private val table: String,
    private val schema: String,
) : ReadShapeProbe {

    override suspend fun shapeOf(columns: Columns, request: SelectRequestBuilder.() -> Unit): ReadShape {
        check(postgrest.supabaseClient.hasReadShapeCheck()) { "The read-shape check is not installed on this client" }
        val capture = ReadRequestCapture()
        val failure = try {
            withContext(capture) {
                postgrest.from(schema, table).select(columns) {
                    request()
                    // The stop comes back as a failed request, which supabase-kt would retry
                    noRetry()
                }
            }
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // The check's stop, or the request could not be built; the capture tells them apart
            failure
        }
        return capture.shape ?: throw failure ?: IllegalStateException("The read-shape check did not see the read")
    }
}

/**
 * Carried in a coroutine context, it makes the phase [installReadShapeCheck] installs record the shape
 * of the request sent from that coroutine, and stop it.
 */
internal class ReadRequestCapture : AbstractCoroutineContextElement(Key) {

    /** The key a capture is found under in a coroutine context. */
    companion object Key : CoroutineContext.Key<ReadRequestCapture>

    /** The shape of the read the capture saw, or `null` while it has seen none. */
    var shape: ReadShape? = null

    /** The URL of the request the capture saw, or `null` while it has seen none. */
    var url: String? = null

    /** The headers of the request the capture saw, by name; empty while it has seen none. */
    var headers: Map<String, List<String>> = emptyMap()
}

/** Thrown by the read-shape check's phase to stop a request; caught by [PipelineReadShapeProbe]. */
private class ReadShapeCheckStop : Exception("Request stopped by the offline engine's read-shape check")

/** The request-pipeline phase the read-shape check runs in, ahead of every plugin's. */
private val ReadShapeCheckPhase = PipelinePhase("SupabaseOfflineReadShapeCheck")

/** Set on a client's HTTP client once the read-shape check is installed. */
private val ReadShapeCheckInstallation = AttributeKey<AtomicBoolean>("io.github.nesfeder.supabase.offline.ReadShapeCheck")

/**
 * Installs the read-shape check on this client's request pipeline, ahead of every other phase, once
 * however many threads call it. It stops every request sent under a [ReadRequestCapture], whatever its
 * URL, so a request built for the check never leaves, and records a read's shape.
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
        val capture = currentCoroutineContext()[ReadRequestCapture] ?: return@intercept
        val request = context
        capture.shape = if (request.method == HttpMethod.Get || request.method == HttpMethod.Head) {
            readShapeOf(
                method = request.method,
                accept = request.headers.getAll(HttpHeaders.Accept).orEmpty(),
                prefer = request.headers.getAll(PostgrestQueryBuilder.HEADER_PREFER).orEmpty(),
            )
        } else {
            ReadShape.Uncacheable("a request that is not a read")
        }
        capture.url = request.url.buildString()
        capture.headers = request.headers.names().associateWith { name -> request.headers.getAll(name).orEmpty() }
        throw ReadShapeCheckStop()
    }
    return true
}

/** Whether the read-shape check is on this client's pipeline. */
@OptIn(SupabaseInternal::class)
private fun SupabaseClient.hasReadShapeCheck(): Boolean =
    httpClient.httpClient.attributes.getOrNull(ReadShapeCheckInstallation)?.load() == true

/** The token the read-shape check's requests carry. It is no credential, and the check never sends it. */
internal const val READ_SHAPE_CHECK_TOKEN: String = "supabase-offline-read-shape-check"

/** The `Accept` value of a plain SELECT: a JSON array of rows. */
private const val JSON_ROWS = "application/json"

/** The media type `single()` and `maybeSingle()` ask for. */
private const val OBJECT = "application/vnd.pgrst.object+json"

/** The media type `explain()` asks for. */
private const val PLAN = "application/vnd.pgrst.plan"
