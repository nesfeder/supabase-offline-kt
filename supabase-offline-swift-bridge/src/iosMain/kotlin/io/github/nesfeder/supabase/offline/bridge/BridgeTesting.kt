// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.bridge

import io.github.nesfeder.supabase.offline.bridge.internal.BRIDGE_LOG_TAG
import io.github.nesfeder.supabase.offline.bridge.internal.assembleSupabaseClient
import io.github.nesfeder.supabase.offline.bridge.internal.logSafely
import io.github.nesfeder.supabase.offline.bridge.internal.toByteArray
import io.github.nesfeder.supabase.offline.bridge.internal.toNSData
import io.github.nesfeder.supabase.offline.testing.inMemory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.IOException
import platform.Foundation.NSData

/**
 * Clients for the tests and previews of an app: the real engine, with its local data in memory,
 * talking to a server the app scripts.
 */
public object BridgeTesting {

    /**
     * Opens a client whose cache, mutation queue and file cache live in memory, and whose every
     * request is answered by [server]. Every option applies as in [BridgeClient.open] but
     * [BridgeClientOptions.dataDirectory], which nothing reads, and PostgREST's retries, which are
     * off so a failure the server scripts arrives at once.
     *
     * The client watches no connectivity monitor: the network reads as unknown (treated as online)
     * until the app reports it ([BridgeClientOptions.reportsNetworkAvailability]) or calls
     * [BridgeClient.disableNetwork].
     *
     * @param clock the time the client sees, or `null` for the device's.
     */
    public fun openInMemory(
        options: BridgeClientOptions,
        server: BridgeFakeServer,
        clock: BridgeClock?,
    ): BridgeOpenResult =
        openBridgeClient(options) { networkAvailability ->
            assembleSupabaseClient(
                options = options,
                engine = fakeServerEngine(server, options.logSink),
                networkAvailability = networkAvailability,
                postgrestRetries = 0,
            ) {
                inMemory(clock?.let(::ScriptedClock) ?: Clock.System)
            }
        }
}

/** A server an app scripts: it answers each request of an in-memory client. */
public interface BridgeFakeServer {

    /** Answers [request], once, through [responder], on any thread and at any later time. */
    public fun respond(request: BridgeHttpRequest, responder: BridgeHttpResponder)
}

/** The time an in-memory client sees, for a test that moves it. */
public interface BridgeClock {

    /** The current time, in milliseconds since 1970. */
    public fun nowEpochMillis(): Long
}

/**
 * One request an in-memory client sent.
 *
 * @property method the HTTP method, such as `GET`.
 * @property url the full URL, query included.
 * @property headers the request's headers, a header sent more than once joined with commas.
 * @property body the request's body; empty when it has none.
 */
public class BridgeHttpRequest
internal constructor(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String>,
    public val body: NSData,
)

/**
 * The one-time answer to a [BridgeHttpRequest]: a response, or a failed connection. A second
 * answer, or one after the request was abandoned, is dropped.
 */
public class BridgeHttpResponder
internal constructor(private val answer: (FakeServerAnswer) -> Unit) {

    /** Answers with an HTTP response. */
    public fun respond(status: Int, headers: Map<String, String>, body: NSData) {
        answer(FakeServerAnswer.Response(status, headers, body.toByteArray()))
    }

    /** Answers as a request that never reached the server: the client reads it as offline. */
    public fun failWithoutConnection(message: String) {
        answer(FakeServerAnswer.NoConnection(message))
    }
}

/** What a fake server answered. */
internal sealed interface FakeServerAnswer {

    /** An HTTP response. */
    class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) :
        FakeServerAnswer

    /** A failed connection, which the client sees as a transport failure. */
    class NoConnection(val message: String) : FakeServerAnswer
}

/** A Ktor engine whose every request [server] answers. */
private fun fakeServerEngine(server: BridgeFakeServer, logSink: BridgeLogSink?): MockEngine =
    MockEngine { request ->
        when (val answer = awaitAnswer(server, request, logSink)) {
            is FakeServerAnswer.Response ->
                respond(
                    content = answer.body,
                    status = HttpStatusCode.fromValue(answer.status),
                    headers =
                        headers { answer.headers.forEach { (name, value) -> append(name, value) } },
                )
            is FakeServerAnswer.NoConnection -> throw IOException(answer.message)
        }
    }

/** Hands [request] to [server] and waits for its one answer. */
private suspend fun awaitAnswer(
    server: BridgeFakeServer,
    request: HttpRequestData,
    logSink: BridgeLogSink?,
): FakeServerAnswer {
    val bridgeRequest = request.toBridge()
    return suspendCancellableCoroutine { continuation ->
        val answered = AtomicBoolean(false)
        val responder = BridgeHttpResponder { answer ->
            try {
                if (answered.compareAndSet(expectedValue = false, newValue = true)) {
                    continuation.resume(answer)
                }
            } catch (failure: Throwable) {
                logSafely(
                    logSink,
                    BridgeLogLevel.ERROR,
                    BRIDGE_LOG_TAG,
                    "A fake server's answer failed (${failure::class.simpleName})",
                )
            }
        }
        server.respond(bridgeRequest, responder)
    }
}

/** [this] request as the fake server sees it: the body's content type among the headers. */
private suspend fun HttpRequestData.toBridge(): BridgeHttpRequest {
    val joined = headers.entries().associate { (name, values) -> name to values.joinToString(",") }
    val contentType = body.contentType?.toString()
    return BridgeHttpRequest(
        method = method.value,
        url = url.toString(),
        headers =
            if (contentType == null || HttpHeaders.ContentType in joined) joined
            else joined + (HttpHeaders.ContentType to contentType),
        body = body.toByteArray().toNSData(),
    )
}

/** A [Clock] that reads the app's [BridgeClock]. */
private class ScriptedClock(private val clock: BridgeClock) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(clock.nowEpochMillis())
}
