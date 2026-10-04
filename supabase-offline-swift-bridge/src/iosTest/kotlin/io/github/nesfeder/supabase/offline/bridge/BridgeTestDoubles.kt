// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.bridge

import io.github.nesfeder.supabase.offline.bridge.internal.toByteArray
import io.github.nesfeder.supabase.offline.bridge.internal.toNSData
import io.ktor.http.decodeURLQueryComponent
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** The URL every test client is opened against; nothing is ever sent there. */
const val TEST_URL = "https://fixture.supabase.co"

/** The rows the default fake server answers every read with. */
const val SERVER_ROWS = """[{"id":1,"title":"Buy milk"}]"""

/**
 * A test that waits on the bridge's background threads: it runs on a real dispatcher, so every
 * bounded wait inside it is real time, never the test scheduler's skipped time.
 */
fun bridgeTest(block: suspend CoroutineScope.() -> Unit): TestResult = runTest {
    withContext(Dispatchers.Default) { block() }
}

/** One request a [RecordingServer] received, with its body as text. */
class RecordedRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
) {
    /** The path, without the query. */
    val path: String
        get() = url.substringAfter("://").substringAfter('/', "").let { "/" + it.substringBefore('?') }

    /** The query parameters, decoded, in the order they were sent. */
    val queryParameters: List<Pair<String, String>>
        get() {
            val query = url.substringAfter('?', "")
            if (query.isEmpty()) return emptyList()
            return query.split('&').map { pair ->
                pair.substringBefore('=').decodeURLQueryComponent(plusIsSpace = true) to
                    pair.substringAfter('=', "").decodeURLQueryComponent(plusIsSpace = true)
            }
        }

    /** The header [name], matched without regard to case. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** What a [RecordingServer] answers one request with. */
class ScriptedAnswer(
    val status: Int,
    val body: ByteArray,
    val contentType: String = "application/json",
) {
    companion object {
        /** A JSON answer. */
        fun json(status: Int, body: String): ScriptedAnswer =
            ScriptedAnswer(status, body.encodeToByteArray())
    }
}

/**
 * A fake server for one test: records every request and answers it by [answer] (PostgREST reads
 * get [SERVER_ROWS], writes `[]`, Storage reads `a photo`). While [online] is `false`, every
 * request fails as if the connection were down. An answer of `null` holds the request in
 * [heldResponders] until the test answers it.
 */
class RecordingServer(
    @Volatile var answer: (RecordedRequest) -> ScriptedAnswer? = ::defaultAnswer,
) : BridgeFakeServer {
    private val recorded = AtomicReference(emptyList<RecordedRequest>())
    private val held = AtomicReference(emptyList<BridgeHttpResponder>())

    /** Whether requests reach this server. */
    @Volatile var online: Boolean = true

    /** Every request so far, in order. */
    val requests: List<RecordedRequest>
        get() = recorded.load()

    /** The responders of requests held by a `null` answer. */
    val heldResponders: List<BridgeHttpResponder>
        get() = held.load()

    override fun respond(request: BridgeHttpRequest, responder: BridgeHttpResponder) {
        val recordedRequest =
            RecordedRequest(
                method = request.method,
                url = request.url,
                headers = request.headers,
                body = request.body.toByteArray().decodeToString(),
            )
        recorded.update { it + recordedRequest }
        if (!online) {
            responder.failWithoutConnection("network down")
            return
        }
        val scripted = answer(recordedRequest)
        if (scripted == null) {
            held.update { it + responder }
            return
        }
        responder.respond(
            status = scripted.status,
            headers = mapOf("Content-Type" to scripted.contentType),
            body = scripted.body.toNSData(),
        )
    }

    companion object {
        /** The default answers, by path and method. */
        fun defaultAnswer(request: RecordedRequest): ScriptedAnswer =
            when {
                request.path.startsWith("/storage/") && request.method == "GET" ->
                    ScriptedAnswer(200, "a photo".encodeToByteArray(), "application/octet-stream")
                request.path.startsWith("/storage/") -> ScriptedAnswer.json(200, "{}")
                request.method == "GET" -> ScriptedAnswer.json(200, SERVER_ROWS)
                request.method == "HEAD" -> ScriptedAnswer.json(200, "")
                else -> ScriptedAnswer.json(201, "[]")
            }
    }
}

/** Receives a completion's answers and checks there is exactly one. */
class CompletionProbe<T> {
    private val calls = AtomicInt(0)
    private val answer = CompletableDeferred<Pair<T?, BridgeError?>>()

    /** The completion to hand a bridge call. */
    val completion: (T?, BridgeError?) -> Unit = { value, error ->
        calls.incrementAndFetch()
        answer.complete(value to error)
    }

    /** The completion of a call that answers with an error only. */
    val errorCompletion: (BridgeError?) -> Unit = { error -> completion(null, error) }

    /** How many times the completion fired. */
    val callCount: Int
        get() = calls.load()

    /** The answer, waiting at most ten seconds. */
    suspend fun await(): Pair<T?, BridgeError?> = withTimeout(10.seconds) { answer.await() }

    /** The value, failing the test on an error. */
    suspend fun awaitValue(): T? {
        val (value, error) = await()
        check(error == null) { "expected a value, got $error" }
        return value
    }

    /** The error, failing the test on a value. */
    suspend fun awaitError(): BridgeError =
        checkNotNull(await().second) { "expected an error, got a value" }
}

/** Receives a subscription's values in order and its one completion. */
class SubscriptionProbe<T> {
    private val values = Channel<T>(Channel.UNLIMITED)

    /** The completion side. */
    val ending = CompletionProbe<Unit>()

    /** The value handler to hand a bridge subscription. */
    val onValue: (T) -> Unit = { values.trySend(it) }

    /** The completion handler to hand a bridge subscription. */
    val onCompletion: (BridgeError?) -> Unit = ending.errorCompletion

    /** The first value, in order from the last one taken, that [matches], waiting ten seconds. */
    suspend fun awaitValue(matches: (T) -> Boolean = { true }): T =
        withTimeout(10.seconds) {
            var value = values.receive()
            while (!matches(value)) value = values.receive()
            value
        }
}

/** Records every log message, then throws [failure] when one is given. */
class RecordingLogSink(private val failure: (() -> Throwable)? = null) : BridgeLogSink {
    private val recorded = AtomicReference(emptyList<String>())

    /** Every message so far, as `LEVEL tag: message`. */
    val messages: List<String>
        get() = recorded.load()

    override fun log(level: BridgeLogLevel, tag: String, message: String) {
        recorded.update { it + "$level $tag: $message" }
        failure?.let { throw it() }
    }
}

/** A clock a test moves. */
class FakeClock(@Volatile var nowMillis: Long) : BridgeClock {
    override fun nowEpochMillis(): Long = nowMillis
}

/**
 * Answers every token request with [token], or fails it with [failure] when that is set; throws
 * [thrown] instead of answering when that is given.
 */
class FakeAccessTokenProvider(
    private val token: String?,
    private val failure: String? = null,
    private val thrown: (() -> Throwable)? = null,
) : BridgeAccessTokenProvider {
    override fun fetchAccessToken(callback: BridgeAccessTokenCallback) {
        thrown?.let { throw it() }
        if (failure != null) callback.fail(failure) else callback.succeed(token)
    }
}

/** Options for a test client of [TEST_URL], configured by [configure]. */
fun testOptions(configure: BridgeClientOptions.() -> Unit = {}): BridgeClientOptions =
    BridgeClientOptions(
            supabaseUrl = TEST_URL,
            supabaseKey = "fixture-anon-key",
            dataDirectory = "/in-memory/bridge-test",
        )
        .apply(configure)

/** An in-memory client of [server], failing the test when it cannot be opened. */
fun openTestClient(
    server: RecordingServer,
    clock: BridgeClock? = null,
    configure: BridgeClientOptions.() -> Unit = {},
): BridgeClient {
    val opened = BridgeTesting.openInMemory(testOptions(configure), server, clock)
    return checkNotNull(opened.client) { "the in-memory client did not open: ${opened.error}" }
}

/** Closes this client and waits for it. */
suspend fun BridgeClient.closeAndWait() {
    val probe = CompletionProbe<Unit>()
    close(probe.errorCompletion)
    probe.await()
}

/** A read description of [table] with [strategy], as the Swift side sends it. */
fun readOf(table: String = "todos", strategy: String = "networkFirst"): String =
    """{"v":1,"table":"$table","strategy":{"kind":"$strategy"}}"""

/** Reads [query] through [client] and returns the answer. */
suspend fun BridgeClient.selectAndWait(query: String): Pair<BridgeQueryResult?, BridgeError?> {
    val probe = CompletionProbe<BridgeQueryResult>()
    select(query, probe.completion)
    return probe.await()
}

/** Writes through [client] and returns the answer. */
suspend fun BridgeClient.mutateAndWait(
    mutation: String,
    body: String?,
): Pair<BridgeMutationResult?, BridgeError?> {
    val probe = CompletionProbe<BridgeMutationResult>()
    mutate(mutation, body, probe.completion)
    return probe.await()
}

/** Runs a call that answers with an error only, and returns that answer. */
suspend fun awaitErrorOnly(call: ((BridgeError?) -> Unit) -> BridgeCancellable): BridgeError? {
    val probe = CompletionProbe<Unit>()
    call(probe.errorCompletion)
    return probe.await().second
}

/** An insert of one row into `todos`, queued while offline. */
const val INSERT_TODO = """{"v":1,"operation":"insert","table":"todos"}"""
