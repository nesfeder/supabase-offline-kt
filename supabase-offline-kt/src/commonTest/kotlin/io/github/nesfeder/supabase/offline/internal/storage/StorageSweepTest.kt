package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The sweep for temporary files a crash left behind removes only files a day old, and never a file
 * a cache row names: a fresh one may be another cache's download still in flight, and an object may
 * be named like a temporary file.
 */
class StorageSweepTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: OfflineStore

    @BeforeTest
    fun setUp() {
        dataDirectory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-sweep-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(dataDirectory)
        store = newTestOfflineStore()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    @Test
    fun an_object_named_like_a_temporary_file_survives_the_next_process() = runTest {
        // Given — cached two days ago under a name a crash leftover could have, and not used since
        val name = ".supabase-offline-download-1234.tmp"
        val delegate = FakeBucketApi(bucketId = BUCKET)
        delegate.onDownload = { "REAL".encodeToByteArray() }
        newTestBucketApi(delegate = delegate, baseDirectory = dataDirectory, store = store).downloadAuthenticated(name) { }
        markFileUsed(dataDirectory / "storage" / BUCKET / name, Clock.System.now().toEpochMilliseconds() - 2L * DAY_MILLIS)

        // When — the next process's first write sweeps crash leftovers
        val next = newTestBucketApi(delegate = delegate, baseDirectory = dataDirectory, store = store)
        next.downloadAuthenticated("other.txt") { }

        // Then
        assertNotNull(next.cachedFile(name), "a real object was swept as a crash leftover")
    }

    @Test
    fun a_second_cache_on_the_same_directory_never_sweeps_a_download_in_flight() = runTest {
        // Given — cache A's channel download is part-way through, its temporary file open
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = FakeBucketApi(bucketId = BUCKET)
        slow.channelChunkSize = 4
        slow.onDownload = { "ABCDEFGHIJKL".encodeToByteArray() }
        slow.onChannelChunk = { written ->
            if (written == 8) {
                parked.complete(Unit)
                release.await()
            }
        }
        val first = newTestBucketApi(delegate = slow, baseDirectory = dataDirectory, store = store)
        val (temporariesBefore, temporariesAfter, delivered) = withContext(Dispatchers.Default) {
            val channel = ByteChannel()
            val reader = async { channel.toByteArray() }
            val download = async { first.downloadAuthenticated("a.txt", channel) { } }
            withTimeout(5.seconds) { parked.await() }
            withTimeout(5.seconds) { while (temporaryFiles().isEmpty()) delay(5) }
            val before = temporaryFiles()

            // When — cache B, on the same directory (a client re-created after close()), makes its
            // first write, which sweeps crash leftovers
            val fast = FakeBucketApi(bucketId = BUCKET)
            fast.onDownload = { "B".encodeToByteArray() }
            newTestBucketApi(delegate = fast, baseDirectory = dataDirectory, store = store).downloadAuthenticated("b.txt") { }
            val after = before.filter { FileSystem.SYSTEM.exists(it) }
            release.complete(Unit)
            withTimeout(5.seconds) { download.await() }
            Triple(before, after, withTimeout(5.seconds) { reader.await() }.decodeToString())
        }

        // Then — A's temporary file survived B's sweep, and A's download completed
        assertFalse(temporariesBefore.isEmpty())
        assertEquals(temporariesBefore, temporariesAfter, "B's sweep deleted A's download in flight")
        assertEquals("ABCDEFGHIJKL", delivered)
    }

    private fun temporaryFiles(): List<Path> =
        FileSystem.SYSTEM.listRecursively(dataDirectory).filter { it.name.endsWith(".tmp") }.toList()

    private companion object {
        const val BUCKET = "media"
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
