@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.nesfeder.supabase.offline.internal.util.provesRequestNeverSent
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Carried in a coroutine context, it keeps the failure the HTTP engine raised for a request sent from
 * that coroutine, before supabase-kt replaces it with an exception that keeps only its message.
 */
private class TransportFailureCapture : AbstractCoroutineContextElement(Key) {

    /** The key a capture is found under in a coroutine context. */
    companion object Key : CoroutineContext.Key<TransportFailureCapture>

    /** The last failure the engine raised, or `null` when none did. */
    var engineFailure: Throwable? = null
}

/** How one send ended. */
internal sealed interface SendOutcome<out T> {

    /** The server answered and the call returned [value]. */
    data class Succeeded<T>(val value: T) : SendOutcome<T>

    /**
     * The call threw [thrown]; [engineFailure] is what the HTTP engine itself raised, or `null` when
     * the request never reached the engine.
     */
    data class Failed(val thrown: Exception, val engineFailure: Throwable?) : SendOutcome<Nothing> {

        /** Whether the request provably never reached the server. */
        val neverReachedServer: Boolean
            get() = (engineFailure ?: thrown).provesRequestNeverSent()
    }
}

/**
 * Runs [send], keeping what the HTTP engine raised if it fails. Cancellation is rethrown.
 */
internal suspend fun <T> sendCapturingEngineFailure(send: suspend () -> T): SendOutcome<T> {
    val capture = TransportFailureCapture()
    return try {
        SendOutcome.Succeeded(withContext(capture) { send() })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        SendOutcome.Failed(failure, capture.engineFailure)
    }
}

/** Set on a client's HTTP client once the capture interceptor is installed. */
private val TransportCaptureInstallation = AttributeKey<AtomicBoolean>("io.github.nesfeder.supabase.offline.TransportFailureCapture")

/**
 * Installs, once per client, an interceptor next to the engine that records the engine's own failure
 * for requests sent through [sendCapturingEngineFailure], and rethrows it unchanged.
 */
@OptIn(SupabaseInternal::class)
internal fun SupabaseClient.installTransportFailureCapture() {
    val client = httpClient.httpClient
    val installation = client.attributes.computeIfAbsent(TransportCaptureInstallation) { AtomicBoolean(false) }
    if (!installation.compareAndSet(expectedValue = false, newValue = true)) return
    client.plugin(HttpSend).intercept { request ->
        val capture = currentCoroutineContext()[TransportFailureCapture] ?: return@intercept execute(request)
        try {
            execute(request)
        } catch (failure: Exception) {
            if (failure !is CancellationException) capture.engineFailure = failure
            throw failure
        }
    }
}
