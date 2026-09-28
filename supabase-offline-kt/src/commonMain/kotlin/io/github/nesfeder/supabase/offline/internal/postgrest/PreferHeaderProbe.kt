package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.util.AttributeKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Reads the `Prefer` header of the PostgREST request a write sends.
 *
 * supabase-kt keeps `dryRun()` in a private field of its request builder and only ever writes it into
 * the outgoing `Prefer` header, as `tx=rollback`. The engine has to know about a dry run — it must
 * never queue one (the queue does not carry `Prefer`, so it would replay as a real write) or overlay
 * one onto the cache (the server rolled it back) — so it looks where supabase-kt puts it: an
 * interceptor on the client's HTTP pipeline records the header of any request sent from a coroutine
 * that carries a probe.
 *
 * A probe with [shortCircuit] set stops the request inside the interceptor, before it reaches the
 * network: the write's request is built, its header read, and nothing is sent. The engine uses that
 * to ask "is this a dry run?" about a write it is about to queue rather than send.
 *
 * @property shortCircuit Whether the request is stopped once its header is read.
 */
internal class PreferHeaderProbe(val shortCircuit: Boolean) : AbstractCoroutineContextElement(Key) {

    /** The key a probe is found under in a coroutine context. */
    companion object Key : CoroutineContext.Key<PreferHeaderProbe>

    /** The `Prefer` header of the last request the probe saw, or `null` while it has seen none. */
    var prefer: String? = null
        private set

    /** Whether the probe saw a request at all. */
    var observed: Boolean = false
        private set

    /** Whether the request the probe saw asked PostgREST to roll its transaction back. */
    val requestedRollback: Boolean
        get() = prefer.orEmpty().split(',').any { preference -> preference.trim() == TX_ROLLBACK_PREFERENCE }

    /**
     * Records the `Prefer` header of a request the interceptor saw.
     *
     * @param preferHeader The header, or `null` when the request carried none.
     */
    fun record(preferHeader: String?) {
        prefer = preferHeader
        observed = true
    }
}

/** The preference supabase-kt's `dryRun()` writes into the `Prefer` header. */
private const val TX_ROLLBACK_PREFERENCE = "tx=rollback"

/** Thrown inside the pipeline to stop a short-circuited request; never escapes the engine. */
internal class PreferProbeShortCircuit : IOException("Request stopped by the offline engine's Prefer probe")

private val PreferProbeInstalled = AttributeKey<Unit>("io.github.nesfeder.supabase.offline.PreferHeaderProbe")

/**
 * Installs the interceptor behind [PreferHeaderProbe] on this client's HTTP pipeline, once per
 * client. It does nothing for a request sent without a probe in its coroutine context.
 */
@OptIn(SupabaseInternal::class)
internal fun SupabaseClient.installPreferHeaderProbe() {
    val client = httpClient.httpClient
    client.attributes.computeIfAbsent(PreferProbeInstalled) {
        client.plugin(HttpSend).intercept { request ->
            val probe = currentCoroutineContext()[PreferHeaderProbe]
            if (probe != null) {
                probe.record(request.headers[PostgrestQueryBuilder.HEADER_PREFER])
                if (probe.shortCircuit) throw PreferProbeShortCircuit()
            }
            execute(request)
        }
    }
}
