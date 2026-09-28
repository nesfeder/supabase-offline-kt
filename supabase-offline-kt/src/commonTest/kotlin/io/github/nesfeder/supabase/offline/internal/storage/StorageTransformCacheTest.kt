package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
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
import kotlin.test.assertNotNull

/**
 * An image transform changes the bytes a download returns, so a transformed download and the
 * original are separate cache entries: a list's thumbnail never replaces the profile screen's full
 * image on disk, and each is served back offline as it was fetched.
 *
 * These run the real supabase-kt bucket against [RecordingStorageServer], because only the real
 * bucket turns a transform into a different request.
 */
class StorageTransformCacheTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: OfflineStore
    private lateinit var server: RecordingStorageServer

    @BeforeTest
    fun setUp() {
        dataDirectory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-transform-${Random.nextLong().toULong()}"
        store = newTestOfflineStore()
        server = RecordingStorageServer()
        server.objects["$BUCKET/$PATH"] = ORIGINAL.encodeToByteArray()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun bucket() = newTestBucketApi(
        delegate = server.supabaseClient().storage.from(BUCKET),
        baseDirectory = dataDirectory,
        store = store,
    )

    @Test
    fun a_thumbnail_and_the_full_image_are_cached_separately() = runTest {
        // Given — the list fetches a thumbnail, the profile screen the full image
        val bucket = bucket()
        val thumbnail = bucket.downloadAuthenticated(PATH) { transform { size(64, 64) } }.decodeToString()
        val full = bucket.downloadAuthenticated(PATH) { }.decodeToString()

        // When — both are read again offline
        server.online = false
        val offlineThumbnail = bucket.downloadAuthenticated(PATH) { transform { size(64, 64) } }.decodeToString()
        val offlineFull = bucket.downloadAuthenticated(PATH) { }.decodeToString()

        // Then — each read gets its own variant back
        assertEquals("render?height=64&width=64:$ORIGINAL", thumbnail)
        assertEquals(ORIGINAL, full)
        assertEquals(thumbnail, offlineThumbnail)
        assertEquals(full, offlineFull)
    }

    @Test
    fun the_full_image_fetched_first_is_not_replaced_by_a_later_thumbnail() = runTest {
        // Given
        val bucket = bucket()
        bucket.downloadPublic(PATH) { }
        bucket.downloadPublic(PATH) { transform { size(32, 32) } }

        // When
        server.online = false
        val offlineFull = bucket.downloadPublic(PATH) { }.decodeToString()

        // Then
        assertEquals(ORIGINAL, offlineFull)
    }

    @Test
    fun two_transforms_of_one_object_are_cached_separately() = runTest {
        // Given
        val bucket = bucket()
        val small = bucket.downloadAuthenticated(PATH) { transform { size(32, 32) } }.decodeToString()
        val large = bucket.downloadAuthenticated(PATH) { transform { size(256, 256) } }.decodeToString()

        // When
        server.online = false

        // Then
        assertEquals(small, bucket.downloadAuthenticated(PATH) { transform { size(32, 32) } }.decodeToString())
        assertEquals(large, bucket.downloadAuthenticated(PATH) { transform { size(256, 256) } }.decodeToString())
    }

    @Test
    fun an_untransformed_object_keeps_its_cache_key_and_file_location() = runTest {
        // Given — a thumbnail cached beside it must not disturb the original's entry
        val bucket = bucket()

        // When
        bucket.downloadAuthenticated(PATH) { transform { size(64, 64) } }
        bucket.downloadAuthenticated(PATH) { }

        // Then — the row is keyed "$bucket::$path" and the file sits at <data>/storage/<bucket>/<path>,
        // exactly where installs of earlier versions keep theirs
        val row = assertNotNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals("$BUCKET::$PATH", row.key)
        assertEquals(PATH, row.path)
        assertEquals((dataDirectory / "storage" / BUCKET / "u-1" / "me.png").toString(), row.localPath)
        assertEquals(ORIGINAL, FileSystem.SYSTEM.read(row.localPath.toPath()) { readUtf8() })
    }

    private companion object {
        const val BUCKET = "avatars"
        const val PATH = "u-1/me.png"
        const val ORIGINAL = "full-size-image"
    }
}
