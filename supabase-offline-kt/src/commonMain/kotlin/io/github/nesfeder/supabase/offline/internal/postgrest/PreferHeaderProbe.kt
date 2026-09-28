@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Tells whether a write asks PostgREST to roll its transaction back — a write built with supabase-kt's
 * `dryRun()` — without sending it. The engine consults it in one place: before it queues a
 * `QueueIfOffline` write, because the queue does not carry the `Prefer` header, and a dry run
 * queued would replay as a real write.
 */
internal fun interface DryRunProbe {

    /**
     * Builds the request [write] would send and reads it, without sending it.
     *
     * @param write Issues one write.
     * @return `true` for a dry run, `false` for a write, or `null` when it cannot be told — nothing
     *   was sent either way.
     */
    suspend fun requestsRollback(write: suspend () -> PostgrestResult): Boolean?
}

/**
 * The [DryRunProbe] over supabase-kt's HTTP pipeline.
 *
 * supabase-kt keeps `dryRun()` in a private field of its request builder and only ever writes it into
 * the outgoing `Prefer` header, as `tx=rollback`. So the probe runs the write with a
 * [PreferHeaderProbe] in its coroutine context: the interceptor [installPreferHeaderProbe] put on the
 * client's pipeline reads the header of the PostgREST write request and stops it there, before it
 * reaches the network.
 *
 * Only a PostgREST write request (a non-`GET` request under the Postgrest plugin's URL) is read and
 * stopped. Anything else the write sends first goes through: supabase-kt refreshes an expired session
 * inline, in the same coroutine, before the write's own request.
 *
 * @property supabase The client whose writes it classifies.
 */
internal class PreferHeaderDryRunProbe(private val supabase: SupabaseClient) : DryRunProbe {

    override suspend fun requestsRollback(write: suspend () -> PostgrestResult): Boolean? {
        if (!supabase.hasPreferHeaderProbe()) return null
        val probe = PreferHeaderProbe()
        val completed = try {
            withContext(probe) { write() }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The stop surfaces as a failed request. Any other failure means the request was never
            // built far enough to be read — and was not sent either.
            false
        }
        check(!completed || probe.observed) {
            "A write reached the network past the dry-run probe; the interceptor did not see its request"
        }
        return if (probe.observed) probe.requestedRollback else null
    }
}

/**
 * Carried in a coroutine context, it makes the interceptor [installPreferHeaderProbe] installs record
 * the `Prefer` header of the PostgREST write request sent from that coroutine, and stop it.
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

    /**
     * Records the `Prefer` header of a request the interceptor saw.
     *
     * @param preferHeader The header, or `null` when the request carried none.
     */
    fun record(preferHeader: String?) {
        prefer = preferHeader
        observations++
    }
}

/**
 * Whether a `Prefer` or `Preference-Applied` header value carries `tx=rollback`.
 *
 * @param header The header's value, or `null` when absent.
 * @return `true` when it names the rollback preference.
 */
internal fun requestsRollback(header: String?): Boolean =
    header.orEmpty().split(',').any { preference -> preference.trim() == TX_ROLLBACK_PREFERENCE }

/**
 * Whether PostgREST rolled the write that produced [this] back: its `Preference-Applied` header says
 * `tx=rollback`. PostgREST sends that only when it honoured the preference; its default
 * (`db-tx-end = commit`) ignores `tx=rollback` and commits the write.
 */
internal val PostgrestResult.wasRolledBack: Boolean
    get() = requestsRollback(headers[PREFERENCE_APPLIED_HEADER])

/** The preference supabase-kt's `dryRun()` writes into the `Prefer` header. */
private const val TX_ROLLBACK_PREFERENCE = "tx=rollback"

/** The response header PostgREST lists the preferences it applied in. */
private const val PREFERENCE_APPLIED_HEADER = "Preference-Applied"

/** Thrown inside the pipeline to stop a probed request; never escapes the engine. */
internal class PreferProbeShortCircuit : IOException("Request stopped by the offline engine's dry-run probe")

/** Set on a client's HTTP client once the interceptor is installed. */
private val PreferProbeInstallation = AttributeKey<AtomicBoolean>("io.github.nesfeder.supabase.offline.PreferHeaderProbe")

/**
 * Installs the interceptor behind [PreferHeaderProbe] on this client's HTTP pipeline, exactly once per
 * client however many threads call it at once: the flag is shared through the client's attributes and
 * claimed with a compare-and-set. It does nothing for a request sent without a probe in its coroutine
 * context, and nothing for a request that is not a PostgREST write.
 *
 * Called when the offline client is built, before the app sends anything through it.
 *
 * @return `true` when this call installed it; `false` when it was already installed.
 */
@OptIn(SupabaseInternal::class)
internal fun SupabaseClient.installPreferHeaderProbe(): Boolean {
    val client = httpClient.httpClient
    // computeIfAbsent may run its block more than once under a race, but every caller gets the one
    // instance that was stored; the compare-and-set on it is what admits a single installer.
    val installation = client.attributes.computeIfAbsent(PreferProbeInstallation) { AtomicBoolean(false) }
    if (!installation.compareAndSet(expectedValue = false, newValue = true)) return false
    client.plugin(HttpSend).intercept { request ->
        val probe = currentCoroutineContext()[PreferHeaderProbe]
        if (probe != null && isPostgrestWrite(request)) {
            probe.record(request.headers[PostgrestQueryBuilder.HEADER_PREFER])
            throw PreferProbeShortCircuit()
        }
        execute(request)
    }
    return true
}

/** Whether the interceptor is on this client's pipeline. */
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
