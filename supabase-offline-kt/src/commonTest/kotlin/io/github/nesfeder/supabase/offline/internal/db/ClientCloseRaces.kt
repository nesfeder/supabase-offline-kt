@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.SupabaseOfflineClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig
import io.github.nesfeder.supabase.offline.internal.postgrest.CLIENT_CLOSED_MESSAGE
import io.github.nesfeder.supabase.offline.testdoubles.RecordingBackendServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The platform factory, as the scenarios call it. */
internal typealias OpenOfflineClient = (SupabaseClient, SupabaseOfflineConfig) -> SupabaseOfflineClient

/**
 * `close()` racing the calls it must not close the database under, over a real database opened by a
 * platform factory. Both platform test classes run these; only opening the client differs.
 */
internal object ClientCloseRaces {

    private const val TABLE = "profiles"
    private const val BUCKET = "avatars"

    /**
     * Races a clear against `close()` [iterations] times, each over a fresh database holding cached
     * rows, a downloaded file and a queued write. Every clear either completes, leaving nothing of the
     * user, or is refused because `close()` came first; `close()` never fails, and the database always
     * reopens.
     *
     * @return how many clears completed and how many were refused.
     */
    suspend fun aClearRacingClose(directory: Path, openClient: OpenOfflineClient, iterations: Int): Pair<Int, Int> = onRealThreads {
        var completed = 0
        var refused = 0
        repeat(iterations) { iteration ->
            val databaseDirectory = directory / "race-$iteration"
            val server = RecordingBackendServer(marker = "MARKER${Random.nextLong().toULong().toString(16)}")
            val logger = RecordingOfflineLogger()
            val client = openClient(server.supabaseClient(), configIn(databaseDirectory, logger))
            fill(client, server)

            // Either call may start first, by up to a few milliseconds: about as long as a clear takes
            val lead = Random.nextLong(0, 8)
            val clearStartsFirst = iteration % 2 == 0
            val clear = async {
                if (!clearStartsFirst) delay(lead)
                runCatching { client.clearAllCache() }
            }
            val close = async {
                if (clearStartsFirst) delay(lead)
                runCatching { client.close() }
            }
            val cleared = clear.await()
            val closed = close.await()

            assertTrue(closed.isSuccess, "iteration $iteration: close() failed: ${closed.exceptionOrNull()}")
            val failure = cleared.exceptionOrNull()
            if (failure == null) {
                completed++
                assertEquals(emptyList(), markerLocations(databaseDirectory, server.marker), "iteration $iteration: a completed clear left data")
            } else {
                refused++
                assertTrue(failure !is CancellationException, "iteration $iteration: an uncancelled caller got $failure")
                assertIs<IllegalStateException>(failure, "iteration $iteration: the clear failed with ${failure::class.simpleName}: ${failure.message}")
                assertEquals(CLIENT_CLOSED_MESSAGE, failure.message)
            }
            val reopened = openClient(server.supabaseClient(), configIn(databaseDirectory))
            val pending = reopened.pendingMutationCount()
            reopened.close()
            if (failure == null) assertEquals(0, pending, "iteration $iteration: a completed clear left a queued write")
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
    suspend fun aWriteInFlightWhenCloseRuns(directory: Path, openClient: OpenOfflineClient) = onRealThreads {
        val server = RecordingBackendServer(marker = "MARKER${Random.nextLong().toULong().toString(16)}")
        val logger = RecordingOfflineLogger()
        val client = openClient(server.supabaseClient(), configIn(directory, logger))
        val held = CompletableDeferred<Unit>()
        server.restHold.value = held
        val write = async { runCatching { client.from(TABLE).insertJson(keylessRow(server.marker)) } }
        server.restArrived.first { requests -> requests > 0 }

        val close = async { runCatching { client.close() } }
        awaitRefusingCalls(client)
        held.complete(Unit)
        val written = write.await()
        val closed = close.await()

        assertIs<MutationOutcome.Applied>(written.getOrNull(), "the write in flight got ${written.exceptionOrNull()}")
        assertTrue(closed.isSuccess, "close() failed: ${closed.exceptionOrNull()}")
        val reopened = openClient(server.supabaseClient(), configIn(directory))
        assertEquals(0, reopened.pendingMutationCount(), "the accepted write stayed queued")
        reopened.syncAllPendingMutations()
        reopened.close()
        assertEquals(1, server.restWrites.load(), "the accepted write was sent again")
        assertEquals(emptyList(), errorsIn(logger), "errors were logged")
    }

    /**
     * A bucket and a count flow obtained before `close()` refuse afterwards with the client's closed
     * error, and nothing reaches the server.
     */
    suspend fun objectsObtainedBeforeCloseRefuseAfterIt(directory: Path, openClient: OpenOfflineClient) = onRealThreads {
        val server = RecordingBackendServer(marker = "MARKER${Random.nextLong().toULong().toString(16)}")
        val client = openClient(server.supabaseClient(), configIn(directory))
        fill(client, server)
        server.online.store(true)
        val bucket = client.storage(BUCKET)
        val pendingCount = client.pendingMutationCountFlow()
        val downloadsBefore = server.storageArrived.value

        client.close()

        listOf(
            runCatching { bucket.downloadAuthenticated("u-1/me.png") { } },
            runCatching { bucket.cachedFile("u-1/me.png") },
            runCatching { bucket.ensureCachedFile("u-1/other.png") },
            runCatching { bucket.upload("u-1/new.png", byteArrayOf(1)) },
            runCatching { pendingCount.first() },
        ).forEachIndexed { index, outcome ->
            val failure = outcome.exceptionOrNull()
            assertTrue(failure !is CancellationException, "call $index after close() gave $failure")
            assertIs<IllegalStateException>(failure, "call $index after close() gave $failure")
            assertEquals(CLIENT_CLOSED_MESSAGE, failure.message)
        }
        assertEquals(downloadsBefore, server.storageArrived.value, "a download was sent after close()")
    }

    /** Cached rows, a downloaded file and a write queued offline, each carrying the server's marker. */
    private suspend fun fill(client: SupabaseOfflineClient, server: RecordingBackendServer) {
        server.online.store(true)
        repeat(10) { index -> client.from(TABLE).select { filter { eq("id", index) } } }
        client.storage(BUCKET).downloadAuthenticated("u-1/me.png") { }
        server.online.store(false)
        client.from(TABLE).insertJson(keylessRow(server.marker))
    }

    /** Waits, bounded, until [client] refuses new calls: `close()` has begun. */
    private suspend fun awaitRefusingCalls(client: SupabaseOfflineClient) {
        withTimeout(5.seconds) {
            while (runCatching { client.from(TABLE) }.isSuccess) delay(1)
        }
    }

    private fun keylessRow(marker: String): JsonArray =
        buildJsonArray { add(buildJsonObject { put("email", "$marker@example.com") }) }

    private fun configIn(directory: Path, logger: RecordingOfflineLogger? = null) =
        SupabaseOfflineConfig(offlineStoragePath = directory, logger = logger)

    /** What [logger] recorded at ERROR: a background failure the engine contained would show here. */
    private fun errorsIn(logger: RecordingOfflineLogger): List<String> =
        logger.entriesAt(OfflineLogLevel.ERROR).map { entry -> entry.message }

    /** Where [marker] can still be read: the database files and the cached files. */
    private fun markerLocations(directory: Path, marker: String): List<String> {
        val databaseFiles = listOf("", "-wal", "-shm").map { suffix -> "${directory / OFFLINE_DATABASE_FILE_NAME}$suffix".toPath() }
        val cachedFiles = mutableListOf<Path>()
        for (cacheDirectory in listOf(directory / "storage", directory / "storage-hashed")) {
            if (!FileSystem.SYSTEM.exists(cacheDirectory)) continue
            FileSystem.SYSTEM.listRecursively(cacheDirectory)
                .filter { file -> FileSystem.SYSTEM.metadataOrNull(file)?.isRegularFile == true }
                .forEach { file -> cachedFiles += file }
        }
        return (databaseFiles + cachedFiles).filter { file -> containsMarker(file, marker) }.map { it.name }
    }

    private fun containsMarker(file: Path, marker: String): Boolean {
        if (!FileSystem.SYSTEM.exists(file)) return false
        return FileSystem.SYSTEM.read(file) { readByteString() }.indexOf(marker.encodeUtf8()) >= 0
    }

    /** Runs [block] on real threads, where the client's work runs, bounded well past its need. */
    private suspend fun <T> onRealThreads(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.Default) { withTimeout(10.minutes) { block() } }
}
