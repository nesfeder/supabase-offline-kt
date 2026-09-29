@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestPipeline
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

/** What the dry-run check learned about a write without sending it. */
internal sealed interface DryRunVerdict {

    /** The write asks PostgREST to roll its transaction back: `dryRun()`. */
    data object DryRun : DryRunVerdict

    /** An ordinary write. */
    data object NotDryRun : DryRunVerdict

    /** The write failed with [cause] before its request was built, so it was not sent. */
    data class NotBuilt(val cause: Exception) : DryRunVerdict

    /** The check is not installed on this client, so it cannot tell. */
    data object Unavailable : DryRunVerdict

    /** Nothing stopped the request, so the write went out and the server answered [result]. */
    data class Sent(val result: PostgrestResult) : DryRunVerdict
}

/**
 * Tells a dry run (`dryRun()`) from an ordinary write before the write is queued, because the mutation
 * queue does not carry the `Prefer` header, and a queued dry run would replay as a real write.
 */
internal fun interface DryRunProbe {

    /** Builds the request [write] would send and reads it, stopping it before the network. */
    suspend fun classify(write: suspend () -> PostgrestResult): DryRunVerdict
}

/**
 * The [DryRunProbe] over supabase-kt's HTTP pipeline. supabase-kt keeps `dryRun()` private and writes
 * it only into the `Prefer` header, as `tx=rollback`, so the check reads that header: it runs the
 * write under a [PreferHeaderProbe], and the phase [installDryRunCheck] put ahead of every
 * other on the request pipeline records the header of the PostgREST write request and stops it. No
 * plugin the app installed runs for it, so no retry or logging plugin sees the stop.
 *
 * Hosted Supabase appears to run PostgREST with its default `db-tx-end = commit`, under which
 * `tx=rollback` is ignored and a "dry run" commits. So a dry run is never queued, and whether it was
 * rolled back is read from the response ([wasRolledBack]). Inferred from configuration; not yet
 * confirmed against a live project.
 */
internal class PreferHeaderDryRunProbe(private val supabase: SupabaseClient) : DryRunProbe {

    override suspend fun classify(write: suspend () -> PostgrestResult): DryRunVerdict {
        if (!supabase.hasPreferHeaderProbe()) return DryRunVerdict.Unavailable
        val probe = PreferHeaderProbe()
        val result = try {
            withContext(probe) { write() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (!probe.observed) return DryRunVerdict.NotBuilt(failure)
            null
        }
        return when {
            result != null -> DryRunVerdict.Sent(result)
            probe.requestedRollback -> DryRunVerdict.DryRun
            else -> DryRunVerdict.NotDryRun
        }
    }
}

/**
 * Carried in a coroutine context, it makes the phase [installDryRunCheck] installs record the
 * `Prefer` header of the PostgREST write request sent from that coroutine, and stop it.
 */
internal class PreferHeaderProbe : AbstractCoroutineContextElement(Key) {

    /** The key a probe is found under in a coroutine context. */
    companion object Key : CoroutineContext.Key<PreferHeaderProbe>

    /** The `Prefer` header of the request the probe saw, or `null` while it has seen none. */
    var prefer: String? = null
        private set

    /** How many PostgREST write requests the probe saw. */
    var observations: Int = 0
        private set

    /** Whether the probe saw a PostgREST write request. */
    val observed: Boolean
        get() = observations > 0

    /** Whether the request the probe saw asked PostgREST to roll its transaction back. */
    val requestedRollback: Boolean
        get() = requestsRollback(prefer)

    /** Records the `Prefer` header of a request, or `null` when it carried none. */
    fun record(preferHeader: String?) {
        prefer = preferHeader
        observations++
    }
}

/** Whether a `Prefer` or `Preference-Applied` header value carries `tx=rollback`. */
internal fun requestsRollback(header: String?): Boolean =
    header.orEmpty().split(',').any { preference -> preference.trim() == TX_ROLLBACK_PREFERENCE }

/**
 * Whether PostgREST rolled the write that produced this result back: its `Preference-Applied` header
 * says `tx=rollback`, which PostgREST sends only when it honoured the preference.
 */
internal val PostgrestResult.wasRolledBack: Boolean
    get() = requestsRollback(headers[PREFERENCE_APPLIED_HEADER])

/** The preference supabase-kt's `dryRun()` writes into the `Prefer` header. */
private const val TX_ROLLBACK_PREFERENCE = "tx=rollback"

/** The response header PostgREST lists the preferences it applied in. */
private const val PREFERENCE_APPLIED_HEADER = "Preference-Applied"

/** Thrown by the dry-run check's phase to stop a request; caught by [PreferHeaderDryRunProbe]. */
private class DryRunCheckStop : Exception("Request stopped by the offline engine's dry-run check")

/** The request-pipeline phase the dry-run check runs in, ahead of every plugin's. */
private val DryRunCheckPhase = PipelinePhase("SupabaseOfflineDryRunCheck")

/** Set on a client's HTTP client once the dry-run check is installed. */
private val PreferProbeInstallation = AttributeKey<AtomicBoolean>("io.github.nesfeder.supabase.offline.PreferHeaderProbe")

/**
 * Installs the dry-run check on this client's request pipeline, in a phase ahead of every other,
 * exactly once however many threads call it: the flag is shared through the client's attributes and
 * claimed with a compare-and-set. It acts only on a PostgREST write sent under a [PreferHeaderProbe].
 *
 * @return `true` when this call installed it.
 */
@OptIn(SupabaseInternal::class)
internal fun SupabaseClient.installDryRunCheck(): Boolean {
    val client = httpClient.httpClient
    // computeIfAbsent may run its block more than once under a race, but every caller gets the one
    // instance that was stored; the compare-and-set on it is what admits a single installer.
    val installation = client.attributes.computeIfAbsent(PreferProbeInstallation) { AtomicBoolean(false) }
    if (!installation.compareAndSet(expectedValue = false, newValue = true)) return false
    client.requestPipeline.insertPhaseBefore(HttpRequestPipeline.Before, DryRunCheckPhase)
    client.requestPipeline.intercept(DryRunCheckPhase) {
        val probe = currentCoroutineContext()[PreferHeaderProbe]
        if (probe != null && isPostgrestWrite(context)) {
            probe.record(context.headers[PostgrestQueryBuilder.HEADER_PREFER])
            throw DryRunCheckStop()
        }
    }
    return true
}

/** Whether the dry-run check is on this client's pipeline. */
@OptIn(SupabaseInternal::class)
private fun SupabaseClient.hasPreferHeaderProbe(): Boolean =
    httpClient.httpClient.attributes.getOrNull(PreferProbeInstallation)?.load() == true

/** Whether [request] is a write to this client's PostgREST: not a read, and under its URL. */
private fun SupabaseClient.isPostgrestWrite(request: HttpRequestBuilder): Boolean {
    if (request.method == HttpMethod.Get || request.method == HttpMethod.Head) return false
    val postgrest = pluginManager.getPluginOrNull(Postgrest) ?: return false
    val base = postgrest.resolveUrl().trimEnd('/')
    val url = request.url.buildString()
    return url == base || url.startsWith("$base/") || url.startsWith("$base?")
}
