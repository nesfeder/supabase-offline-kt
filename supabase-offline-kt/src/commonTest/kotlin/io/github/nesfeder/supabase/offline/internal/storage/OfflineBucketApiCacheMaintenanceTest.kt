package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How [CachingBucketApi] keeps its disk cache in step with the bucket operations it forwards:
 * `delete` removes the cached file and its row, `move` carries both across, `copy` leaves the cache
 * alone, and `cachedFile` answers only for a tracked file that is still on disk.
 *
 * Runs against the real okio [FileSystem.SYSTEM] in a per-test temporary directory, over the
 * in-memory offline-store fakes.
 */
class OfflineBucketApiCacheMaintenanceTest {

    private lateinit var baseDir: Path
    private lateinit var store: OfflineStore
    private lateinit var delegate: FakeBucketApi
    private lateinit var api: CachingBucketApi

    @BeforeTest
    fun setUp() {
        baseDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-bucket-maintenance-${Random.nextLong()}"
        store = newTestOfflineStore()
        delegate = FakeBucketApi(bucketId = BUCKET)
        api = newTestBucketApi(delegate = delegate, baseDirectory = baseDir, store = store)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(baseDir, mustExist = false)
    }

    /** Downloads [path] once through the wrapper so its file and cache row exist. */
    private suspend fun primeCache(path: String, bytes: ByteArray) {
        delegate.onDownload = { bytes }
        api.downloadPublic(path) { }
    }

    @Test
    fun delete_removesCachedFileAndStorageRow() = runTest {
        // Given
        val path = "foo_delete.jpg"
        primeCache(path, "cached-delete".encodeToByteArray())
        val rowBefore = store.getStorageByKey(BUCKET, path)
        assertNotNull(rowBefore)
        val localPathBefore = rowBefore.localPath.toPath()
        assertTrue(FileSystem.SYSTEM.exists(localPathBefore))

        // When
        api.delete(listOf(path))

        // Then — forwarded, and both the row and the file are gone
        assertEquals(listOf<Collection<String>>(listOf(path)), delegate.deletedPaths)
        assertNull(store.getStorageByKey(BUCKET, path))
        assertFalse(FileSystem.SYSTEM.exists(localPathBefore))
    }

    @Test
    fun move_movesFileAndUpdatesStorageRows() = runTest {
        // Given
        val from = "foo_move_src.jpg"
        val to = "foo_move_dst.jpg"
        primeCache(from, "move-me".encodeToByteArray())
        val srcRow = store.getStorageByKey(BUCKET, from)
        assertNotNull(srcRow)
        val srcPath = srcRow.localPath.toPath()
        assertTrue(FileSystem.SYSTEM.exists(srcPath))

        // When
        api.move(from = from, to = to, destinationBucket = null)

        // Then — forwarded; the old row is removed, the new row points at the moved file
        assertEquals(listOf(Triple<String, String, String?>(from, to, null)), delegate.moves)
        assertNull(store.getStorageByKey(BUCKET, from))
        val dstRow = store.getStorageByKey(BUCKET, to)
        assertNotNull(dstRow)
        assertTrue(FileSystem.SYSTEM.exists(dstRow.localPath.toPath()))
        assertFalse(FileSystem.SYSTEM.exists(srcPath))
    }

    @Test
    fun move_whenSourceFileMissing_doesNothing() = runTest {
        // Given — nothing cached, so the source file does not exist
        val from = "foo_move_missing_src.jpg"
        val to = "foo_move_missing_dst.jpg"
        assertNull(store.getStorageByKey(BUCKET, from))

        // When
        api.move(from = from, to = to, destinationBucket = null)

        // Then — forwarded, but no cache rows appear
        assertEquals(listOf(Triple<String, String, String?>(from, to, null)), delegate.moves)
        assertNull(store.getStorageByKey(BUCKET, from))
        assertNull(store.getStorageByKey(BUCKET, to))
    }

    @Test
    fun copy_doesNotPopulateCache() = runTest {
        // Given
        val from = "foo_copy_src.jpg"
        val to = "foo_copy_dst.jpg"

        // When — the wrapper deliberately leaves the cache alone on copy
        api.copy(from = from, to = to, destinationBucket = null)

        // Then
        assertEquals(listOf(Triple<String, String, String?>(from, to, null)), delegate.copies)
        assertNull(store.getStorageByKey(BUCKET, from))
        assertNull(store.getStorageByKey(BUCKET, to))
    }

    @Test
    fun cachedFile_checksStoreAndDisk() = runTest {
        // Given
        val path = "foo_localPath.jpg"
        primeCache(path, "cached-local-path".encodeToByteArray())

        // When
        val cached = api.cachedFile(path)

        // Then
        assertNotNull(cached)
        assertTrue(FileSystem.SYSTEM.exists(cached.path.toPath()))
    }

    @Test
    fun cachedFile_returnsNullWhenNotTracked() = runTest {
        // Given — nothing cached

        // When
        val cached = api.cachedFile("non-existent.jpg")

        // Then
        assertNull(cached)
    }

    private companion object {
        const val BUCKET = "test-bucket"
    }
}
