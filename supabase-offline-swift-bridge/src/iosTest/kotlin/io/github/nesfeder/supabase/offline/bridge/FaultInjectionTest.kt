// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalNativeApi::class)

package io.github.nesfeder.supabase.offline.bridge

import io.github.nesfeder.supabase.offline.bridge.internal.toNSData
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.processUnhandledException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import platform.Foundation.NSData

/**
 * No Kotlin exception crosses the boundary: a failure thrown inside every entry point, whatever its
 * type, arrives at its caller as one error value, the process lives on, and the client still works.
 */
class FaultInjectionTest {

    private val server = RecordingServer()

    /** A failure of each family a path can throw: an exception, a state error, an `Error`. */
    private val faults: List<() -> Throwable> =
        listOf(
            { RuntimeException("injected") },
            { IllegalStateException("injected") },
            { NotImplementedError("injected") },
        )

    @Test
    fun a_failure_inside_every_asynchronous_call_arrives_as_one_error_value() = bridgeTest {
        // Given — one client, and every asynchronous entry point
        val client = openTestClient(server)
        val probes = mutableListOf<CompletionProbe<*>>()
        val mismatches = mutableListOf<String>()

        // When — each fails inside, once per family of failure
        for (fault in faults) {
            for ((name, call) in asynchronousCalls(client)) {
                val thrown = fault()
                client.runner.faultInjector = { operation -> if (operation == name) throw thrown }
                val probe = call()
                probes += probe
                val error = probe.await().second
                if (
                    error?.kind != BridgeErrorKind.UNEXPECTED ||
                        error.debugType != thrown::class.simpleName
                ) {
                    mismatches += "$name with ${thrown::class.simpleName}: $error"
                }
            }
        }
        client.runner.faultInjector = null
        delay(200)
        val (afterward, error) = client.selectAndWait(readOf())

        // Then — every call answered once with the failure as a value, and the client still reads
        assertEquals(emptyList(), mismatches)
        assertEquals(emptyList(), probes.filter { it.callCount != 1 }.map { it.callCount })
        assertNull(error)
        assertEquals(SERVER_ROWS, afterward?.data)
        client.closeAndWait()
    }

    @Test
    fun a_failure_inside_every_synchronous_call_arrives_as_a_value() = bridgeTest {
        // Given
        val sink = RecordingLogSink()
        val client = openTestClient(server) { logSink = sink }

        // When — each synchronous entry point fails inside
        client.runner.faultInjector = { throw NotImplementedError("injected") }
        val status = client.currentSyncStatus()
        val publicUrl = client.storage.publicUrl("avatars", "a.png")
        val authenticatedUrl = client.storage.authenticatedUrl("avatars", "a.png")
        client.setNetworkAvailable(true)
        client.runner.faultInjector = null

        // Then
        assertEquals(BridgeErrorKind.UNEXPECTED, status.lastSyncError?.kind)
        assertEquals(BridgeErrorKind.UNEXPECTED, publicUrl.error?.kind)
        assertNull(publicUrl.value)
        assertEquals(BridgeErrorKind.UNEXPECTED, authenticatedUrl.error?.kind)
        assertTrue(sink.messages.any { "Reporting the network" in it })
        assertNull(client.currentSyncStatus().lastSyncError)
        client.closeAndWait()
    }

    @Test
    fun a_failure_inside_close_arrives_as_a_value_and_the_client_still_closes() = bridgeTest {
        // Given
        val client = openTestClient(server)
        client.runner.faultInjector = { operation ->
            if (operation == "close") throw IllegalStateException("injected")
        }

        // When
        val closing = awaitErrorOnly { client.close(it) }
        val (_, afterward) = client.selectAndWait(readOf())

        // Then
        assertEquals(BridgeErrorKind.UNEXPECTED, closing?.kind)
        assertEquals(BridgeErrorKind.CLIENT_CLOSED, afterward?.kind)
    }

    @Test
    fun a_failure_thrown_by_an_open_arrives_as_a_value() {
        // When — building the client fails
        val opened = openBridgeClient(testOptions()) { throw NotImplementedError("injected") }

        // Then
        assertNull(opened.client)
        assertEquals(BridgeErrorKind.UNEXPECTED, opened.error?.kind)
        assertEquals("NotImplementedError", opened.error?.debugType)
    }

    @Test
    fun an_error_thrown_inside_the_http_path_arrives_as_a_value() = bridgeTest {
        // Given — a fake server that throws an Error while answering
        server.answer = { throw NotImplementedError("injected in the engine") }
        val client = openTestClient(server)

        // When
        val (_, error) = client.selectAndWait(readOf(strategy = "networkOnly"))

        // Then
        assertEquals(BridgeErrorKind.UNEXPECTED, error?.kind)
        assertEquals("NotImplementedError", error?.debugType)
        server.answer = RecordingServer::defaultAnswer
        assertNull(client.selectAndWait(readOf()).second)
        client.closeAndWait()
    }

    @Test
    fun a_token_provider_that_throws_fails_only_its_call() = bridgeTest {
        // Given
        val client =
            openTestClient(server) {
                accessTokenProvider =
                    FakeAccessTokenProvider(token = null) {
                        NotImplementedError("injected in the token provider")
                    }
            }

        // When
        val (_, error) = client.selectAndWait(readOf(strategy = "networkOnly"))

        // Then
        assertEquals(BridgeErrorKind.UNEXPECTED, error?.kind)
        client.closeAndWait()
    }

    @Test
    fun a_completion_or_value_handler_that_throws_is_reported_and_the_client_keeps_working() =
        bridgeTest {
            // Given
            val sink = RecordingLogSink()
            val client = openTestClient(server) { logSink = sink }
            val values = SubscriptionProbe<BridgeSyncStatus>()

            // When — a completion and a value handler throw
            val answered = CompletionProbe<BridgeQueryResult>()
            client.select(readOf()) { value, error ->
                answered.completion(value, error)
                throw IllegalStateException("thrown by a caller")
            }
            answered.await()
            client.observeSyncStatus(
                onStatus = { status ->
                    values.onValue(status)
                    throw IllegalStateException("thrown by a caller")
                },
                onCompletion = values.onCompletion,
            )
            values.awaitValue()
            awaitErrorOnly { client.disableNetwork(it) }
            val disabled = values.awaitValue { it.network == BridgeNetworkStatus.DISABLED }
            awaitErrorOnly { client.enableNetwork(it) }
            val (afterward, error) = client.selectAndWait(readOf())

            // Then — both reported; the subscription went on, and the client still reads
            assertEquals(BridgeNetworkStatus.DISABLED, disabled.network)
            awaitUntil { sink.messages.any { "The completion of select" in it } }
            awaitUntil { sink.messages.any { "A value handler of observeSyncStatus" in it } }
            assertNull(error)
            assertNotNull(afterward)
            client.closeAndWait()
        }

    @Test
    fun a_log_sink_that_throws_never_breaks_a_call() = bridgeTest {
        // Given
        val sink = RecordingLogSink { NotImplementedError("injected in the sink") }
        val client =
            openTestClient(server) {
                logLevel = BridgeLogLevel.DEBUG
                logSink = sink
            }

        // When
        val (read, error) = client.selectAndWait(readOf())

        // Then
        assertNull(error)
        assertEquals(SERVER_ROWS, read?.data)
        awaitUntil { sink.messages.isNotEmpty() }
        client.closeAndWait()
    }

    @Test
    fun an_exception_that_escapes_every_handler_is_reported_and_the_process_lives() = bridgeTest {
        // Given — a client whose sink is the latest opened
        val sink = RecordingLogSink()
        val client = openTestClient(server) { logSink = sink }

        // When — a failure reaches the runtime's last resort
        processUnhandledException(RuntimeException("escaped"))

        // Then
        assertTrue(sink.messages.any { "An unhandled failure was caught (RuntimeException)" in it })
        assertNull(client.selectAndWait(readOf()).second)
        client.closeAndWait()
    }

    /** Every asynchronous entry point, by the name its fault injector sees, as one call. */
    private fun asynchronousCalls(
        client: BridgeClient
    ): List<Pair<String, () -> CompletionProbe<*>>> {
        val bytes: NSData = "x".encodeToByteArray().toNSData()
        val storage = client.storage
        return listOf(
            "select" to { valued<BridgeQueryResult> { client.select(readOf(), it) } },
            "observe" to
                {
                    subscribed<BridgeQuerySnapshot> { value, end ->
                        client.observe(readOf(), value, end)
                    }
                },
            "mutate" to
                {
                    valued<BridgeMutationResult> { client.mutate(INSERT_TODO, """{"id":1}""", it) }
                },
            "rpc" to
                {
                    valued<BridgeQueryResult> { client.rpc("""{"v":1,"function":"f"}""", null, it) }
                },
            "pendingMutations" to
                {
                    valued<List<BridgePendingMutation>> { client.pendingMutations(null, it) }
                },
            "observePendingMutations" to
                {
                    subscribed<List<BridgePendingMutation>> { value, end ->
                        client.observePendingMutations(null, value, end)
                    }
                },
            "pendingMutationCount" to { valued<Int> { client.pendingMutationCount(null, it) } },
            "observePendingMutationCount" to
                {
                    subscribed<Int> { value, end ->
                        client.observePendingMutationCount(null, value, end)
                    }
                },
            "discardPendingMutation" to { unvalued { client.discardPendingMutation(1, it) } },
            "rejectedMutations" to
                {
                    valued<List<BridgeRejectedMutation>> { client.rejectedMutations(it) }
                },
            "observeRejectedMutations" to
                {
                    subscribed<List<BridgeRejectedMutation>> { value, end ->
                        client.observeRejectedMutations(value, end)
                    }
                },
            "dismissRejectedMutation" to { unvalued { client.dismissRejectedMutation(1, it) } },
            "retryRejectedMutation" to { unvalued { client.retryRejectedMutation(1, it) } },
            "syncPendingMutations" to
                {
                    valued<BridgeSyncReport> { client.syncPendingMutations(null, it) }
                },
            "observeSyncStatus" to
                {
                    subscribed<BridgeSyncStatus> { value, end ->
                        client.observeSyncStatus(value, end)
                    }
                },
            "disableNetwork" to { unvalued { client.disableNetwork(it) } },
            "enableNetwork" to { unvalued { client.enableNetwork(it) } },
            "clearLocalData" to { unvalued { client.clearLocalData(it) } },
            "applyServerRecord" to
                {
                    unvalued { client.applyServerRecord("todos", """{"id":1}""", null, null, it) }
                },
            "applyServerDelete" to
                {
                    unvalued { client.applyServerDelete("todos", """{"id":1}""", null, null, it) }
                },
            "upload" to { valued<String> { storage.upload("b", "p", bytes, null, it) } },
            "update" to { valued<String> { storage.update("b", "p", bytes, null, it) } },
            "uploadToSignedUrl" to
                {
                    valued<String> { storage.uploadToSignedUrl("b", "p", "t", bytes, null, it) }
                },
            "delete" to { unvalued { storage.delete("b", listOf("p"), it) } },
            "moveObject" to { unvalued { storage.moveObject("b", "p", "q", null, it) } },
            "copyObject" to { unvalued { storage.copyObject("b", "p", "q", null, it) } },
            "createSignedUploadUrl" to
                {
                    valued<String> { storage.createSignedUploadUrl("b", "p", false, it) }
                },
            "createSignedUrl" to
                {
                    valued<String> { storage.createSignedUrl("b", "p", 60, null, it) }
                },
            "createSignedUrls" to
                {
                    valued<String> { storage.createSignedUrls("b", listOf("p"), 60, it) }
                },
            "download" to { valued<NSData> { storage.download("b", "p", false, null, it) } },
            "list" to { valued<String> { storage.list("b", "", null, it) } },
            "info" to { valued<String> { storage.info("b", "p", it) } },
            "exists" to { valued<Boolean> { storage.exists("b", "p", it) } },
            "cachedFile" to { valued<BridgeCachedFile> { storage.cachedFile("b", "p", null, it) } },
            "ensureCachedFile" to
                {
                    valued<BridgeCachedFile> { storage.ensureCachedFile("b", "p", false, it) }
                },
            "evictCachedFile" to { unvalued { storage.evictCachedFile("b", "p", null, it) } },
            "clearFileCache" to { unvalued { storage.clearFileCache(it) } },
            "pinCachedFile" to { unvalued { storage.pinCachedFile("b", "p", it) } },
            "unpinCachedFile" to { unvalued { storage.unpinCachedFile("b", "p", it) } },
            "isCachedFilePinned" to
                {
                    valued<Boolean> { storage.isCachedFilePinned("b", "p", it) }
                },
        )
    }

    /** A call answering with a value or an error. */
    private fun <T> valued(
        start: ((T?, BridgeError?) -> Unit) -> BridgeCancellable
    ): CompletionProbe<T> = CompletionProbe<T>().also { start(it.completion) }

    /** A call answering with an error only. */
    private fun unvalued(
        start: ((BridgeError?) -> Unit) -> BridgeCancellable
    ): CompletionProbe<Unit> = CompletionProbe<Unit>().also { start(it.errorCompletion) }

    /** A subscription: its completion is the probe. */
    private fun <T> subscribed(
        start: (onValue: (T) -> Unit, onCompletion: (BridgeError?) -> Unit) -> BridgeCancellable
    ): CompletionProbe<Unit> =
        CompletionProbe<Unit>().also { probe -> start({}, probe.errorCompletion) }
}
