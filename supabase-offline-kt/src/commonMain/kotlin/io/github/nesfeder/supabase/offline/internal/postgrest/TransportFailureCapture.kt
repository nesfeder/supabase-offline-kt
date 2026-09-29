// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.nesfeder.supabase.offline.internal.util.provesRequestNeverSent
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.util.AttributeKey
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Carried in a coroutine context, it watches every attempt the HTTP engine makes for requests sent
 * from that coroutine — each retry an app plugin makes, and any other request on the way, such as a
 * token refresh — before supabase-kt replaces a failure with an exception that keeps only its
 * message.
 */
private class TransportFailureCapture : AbstractCoroutineContextElement(Key) {

    /** The key a capture is found under in a coroutine context. */
    companion object Key : CoroutineContext.Key<TransportFailureCapture>

    /** How many attempts reached the engine. */
    var attempts: Int = 0

    /** How many of them failed in a way that proves the request never left the device. */
    var undeliveredAttempts: Int = 0
}

/** How one send ended. */
internal sealed interface SendOutcome<out T> {

    /** The server answered and the call returned [value]. */
    data class Succeeded<T>(val value: T) : SendOutcome<T>

    /**
     * The call threw [thrown].
     *
     * @property neverReachedServer Whether the request provably never reached the server: every
     *   attempt the engine made failed before connecting, none got a response of any kind, or —
     *   when no attempt reached the engine — [thrown] itself proves it.
     */
    data class Failed(val thrown: Exception, val neverReachedServer: Boolean) : SendOutcome<Nothing>
}

/** Runs [send], watching what the HTTP engine does with it. Cancellation is rethrown. */
internal suspend fun <T> sendCapturingEngineFailure(send: suspend () -> T): SendOutcome<T> {
    val capture = TransportFailureCapture()
    return try {
        SendOutcome.Succeeded(withContext(capture) { send() })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        val neverReachedServer =
            if (capture.attempts == 0) {
                failure.provesRequestNeverSent()
            } else {
                capture.undeliveredAttempts == capture.attempts
            }
        SendOutcome.Failed(failure, neverReachedServer)
    }
}

/** Set on a client's HTTP client once the capture interceptor is installed. */
private val TransportCaptureInstallation =
    AttributeKey<AtomicBoolean>("io.github.nesfeder.supabase.offline.TransportFailureCapture")

/**
 * Installs, once per client, an interceptor next to the engine that counts, for requests sent
 * through [sendCapturingEngineFailure], each attempt and each one that provably never left the
 * device, and rethrows every failure unchanged. A response of any status, and an attempt cancelled
 * or failed any other way, counts as one that may have left. An interceptor an app adds to
 * `HttpSend` after this one sits closer to the engine, and its own attempts are seen as one.
 */
@OptIn(SupabaseInternal::class)
internal fun SupabaseClient.installTransportFailureCapture() {
    val client = httpClient.httpClient
    val installation =
        client.attributes.computeIfAbsent(TransportCaptureInstallation) { AtomicBoolean(false) }
    if (!installation.compareAndSet(expectedValue = false, newValue = true)) return
    client.plugin(HttpSend).intercept { request ->
        val capture =
            currentCoroutineContext()[TransportFailureCapture] ?: return@intercept execute(request)
        capture.attempts++
        try {
            execute(request)
        } catch (failure: Exception) {
            if (failure !is CancellationException && failure.provesRequestNeverSent())
                capture.undeliveredAttempts++
            throw failure
        }
    }
}
