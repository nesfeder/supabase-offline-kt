// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.postgrest.CLIENT_CLOSED_MESSAGE
import io.github.nesfeder.supabase.offline.testdoubles.RecordingBackendServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.assertClientClosedError
import io.github.nesfeder.supabase.offline.testdoubles.everyFlowReadStrategy
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/** The platform's open path, as the scenarios call it. */
internal typealias OpenOfflineClient = (SupabaseClient, Offline.Config) -> Offline

/**
 * `close()` racing the calls it must not close the database under, over a real database opened by
 * the platform's open path. Both platform test classes run these; only opening the client differs.
 */
internal object ClientCloseRaces {

    private const val TABLE = "profiles"
    private const val BUCKET = "avatars"

    /**
     * Races a clear against `close()` [iterations] times, each over a fresh database holding cached
     * rows, a downloaded file and a queued write. Every clear either completes, leaving nothing of
     * the user, or is refused because `close()` came first; `close()` never fails, and the database
     * always reopens.
     *
     * @return how many clears completed and how many were refused.
     */
    suspend fun aClearRacingClose(
        directory: Path,
        openClient: OpenOfflineClient,
        iterations: Int,
    ): Pair<Int, Int> = onRealThreads {
        var completed = 0
        var refused = 0
        repeat(iterations) { iteration ->
            // Given — a fresh database holding cached rows, a downloaded file and a queued write
            val databaseDirectory = directory / "race-$iteration"
            val server =
                RecordingBackendServer(marker = "MARKER${Random.nextLong().toULong().toString(16)}")
            val logger = RecordingLogSink()
            val client =
                openClient(server.supabaseClient(logs = logger), configIn(databaseDirectory))
            fill(client, server)

            // When — a clear and close() race; either may start first, by up to a few milliseconds,
            // about as long as a clear takes
            val lead = Random.nextLong(0, 8)
            val clearStartsFirst = iteration % 2 == 0
            val clear = async {
                if (!clearStartsFirst) delay(lead)
                runCatching { client.clearLocalData() }
            }
            val close = async {
                if (clearStartsFirst) delay(lead)
                runCatching { client.close() }
            }
            val cleared = clear.await()
            val closed = close.await()

            // Then — close() succeeded; the clear completed, leaving nothing, or was refused with
            // the
            // closed error; the database reopens with nothing queued after a completed clear
            assertTrue(
                closed.isSuccess,
                "iteration $iteration: close() failed: ${closed.exceptionOrNull()}",
            )
            val failure = cleared.exceptionOrNull()
            if (failure == null) {
                completed++
                assertEquals(
                    emptyList(),
                    markerLocations(databaseDirectory, server.marker),
                    "iteration $iteration: a completed clear left data",
                )
            } else {
                refused++
                assertTrue(
                    failure !is CancellationException,
                    "iteration $iteration: an uncanceled caller got $failure",
                )
                assertIs<IllegalStateException>(
                    failure,
                    "iteration $iteration: the clear failed with ${failure::class.simpleName}: ${failure.message}",
                )
                assertEquals(CLIENT_CLOSED_MESSAGE, failure.message)
            }
            val reopened = openClient(server.supabaseClient(), configIn(databaseDirectory))
            val pending = reopened.pendingMutationCount()
            reopened.close()
            if (failure == null)
                assertEquals(
                    0,
                    pending,
                    "iteration $iteration: a completed clear left a queued write",
                )
            assertEquals(emptyList(), errorsIn(logger), "iteration $iteration: errors were logged")
            FileSystem.SYSTEM.deleteRecursively(databaseDirectory, mustExist = false)
        }
        assertEquals(iterations, completed + refused)
        completed to refused
    }

    /**
     * An online write on the wire when `close()` runs: `close()` waits for it, the caller gets the
     * server's answer, and a client reopened on the same database sends nothing again.
     */
    suspend fun aWriteInFlightWhenCloseRuns(directory: Path, openClient: OpenOfflineClient) =
        onRealThreads {
            // Given — an online write on the wire, held at the server
            val server =
                RecordingBackendServer(marker = "MARKER${Random.nextLong().toULong().toString(16)}")
            val logger = RecordingLogSink()
            val client = openClient(server.supabaseClient(logs = logger), configIn(directory))
            val held = CompletableDeferred<Unit>()
            server.restHold.value = held
            val write = async {
                runCatching { client.from(TABLE).insert(keylessRow(server.marker)) }
            }
            server.restArrived.first { requests -> requests > 0 }

            // When — close() runs, and then the server accepts the write
            val close = async { runCatching { client.close() } }
            awaitRefusingCalls(client)
            held.complete(Unit)
            val written = write.await()
            val closed = close.await()

            // Then — the caller got the server's answer, and a reopened client sends nothing again
            assertIs<MutationOutcome.Applied>(
                written.getOrNull(),
                "the write in flight got ${written.exceptionOrNull()}",
            )
            assertTrue(closed.isSuccess, "close() failed: ${closed.exceptionOrNull()}")
            val reopened = openClient(server.supabaseClient(), configIn(directory))
            assertEquals(0, reopened.pendingMutationCount(), "the accepted write stayed queued")
            reopened.syncPendingMutations().syncedCount
            reopened.close()
            assertEquals(1, server.restWrites.load(), "the accepted write was sent again")
            assertEquals(emptyList(), errorsIn(logger), "errors were logged")
        }

    /**
     * A bucket and a count flow obtained before `close()` refuse afterward with the client's closed
     * error, and nothing reaches the server.
     */
    suspend fun objectsObtainedBeforeCloseRefuseAfterIt(
        directory: Path,
        openClient: OpenOfflineClient,
    ) = onRealThreads {
        // Given — a bucket and a count flow obtained before close()
        val server =
            RecordingBackendServer(marker = "MARKER${Random.nextLong().toULong().toString(16)}")
        val client = openClient(server.supabaseClient(), configIn(directory))
        fill(client, server)
        server.online.store(true)
        val bucket = client.storage.from(BUCKET)
        val pendingCount = client.pendingMutationCountFlow()
        val downloadsBefore = server.storageArrived.value

        // When
        client.close()

        // Then — each call gets the closed error, never a cancellation, and no download is sent
        listOf(
                runCatching { bucket.downloadAuthenticated("u-1/me.png") {} },
                runCatching { bucket.cachedFile("u-1/me.png") },
                runCatching { bucket.ensureCachedFile("u-1/other.png") },
                runCatching { bucket.upload("u-1/new.png", byteArrayOf(1)) },
                runCatching { pendingCount.first() },
            )
            .forEachIndexed { index, outcome ->
                val failure = outcome.exceptionOrNull()
                assertTrue(
                    failure !is CancellationException,
                    "call $index after close() gave $failure",
                )
                assertIs<IllegalStateException>(failure, "call $index after close() gave $failure")
                assertEquals(CLIENT_CLOSED_MESSAGE, failure.message)
            }
        assertEquals(
            downloadsBefore,
            server.storageArrived.value,
            "a download was sent after close()",
        )
    }

    /**
     * Three reactive reads of each strategy that reads the cache in its collector's coroutine
     * (`CacheOnly`, `NetworkFirstThenCache`) and a writer re-running them, with `close()` at a
     * random moment, [iterations] times. Every read completes and the writer ends with the closed
     * error, never a cancellation; nothing reaches the server once `close()` has returned, the
     * database reopens at once, and nothing is logged at ERROR.
     */
    suspend fun readersRacingClose(
        directory: Path,
        openClient: OpenOfflineClient,
        iterations: Int,
    ) = onRealThreads {
        repeat(iterations) { iteration ->
            // Given — six live reads and a writer, online
            val databaseDirectory = directory / "readers-$iteration"
            val server =
                RecordingBackendServer(marker = "MARKER${Random.nextLong().toULong().toString(16)}")
            val logger = RecordingLogSink()
            val client =
                openClient(server.supabaseClient(logs = logger), configIn(databaseDirectory))
            fill(client, server)
            server.online.store(true)
            val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val strategies =
                listOf(FlowReadStrategy.CacheOnly, FlowReadStrategy.NetworkFirstThenCache)
            // Each reader's own first value, so every one is live, not still starting, at close()
            val firstValues =
                List(strategies.size * READERS_PER_STRATEGY) { CompletableDeferred<Unit>() }
            val readers = strategies.flatMapIndexed { strategyIndex, strategy ->
                (0 until READERS_PER_STRATEGY).map { index ->
                    val firstValue = firstValues[strategyIndex * READERS_PER_STRATEGY + index]
                    app.async {
                        val outcome = runCatching {
                            client
                                .from(TABLE)
                                .selectAsFlow(readStrategy = strategy) {
                                    filter { eq("id", index) }
                                }
                                .collect { firstValue.complete(Unit) }
                        }
                        outcome.exceptionOrNull()
                    }
                }
            }
            val writer = app.async {
                val outcome = runCatching {
                    var index = 0
                    while (true) {
                        client.from(TABLE).insert(keylessRow("${server.marker}-${index++}"))
                        yield()
                    }
                }
                outcome.exceptionOrNull()
            }
            withTimeout(5.seconds) { firstValues.awaitAll() }

            // When — close() at a random moment
            delay(Random.nextLong(0, 8))
            val closed = runCatching { client.close() }
            val requestsAtClose = server.requestsArrived()

            // Then
            assertTrue(
                closed.isSuccess,
                "iteration $iteration: close() failed: ${closed.exceptionOrNull()}",
            )
            val readerEnds = withTimeout(5.seconds) { readers.awaitAll() }
            readerEnds.forEachIndexed { index, failure ->
                assertNull(failure, "iteration $iteration: reader $index ended with $failure")
            }
            assertClientClosedError(
                withTimeout(5.seconds) { writer.await() },
                "iteration $iteration: the writer",
            )
            assertEquals(
                requestsAtClose,
                server.requestsArrived(),
                "iteration $iteration: a request reached the server after close()",
            )
            openClient(server.supabaseClient(), configIn(databaseDirectory)).close()
            assertEquals(emptyList(), errorsIn(logger), "iteration $iteration: errors were logged")
            app.cancel()
            FileSystem.SYSTEM.deleteRecursively(databaseDirectory, mustExist = false)
        }
    }

    /**
     * The logout sequence an app runs, [iterations] times across every read strategy: a screen
     * still observing a read, then `clearLocalData()` and `close()` back to back. The clear re-runs
     * the live read; the read completes, nothing of the user is left, and the database reopens at
     * once.
     */
    suspend fun logoutWithALiveRead(
        directory: Path,
        openClient: OpenOfflineClient,
        iterations: Int,
    ) = onRealThreads {
        repeat(iterations) { iteration ->
            // Given — a screen observing a read of one strategy
            val strategy = everyFlowReadStrategy[iteration % everyFlowReadStrategy.size]
            val databaseDirectory = directory / "logout-$iteration"
            val server =
                RecordingBackendServer(marker = "MARKER${Random.nextLong().toULong().toString(16)}")
            val logger = RecordingLogSink()
            val client =
                openClient(server.supabaseClient(logs = logger), configIn(databaseDirectory))
            fill(client, server)
            // A network-first read needs the network to start; once it has, nothing reaches the
            // server
            server.online.store(strategy == FlowReadStrategy.NetworkFirstThenCache)
            val screen = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val emitted = CompletableDeferred<Unit>()
            val reader = screen.async {
                val outcome = runCatching {
                    client.from(TABLE).selectAsFlow(readStrategy = strategy).collect {
                        emitted.complete(Unit)
                    }
                }
                outcome.exceptionOrNull()
            }
            withTimeout(5.seconds) { emitted.await() }
            server.online.store(false)

            // When — the app logs out
            client.clearLocalData()
            client.close()

            // Then
            val readerEnd = withTimeout(5.seconds) { reader.await() }
            assertNull(
                readerEnd,
                "iteration $iteration: the $strategy read live at logout ended with $readerEnd",
            )
            assertEquals(
                emptyList(),
                markerLocations(databaseDirectory, server.marker),
                "iteration $iteration: the user's data was left behind",
            )
            openClient(server.supabaseClient(), configIn(databaseDirectory)).close()
            assertEquals(emptyList(), errorsIn(logger), "iteration $iteration: errors were logged")
            screen.cancel()
            FileSystem.SYSTEM.deleteRecursively(databaseDirectory, mustExist = false)
        }
    }

    /**
     * Cached rows, a downloaded file and a write queued offline, each carrying the server's marker.
     */
    private suspend fun fill(client: Offline, server: RecordingBackendServer) {
        server.online.store(true)
        repeat(10) { index -> client.from(TABLE).select { filter { eq("id", index) } } }
        client.storage.from(BUCKET).downloadAuthenticated("u-1/me.png") {}
        server.online.store(false)
        client.from(TABLE).insert(keylessRow(server.marker))
    }

    /** Waits, bounded, until [client] refuses new calls: `close()` has begun. */
    private suspend fun awaitRefusingCalls(client: Offline) {
        withTimeout(5.seconds) {
            while (runCatching { client.from(TABLE) }.isSuccess) delay(1)
        }
    }

    private const val READERS_PER_STRATEGY = 3

    private fun keylessRow(marker: String): JsonArray = buildJsonArray {
        add(buildJsonObject { put("email", "$marker@example.com") })
    }

    private fun configIn(directory: Path): Offline.Config = Offline.createConfig {
        dataDirectory = directory.toString()
    }

    /**
     * What [logger] recorded at ERROR: a background failure the engine contained would show here.
     */
    private fun errorsIn(logger: RecordingLogSink): List<String> =
        logger.entriesAt(EngineLogLevel.ERROR).map { entry -> entry.message }

    /** Where [marker] can still be read: the database files and the cached files. */
    private fun markerLocations(directory: Path, marker: String): List<String> {
        val databaseFiles =
            listOf("", "-wal", "-shm").map { suffix ->
                "${directory / OFFLINE_DATABASE_FILE_NAME}$suffix".toPath()
            }
        val cachedFiles = mutableListOf<Path>()
        for (cacheDirectory in listOf(directory / "storage", directory / "storage-hashed")) {
            if (!FileSystem.SYSTEM.exists(cacheDirectory)) continue
            FileSystem.SYSTEM.listRecursively(cacheDirectory)
                .filter { file -> FileSystem.SYSTEM.metadataOrNull(file)?.isRegularFile == true }
                .forEach { file -> cachedFiles += file }
        }
        return (databaseFiles + cachedFiles)
            .filter { file -> containsMarker(file, marker) }
            .map { it.name }
    }

    private fun containsMarker(file: Path, marker: String): Boolean {
        if (!FileSystem.SYSTEM.exists(file)) return false
        return FileSystem.SYSTEM.read(file) { readByteString() }.indexOf(marker.encodeUtf8()) >= 0
    }

    /** Runs [block] on real threads, where the client's work runs, bounded well past its need. */
    private suspend fun <T> onRealThreads(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.Default) { withTimeout(10.minutes) { block() } }
}
