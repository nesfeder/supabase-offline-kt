// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.nesfeder.supabase.offline.testdoubles.FakeResumableCache
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingUncaughtExceptions
import io.github.nesfeder.supabase.offline.testdoubles.assertClientClosedError
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `close()` releases what the client holds: it stops the client's background work — without
 * touching the scope the caller handed in — runs the factory's release once, and refuses later use.
 * Nothing the client started reaches the server once it has closed: a replay that outlived it would
 * send writes whose bookkeeping can no longer be recorded, and the next client would send them
 * again.
 */
@OptIn(ExperimentalAtomicApi::class, FlowPreview::class)
class SupabaseOfflineClientCloseTest {

    private val callerJob = SupervisorJob()
    private val callerScope = CoroutineScope(callerJob + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        callerJob.cancel()
    }

    @Test
    fun close_stops_the_clients_background_work_and_ends_a_live_read_of_every_strategy() = runTest {
        for (strategy in FlowReadStrategy.entries) {
            // Given — a live reactive read of this strategy, which has emitted
            val releases = AtomicInt(0)
            val server = RecordingPostgrestServer()
            val client =
                newTestOfflineClient(
                    supabase = server.supabaseClient(),
                    onClose = { releases.addAndFetch(1) },
                    coordinatorScope = callerScope,
                )
            val emitted = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Throwable?>()
            val observer = callerScope.launch {
                ended.complete(
                    runCatching {
                        client.from("todos").selectAsFlow(readStrategy = strategy).collect {
                            emitted.complete(Unit)
                        }
                    }
                        .exceptionOrNull()
                )
            }
            withContext(Dispatchers.Default) { withTimeout(5.seconds) { emitted.await() } }

            // When
            client.close()
            client.close()
            val requestsAtClose = server.requests.size

            // Then — the read fails in band rather than ending as if the table were done,
            // everything
            // the client started is gone, nothing more reaches the server, the caller's own scope
            // is
            // untouched, and the factory's release ran exactly once
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    observer.join()
                    while (callerJob.children.any()) delay(5)
                }
                delay(SETTLE_MILLIS)
            }
            assertClientClosedError(ended.await(), "the $strategy read live at close()")
            assertEquals(
                requestsAtClose,
                server.requests.size,
                "the $strategy read sent a request after close()",
            )
            assertTrue(callerJob.isActive, "close() cancelled the caller's scope")
            assertEquals(
                1,
                releases.load(),
                "$strategy: the factory's release ran ${releases.load()} times",
            )
        }
    }

    @Test
    fun a_read_of_any_strategy_made_after_close_fails_with_the_closed_error_and_sends_nothing() =
        runTest {
            for (strategy in FlowReadStrategy.entries) {
                // Given — a builder obtained before close()
                val server = RecordingPostgrestServer()
                val client =
                    newTestOfflineClient(
                        supabase = server.supabaseClient(),
                        coordinatorScope = callerScope,
                    )
                val builder = client.from("todos")

                // When
                client.close()
                val outcome =
                    withContext(Dispatchers.Default) {
                        withTimeout(5.seconds) {
                            runCatching { builder.selectAsFlow(readStrategy = strategy).toList() }
                        }
                    }

                // Then
                assertClientClosedError(
                    outcome.exceptionOrNull(),
                    "a $strategy read started after close(), which gave ${outcome.getOrNull()?.size} emissions,",
                )
                assertEquals(
                    0,
                    server.requests.size,
                    "a $strategy read started after close() sent a request",
                )
            }
        }

    @Test
    fun close_called_inside_a_collector_returns_at_once_and_ends_the_collection() = runTest {
        val flows: List<Pair<String, (SupabaseOfflineClient) -> Flow<*>>> =
            listOf<Pair<String, (SupabaseOfflineClient) -> Flow<*>>>(
                "pendingMutationCountFlow()" to { client -> client.pendingMutationCountFlow() },
                "pendingMutationCountFlow(table)" to
                    { client ->
                        client.pendingMutationCountFlow("todos")
                    },
            ) +
                FlowReadStrategy.entries.map { strategy ->
                    "selectAsFlow($strategy)" to
                        { client: SupabaseOfflineClient ->
                            client.from("todos").selectAsFlow(readStrategy = strategy)
                        }
                }
        for ((name, flowOf) in flows) {
            // Given — a client that says when a close() gives up waiting for calls in flight
            val releases = AtomicInt(0)
            val gaveUp = AtomicInt(0)
            val client =
                newTestOfflineClient(
                    supabase = RecordingPostgrestServer().supabaseClient(),
                    onClose = { releases.addAndFetch(1) },
                    coordinatorScope = callerScope,
                    onStillClosing = { gaveUp.addAndFetch(1) },
                )
            val releasedWhenCloseReturned = CompletableDeferred<Int>()

            // When — the collector closes the client from inside its body
            val outcome =
                withContext(Dispatchers.Default) {
                    withTimeout(15.seconds) {
                        runCatching {
                            flowOf(client).collect {
                                if (!releasedWhenCloseReturned.isCompleted) {
                                    client.close()
                                    releasedWhenCloseReturned.complete(releases.load())
                                }
                            }
                        }
                    }
                }

            // Then — close() did not wait on the collection it was called from: it never gave up,
            // and the database was released by the time it returned. The collection ended with the
            // closed error
            assertEquals(0, gaveUp.load(), "$name: close() inside the collector gave up waiting")
            assertEquals(
                1,
                releasedWhenCloseReturned.await(),
                "$name: the database was not released when close() returned",
            )
            assertClientClosedError(
                outcome.exceptionOrNull(),
                "$name after close() inside its collector",
            )
            assertEquals(
                1,
                releases.load(),
                "$name: the factory's release ran ${releases.load()} times",
            )
        }
    }

    @Test
    fun a_collection_that_stops_reading_does_not_hold_close() = runTest {
        for (variant in listOf(STUCK_IN_ITS_BODY, NEVER_READ_AGAIN, NEVER_READ_AGAIN_RENDEZVOUS)) {
            for (strategy in FlowReadStrategy.entries) {
                // Given — reads the server answers and writes that queue, so every read keeps
                // wanting
                // to emit, and a collection that stops reading after its first value
                val releases = AtomicInt(0)
                val gaveUp = AtomicInt(0)
                val server =
                    RecordingPostgrestServer(
                        answer = { request ->
                            if (request.method == "GET") okJson("[]") else connectivityFailure()
                        }
                    )
                val client =
                    newTestOfflineClient(
                        supabase = server.supabaseClient(),
                        onClose = { releases.addAndFetch(1) },
                        coordinatorScope = callerScope,
                        onStillClosing = { gaveUp.addAndFetch(1) },
                    )
                val uncaught = RecordingUncaughtExceptions()
                val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + uncaught)
                val entered = CompletableDeferred<Unit>()
                val read = client.from("todos").selectAsFlow(readStrategy = strategy)
                if (variant == STUCK_IN_ITS_BODY) {
                    appScope.launch {
                        read.collect {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    }
                } else if (variant == NEVER_READ_AGAIN) {
                    // The app's own launch holds the channel, and waits on it, as long as it lives
                    appScope.launch {
                        val values = read.produceIn(this)
                        values.receive()
                        entered.complete(Unit)
                    }
                } else {
                    // Rendezvous, so the channel nobody reads holds its producer in its send
                    val values = read.buffer(Channel.RENDEZVOUS).produceIn(appScope)
                    appScope.launch {
                        values.receive()
                        entered.complete(Unit)
                    }
                }
                withContext(Dispatchers.Default) { withTimeout(5.seconds) { entered.await() } }
                repeat(3) { index ->
                    client
                        .from("todos")
                        .insertJson(buildJsonArray { add(buildJsonObject { put("id", index) }) })
                }

                // When
                client.close()
                val releasedWhenCloseReturned = releases.load()
                if (variant != STUCK_IN_ITS_BODY) {
                    // The unread channels' producers end at close(); the app's jobs end with them
                    withContext(Dispatchers.Default) {
                        withTimeout(5.seconds) {
                            appScope.coroutineContext.job.children.forEach { job -> job.join() }
                        }
                    }
                }

                // Then — close() did not wait on the collection: it never gave up, and the database
                // was released by the time it returned. Nothing reached the app's scope
                assertEquals(0, gaveUp.load(), "$variant, $strategy: close() gave up waiting")
                assertEquals(
                    1,
                    releasedWhenCloseReturned,
                    "$variant, $strategy: the database was not released when close() returned",
                )
                assertEquals(
                    emptyList(),
                    uncaught.recorded(),
                    "$variant, $strategy: the app's scope failed",
                )
                appScope.cancel()
            }
        }
    }

    @Test
    fun a_flow_the_app_stopped_reading_through_produceIn_ends_quietly_at_close() = runTest {
        val flows: List<Pair<String, (SupabaseOfflineClient) -> Flow<*>>> =
            listOf<Pair<String, (SupabaseOfflineClient) -> Flow<*>>>(
                "pendingMutationCountFlow()" to { client -> client.pendingMutationCountFlow() },
                "pendingMutationCountFlow(table)" to
                    { client ->
                        client.pendingMutationCountFlow("todos")
                    },
            ) +
                FlowReadStrategy.entries.map { strategy ->
                    "selectAsFlow($strategy)" to
                        { client: SupabaseOfflineClient ->
                            client.from("todos").selectAsFlow(readStrategy = strategy)
                        }
                }
        for ((name, flowOf) in flows) {
            // Given — an app's launch that holds a client flow through produceIn, reads it once and
            // then stops reading while writes queue, in a scope with a plain Job and another job
            val releases = AtomicInt(0)
            val gaveUp = AtomicInt(0)
            val server =
                RecordingPostgrestServer(
                    answer = { request ->
                        if (request.method == "GET") okJson("[]") else connectivityFailure()
                    }
                )
            val client =
                newTestOfflineClient(
                    supabase = server.supabaseClient(),
                    onClose = { releases.addAndFetch(1) },
                    coordinatorScope = callerScope,
                    onStillClosing = { gaveUp.addAndFetch(1) },
                )
            val uncaught = RecordingUncaughtExceptions()
            val appJob = Job()
            val appScope = CoroutineScope(appJob + Dispatchers.Default + uncaught)
            val sibling = appScope.launch { awaitCancellation() }
            val held = CompletableDeferred<ReceiveChannel<*>>()
            val holder = appScope.launch {
                val values = flowOf(client).produceIn(this)
                values.receive()
                held.complete(values)
            }
            val values =
                withContext(Dispatchers.Default) { withTimeout(5.seconds) { held.await() } }
            repeat(5) { index ->
                client
                    .from("todos")
                    .insertJson(buildJsonArray { add(buildJsonObject { put("id", index) }) })
            }

            // When
            client.close()
            val releasedWhenCloseReturned = releases.load()
            withContext(Dispatchers.Default) { withTimeout(5.seconds) { holder.join() } }

            // Then — close() neither waited on the channel nor failed the app: its launch ended as
            // it would have, its scope and the job beside it are untouched, nothing reached its
            // handler, and reading again gives the closed error
            assertEquals(0, gaveUp.load(), "$name: close() gave up waiting")
            assertEquals(
                1,
                releasedWhenCloseReturned,
                "$name: the database was not released when close() returned",
            )
            assertEquals(false, holder.isCancelled, "$name: the app's launch was failed")
            assertEquals(true, sibling.isActive, "$name: the job beside it was cancelled")
            assertEquals(true, appJob.isActive, "$name: the app's scope was cancelled")
            assertEquals(
                emptyList(),
                uncaught.recorded(),
                "$name: the closed error escaped into the app's scope",
            )
            var ending = values.receiveCatching()
            while (!ending.isClosed) ending = values.receiveCatching()
            assertClientClosedError(ending.exceptionOrNull(), "$name: reading again after close()")
            appScope.cancel()
        }
    }

    @Test
    fun a_second_close_returns_only_once_the_database_is_released() = runTest {
        // Given — a write on the wire, held at the server, and a first close() waiting for it
        val releases = AtomicInt(0)
        val arrived = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        val server =
            RecordingPostgrestServer(
                answer = {
                    arrived.complete(Unit)
                    held.await()
                    okJson("[]")
                }
            )
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                onClose = { releases.addAndFetch(1) },
                coordinatorScope = callerScope,
            )
        val write = callerScope.async {
            runCatching {
                client
                    .from("todos")
                    .insertJson(buildJsonArray { add(buildJsonObject { put("id", 1) }) })
            }
        }
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { arrived.await() } }
        val first = callerScope.async {
            client.close()
            releases.load()
        }
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (runCatching { client.from("todos") }.isSuccess) delay(1)
            }
        }

        // When — a second close() while the first still waits, then the server answers
        val second = callerScope.async {
            client.close()
            releases.load()
        }
        withContext(Dispatchers.Default) { delay(SETTLE_MILLIS) }
        val secondReturnedFirst = second.isCompleted
        held.complete(Unit)

        // Then — neither returned before the database was released, and it was released once
        assertFalse(
            secondReturnedFirst,
            "the second close() returned while the first was still waiting",
        )
        val releasedWhenEachReturned =
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { listOf(first.await(), second.await()) }
            }
        assertEquals(listOf(1, 1), releasedWhenEachReturned)
        assertIs<MutationOutcome.Applied>(
            write.await().getOrNull(),
            "the write in flight at close() got ${write.await().exceptionOrNull()}",
        )
    }

    @Test
    fun a_storage_call_on_the_wire_at_close_is_stopped_with_the_closed_error() = runTest {
        val calls: List<Pair<String, suspend (OfflineBucketApi) -> Any?>> =
            listOf(
                "upload" to { bucket -> bucket.upload("u-1/new.png", byteArrayOf(1)) },
                "update" to { bucket -> bucket.update("u-1/new.png", byteArrayOf(1)) },
                "createSignedUrl" to { bucket -> bucket.createSignedUrl("u-1/me.png", 60.seconds) },
                "createSignedUploadUrl" to
                    { bucket ->
                        bucket.createSignedUploadUrl("u-1/new.png")
                    },
                "list" to { bucket -> bucket.list("u-1") },
                "info" to { bucket -> bucket.info("u-1/me.png") },
                "exists" to { bucket -> bucket.exists("u-1/me.png") },
                "delete" to { bucket -> bucket.delete("u-1/me.png") },
                "move" to { bucket -> bucket.move("u-1/me.png", "u-1/moved.png") },
                "copy" to { bucket -> bucket.copy("u-1/me.png", "u-1/copy.png") },
            )
        for ((name, call) in calls) {
            // Given — the call on the wire, held at the server
            val releases = AtomicInt(0)
            val gaveUp = AtomicInt(0)
            val server = RecordingStorageServer()
            val held = CompletableDeferred<Unit>()
            server.gate = held
            val supabase =
                newTestSupabaseClient(engine = server.engine) {
                    install(Postgrest)
                    install(Storage) { resumable { cache = FakeResumableCache() } }
                }
            val client =
                newTestOfflineClient(
                    supabase = supabase,
                    onClose = { releases.addAndFetch(1) },
                    coordinatorScope = callerScope,
                    onStillClosing = { gaveUp.addAndFetch(1) },
                )
            val bucket = client.storage("avatars")
            val outcome = callerScope.async { runCatching { call(bucket) } }
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { server.requestArrived.await() }
            }

            // When
            client.close()
            val releasedWhenCloseReturned = releases.load()

            // Then — close() stopped the call rather than waiting for it (it never gave up, and the
            // database was released by the time it returned), and the caller got the closed error
            held.complete(Unit)
            assertEquals(0, gaveUp.load(), "$name: close() gave up waiting for the call")
            val failure =
                withContext(Dispatchers.Default) { withTimeout(5.seconds) { outcome.await() } }
                    .exceptionOrNull()
            assertClientClosedError(failure, "$name on the wire at close()")
            assertEquals(
                1,
                releasedWhenCloseReturned,
                "$name: the database was not released when close() returned",
            )
        }
    }

    @Test
    fun nothing_is_sent_after_close() = runTest {
        // Given — two writes queued offline
        val online = AtomicInt(0)
        val sent = AtomicInt(0)
        val held = CompletableDeferred<Unit>()
        val server =
            RecordingPostgrestServer(
                answer = {
                    if (online.load() == 0) connectivityFailure()
                    sent.addAndFetch(1)
                    held.await()
                    okJson("[]")
                }
            )
        val client =
            newTestOfflineClient(supabase = server.supabaseClient(), coordinatorScope = callerScope)
        val builder = client.from("todos")
        builder.insertJson(buildJsonArray { add(buildJsonObject { put("id", 1) }) })
        builder.insertJson(buildJsonArray { add(buildJsonObject { put("id", 2) }) })
        assertEquals(2, client.pendingMutationCount())

        // When — back online, a replay and an online write are in flight, held at the server, when
        // the client closes; then the server answers
        online.store(1)
        val replay = callerScope.launch { runCatching { client.syncAllPendingMutations() } }
        val write = callerScope.launch {
            runCatching {
                builder.insertJson(buildJsonArray { add(buildJsonObject { put("id", 3) }) })
            }
        }
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { while (sent.load() == 0) delay(5) }
            delay(SETTLE_MILLIS)
        }
        client.close()
        val sentAtClose = sent.load()
        held.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                replay.join()
                write.join()
            }
            delay(SETTLE_MILLIS)
        }

        // Then — nothing reached the server after close(), and the client refuses another replay
        assertEquals(sentAtClose, sent.load(), "a request was sent after close()")
        assertFailsWith<IllegalStateException> { client.syncAllPendingMutations() }
    }

    @Test
    fun a_callers_replay_in_flight_sends_nothing_after_close() = runTest {
        // Given — two writes queued offline; the engine's own passes never run, so the caller's
        // replay is the only sender
        val online = AtomicInt(0)
        val sent = AtomicInt(0)
        val held = CompletableDeferred<Unit>()
        val server =
            RecordingPostgrestServer(
                answer = {
                    if (online.load() == 0) connectivityFailure()
                    sent.addAndFetch(1)
                    held.await()
                    okJson("[]")
                }
            )
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                coordinatorScope = idleCoroutineScope(),
                closeWait = IDLE_CLOSE_WAIT,
            )
        client.from("todos").insertJson(buildJsonArray { add(buildJsonObject { put("id", 1) }) })
        client.from("todos").insertJson(buildJsonArray { add(buildJsonObject { put("id", 2) }) })

        // When — its first request is held at the server when the client closes
        online.store(1)
        val replay = callerScope.async { runCatching { client.syncAllPendingMutations() } }
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { while (sent.load() == 0) delay(5) }
        }
        client.close()
        held.complete(Unit)
        val outcome = withContext(Dispatchers.Default) { withTimeout(5.seconds) { replay.await() } }

        // Then — the replay stopped with the closed error and sent nothing more
        assertClientClosedError(outcome.exceptionOrNull(), "the replay in flight at close()")
        assertEquals(1, sent.load(), "the replay kept sending after close()")
    }

    @Test
    fun close_waits_in_real_time_for_a_call_in_flight_when_called_under_a_test_scheduler() =
        runTest {
            // Given — a write on the wire, which the server answers after a moment of real time
            val releases = AtomicInt(0)
            val arrived = CompletableDeferred<Unit>()
            val held = CompletableDeferred<Unit>()
            val server =
                RecordingPostgrestServer(
                    answer = {
                        arrived.complete(Unit)
                        held.await()
                        okJson("[]")
                    }
                )
            val client =
                newTestOfflineClient(
                    supabase = server.supabaseClient(),
                    onClose = { releases.addAndFetch(1) },
                    coordinatorScope = callerScope,
                )
            val write = callerScope.async {
                runCatching {
                    client
                        .from("todos")
                        .insertJson(buildJsonArray { add(buildJsonObject { put("id", 1) }) })
                }
            }
            withContext(Dispatchers.Default) { withTimeout(5.seconds) { arrived.await() } }
            callerScope.launch {
                delay(SETTLE_MILLIS)
                held.complete(Unit)
            }

            // When — close() is called on the test's scheduler, whose clock skips ahead when idle
            client.close()

            // Then — it returned only after the write was answered and the database was released
            assertEquals(1, releases.load(), "close() returned before the database was released")
            assertIs<MutationOutcome.Applied>(
                write.await().getOrNull(),
                "the write in flight at close() got ${write.await().exceptionOrNull()}",
            )
        }

    @Test
    fun past_its_wait_close_returns_and_the_database_is_released_once_the_last_call_finishes() =
        runTest {
            // Given — a write on the wire that the server holds past close()'s wait
            val releases = AtomicInt(0)
            val logger = RecordingOfflineLogger()
            val arrived = CompletableDeferred<Unit>()
            val held = CompletableDeferred<Unit>()
            val server =
                RecordingPostgrestServer(
                    answer = {
                        arrived.complete(Unit)
                        held.await()
                        okJson("[]")
                    }
                )
            val callsInFlightWhenStillClosing = AtomicInt(-1)
            val client =
                newTestOfflineClient(
                    supabase = server.supabaseClient(),
                    sdkLogger = SdkLogger(logger, OfflineLogLevel.INFO),
                    onClose = { releases.addAndFetch(1) },
                    coordinatorScope = callerScope,
                    closeWait = IDLE_CLOSE_WAIT,
                    onStillClosing = { callsInFlight ->
                        callsInFlightWhenStillClosing.store(callsInFlight())
                    },
                )
            val write = callerScope.async {
                runCatching {
                    client
                        .from("todos")
                        .insertJson(buildJsonArray { add(buildJsonObject { put("id", 1) }) })
                }
            }
            withContext(Dispatchers.Default) { withTimeout(5.seconds) { arrived.await() } }

            // When — close() waits out its bound with the write still held, twice, then the server
            // answers
            client.close()
            client.close()
            val releasedWhenCloseReturned = releases.load()
            held.complete(Unit)
            val outcome =
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        while (releases.load() == 0) delay(5)
                        write.await()
                    }
                }

            // Then — close() left the database open under the write and said so, once, with the
            // call
            // still in flight; the write was answered, and the database was released once, after it
            assertEquals(
                0,
                releasedWhenCloseReturned,
                "close() released the database under a call in flight",
            )
            assertEquals(
                1,
                logger.entriesAt(OfflineLogLevel.WARNING).count { entry ->
                    entry.message.startsWith("close() waited")
                },
            )
            assertEquals(
                1,
                callsInFlightWhenStillClosing.load(),
                "the calls still in flight were not reported",
            )
            assertIs<MutationOutcome.Applied>(
                outcome.getOrNull(),
                "the write in flight at close() got ${outcome.exceptionOrNull()}",
            )
            assertEquals(1, releases.load())
        }

    @Test
    fun every_close_that_returns_before_the_release_leaves_the_database_marked_still_closing() =
        runTest {
            // Which close() reports first is a race, so run it enough times to see both orders
            repeat(MARKING_ORDERINGS) { attempt ->
                // Given — a write on the wire, held past close()'s wait
                val held = CompletableDeferred<Unit>()
                val arrived = CompletableDeferred<Unit>()
                val server =
                    RecordingPostgrestServer(
                        answer = {
                            arrived.complete(Unit)
                            held.await()
                            okJson("[]")
                        }
                    )
                val marked = AtomicInt(0)
                val client =
                    newTestOfflineClient(
                        supabase = server.supabaseClient(),
                        coordinatorScope = callerScope,
                        closeWait = IDLE_CLOSE_WAIT,
                        onStillClosing = { marked.addAndFetch(1) },
                    )
                val write = callerScope.async {
                    runCatching {
                        client
                            .from("todos")
                            .insertJson(buildJsonArray { add(buildJsonObject { put("id", 1) }) })
                    }
                }
                withContext(Dispatchers.Default) { withTimeout(5.seconds) { arrived.await() } }

                // When — two close() calls at once, both past their wait
                val markedWhenEachReturned =
                    withContext(Dispatchers.Default) {
                        withTimeout(5.seconds) {
                            List(2) {
                                    callerScope.async {
                                        client.close()
                                        marked.load()
                                    }
                                }
                                .map { close -> close.await() }
                        }
                    }
                held.complete(Unit)
                withContext(Dispatchers.Default) { withTimeout(5.seconds) { write.await() } }

                // Then — neither returned before the database was marked as still closing
                assertTrue(
                    markedWhenEachReturned.all { count -> count > 0 },
                    "attempt $attempt: a close() returned before the mark ($markedWhenEachReturned)",
                )
            }
        }

    @Test
    fun a_flow_made_after_close_reports_the_closed_error_through_its_own_error_channel() = runTest {
        // Given — a closed client, and a builder obtained before it closed
        val client =
            newTestOfflineClient(
                supabase = RecordingPostgrestServer().supabaseClient(),
                coordinatorScope = callerScope,
            )
        val builder = client.from("todos")
        client.close()
        val flows: List<Pair<String, () -> Flow<*>>> =
            listOf<Pair<String, () -> Flow<*>>>(
                "pendingMutationCountFlow()" to { client.pendingMutationCountFlow() },
                "pendingMutationCountFlow(table)" to { client.pendingMutationCountFlow("todos") },
            ) +
                FlowReadStrategy.entries.map { strategy ->
                    "selectAsFlow($strategy)" to { builder.selectAsFlow(readStrategy = strategy) }
                }

        for ((name, flowOf) in flows) {
            // When — the flow is made and collected with an operator that catches its failure
            val caught = mutableListOf<Throwable>()
            val escaped =
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        runCatching { flowOf().catch { failure -> caught += failure }.collect {} }
                            .exceptionOrNull()
                    }
                }

            // Then — catch saw the closed error, and nothing was thrown past it
            assertEquals(null, escaped, "$name threw past catch: $escaped")
            assertEquals(1, caught.size, "$name: catch saw $caught")
            assertClientClosedError(caught.single(), "$name made after close()")
        }
    }

    @Test
    fun a_closed_client_refuses_every_call() = runTest {
        // Given
        val client = newTestOfflineClient(coordinatorScope = callerScope)

        // When
        client.close()

        // Then
        assertFailsWith<IllegalStateException> { client.from("todos") }
        assertFailsWith<IllegalStateException> { client.storage("avatars") }
        assertFailsWith<IllegalStateException> { client.syncAllPendingMutations() }
        assertFailsWith<IllegalStateException> { client.syncPendingMutations("todos") }
        assertFailsWith<IllegalStateException> { client.pendingMutationCount() }
        assertFailsWith<IllegalStateException> { client.pendingMutationCount("todos") }
        assertFailsWith<IllegalStateException> { client.pendingMutationCountFlow().first() }
        assertFailsWith<IllegalStateException> { client.pendingMutationCountFlow("todos").first() }
        assertFailsWith<IllegalStateException> {
            client.applyServerRecord("todos", buildJsonObject { put("id", 1) })
        }
        assertFailsWith<IllegalStateException> {
            client.applyServerDelete("todos", buildJsonObject { put("id", 1) })
        }
        assertFailsWith<IllegalStateException> { client.clearAllCache() }
    }

    private companion object {
        /** Real time given to anything in flight to reach the server. */
        const val SETTLE_MILLIS = 200L

        const val STUCK_IN_ITS_BODY = "a collector stuck in its body"
        const val NEVER_READ_AGAIN = "a produceIn channel never read again"
        const val NEVER_READ_AGAIN_RENDEZVOUS = "a rendezvous produceIn channel never read again"
        const val MARKING_ORDERINGS = 20

        /** An idle client's work can never finish, so its `close()` always waits this long. */
        val IDLE_CLOSE_WAIT: Duration = 100.milliseconds
    }
}
