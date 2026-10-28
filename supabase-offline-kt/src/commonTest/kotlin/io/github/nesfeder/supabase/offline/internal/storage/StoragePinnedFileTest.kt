// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.internal.db.CachedFileRow
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestFileCache
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A pinned object keeps its cached copies, the original and every image transform, through every
 * eviction pass, across a restart, and through writes to it; a move takes the pin along. The
 * explicit calls still remove it: `evictCachedFile(path)`, a delete through the bucket,
 * `clearFileCache()`, `clearLocalData()` and the cache emptied for another user. Pinning an object
 * that is not cached records the pin alone, and its next download is kept.
 *
 * The downloads run the real supabase-kt bucket against [RecordingStorageServer], because only the
 * real bucket turns a transform into its own request; the writes run against a [FakeBucketApi].
 * Uses are spaced minutes apart on the test clock, past the eviction's grace window.
 */
class StoragePinnedFileTest {

    private lateinit var dataDirectory: Path
    private lateinit var server: RecordingStorageServer
    private lateinit var store: TestStore
    private var now = 0L

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-pins-${Random.nextLong().toULong()}"
        server = RecordingStorageServer()
        for (path in listOf(PINNED, OLDER, NEWER, NEWEST)) {
            server.objects["$BUCKET/$path"] = ByteArray(FILE_SIZE) { 7 }
        }
        store = newTestStore(nowMillis = { now })
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun cache(maxSizeBytes: Long? = null): StorageFileCache =
        newTestFileCache(
            baseDirectory = dataDirectory,
            store = store,
            maxSizeBytes = maxSizeBytes,
            nowMillis = { now },
        )

    private fun bucket(
        cache: StorageFileCache,
        delegate: BucketApi = server.supabaseClient().storage.from(BUCKET),
    ): CachingBucketApi =
        newTestBucketApi(
            delegate = delegate,
            baseDirectory = dataDirectory,
            store = store,
            cache = cache,
        )

    /** Downloads [path] through [bucket] at minute [minute], with [thumbnail] too when asked. */
    private suspend fun download(
        bucket: OfflineBucketApi,
        path: String,
        minute: Long,
        thumbnail: Boolean = false,
    ) {
        now = minute * MINUTE
        bucket.downloadAuthenticated(path) {}
        if (thumbnail) bucket.downloadAuthenticated(path) { transform { size(64, 64) } }
    }

    private suspend fun OfflineBucketApi.thumbnailOf(path: String) =
        cachedFile(path) { size(64, 64) }

    @Test
    fun a_pinned_objects_copies_outlive_an_eviction_pass_that_takes_newer_files() = runTest {
        // Given — the pinned object and its thumbnail are the least recently used files
        val bucket = bucket(cache(maxSizeBytes = 60))
        bucket.pinCachedFile(PINNED)
        download(bucket, PINNED, minute = 1, thumbnail = true)
        download(bucket, OLDER, minute = 2)

        // When — a third download takes the cache past its bound
        download(bucket, NEWER, minute = 3)

        // Then — the eviction took the oldest file it may take, and left the pinned copies
        assertNotNull(bucket.cachedFile(PINNED))
        assertNotNull(bucket.thumbnailOf(PINNED))
        assertNull(bucket.cachedFile(OLDER))
        assertNotNull(bucket.cachedFile(NEWER))
        assertTrue(bucket.isCachedFilePinned(PINNED))
        assertFalse(bucket.isCachedFilePinned(OLDER))
    }

    @Test
    fun a_pin_outlives_a_restart() = runTest {
        // Given — an object pinned and cached, then the cache built again over the same database
        // and directory, as at the next launch
        val before = bucket(cache(maxSizeBytes = 60))
        before.pinCachedFile(PINNED)
        download(before, PINNED, minute = 1, thumbnail = true)
        val after = bucket(cache(maxSizeBytes = 60))

        // When
        val pinnedAfterRestart = after.isCachedFilePinned(PINNED)
        download(after, OLDER, minute = 2)
        download(after, NEWER, minute = 3)

        // Then — still pinned, and still spared
        assertTrue(pinnedAfterRestart)
        assertNotNull(after.cachedFile(PINNED))
        assertNotNull(after.thumbnailOf(PINNED))
        assertNull(after.cachedFile(OLDER))
    }

    @Test
    fun pinning_an_object_not_cached_records_the_pin_alone_and_keeps_its_next_download() = runTest {
        // Given
        val bucket = bucket(cache(maxSizeBytes = 25))

        // When — pinned before any download, and pinned twice
        bucket.pinCachedFile(PINNED)
        bucket.pinCachedFile(PINNED)

        // Then — nothing was sent or cached
        assertTrue(bucket.isCachedFilePinned(PINNED))
        assertNull(bucket.cachedFile(PINNED))
        assertEquals(0, server.requests.size)

        // And its first download is kept through a pass that evicts a newer file
        download(bucket, PINNED, minute = 1)
        download(bucket, OLDER, minute = 2)
        download(bucket, NEWER, minute = 3)
        assertNotNull(bucket.cachedFile(PINNED))
        assertNull(bucket.cachedFile(OLDER))
    }

    @Test
    fun pinned_files_that_fill_the_bound_leave_the_cache_over_it_and_others_go() = runTest {
        // Given — two pinned files that alone pass the bound
        val bucket = bucket(cache(maxSizeBytes = 15))
        bucket.pinCachedFile(PINNED)
        bucket.pinCachedFile(OLDER)
        download(bucket, PINNED, minute = 1)
        download(bucket, OLDER, minute = 2)

        // When — two more downloads, a minute apart
        download(bucket, NEWER, minute = 3)
        download(bucket, NEWEST, minute = 4)

        // Then — every unpinned file but the newest went; the pinned ones keep the cache over
        assertNotNull(bucket.cachedFile(PINNED))
        assertNotNull(bucket.cachedFile(OLDER))
        assertNull(bucket.cachedFile(NEWER))
        assertNotNull(bucket.cachedFile(NEWEST))
        assertEquals(3L * FILE_SIZE, store.cachedFileDao.totalSizeBytes())
    }

    @Test
    fun pinned_files_under_the_bound_but_past_its_low_water_mark_flush_every_other_file() =
        runTest {
            // Given — a bound of 100 (low water 90), and a pinned file of 92 bytes, which passes
            // the low-water mark without filling the bound
            server.objects["$BUCKET/big.png"] = ByteArray(92) { 7 }
            for (name in listOf("a", "b", "c")) server.objects["$BUCKET/$name.png"] = ByteArray(3)
            val bucket = bucket(cache(maxSizeBytes = 100))
            bucket.pinCachedFile("big.png")
            download(bucket, "big.png", minute = 1)
            download(bucket, "a.png", minute = 2)
            download(bucket, "b.png", minute = 3)

            // When — c.png takes the cache to 101; evicting a.png alone brings it to 98
            download(bucket, "c.png", minute = 4)

            // Then — the least recently used file went, and only it: the pass stopped at the bound
            assertNull(bucket.cachedFile("a.png"))
            assertNotNull(bucket.cachedFile("b.png"))
            assertNotNull(bucket.cachedFile("c.png"))
            assertNotNull(bucket.cachedFile("big.png"))
            assertEquals(98L, store.cachedFileDao.totalSizeBytes())
        }

    @Test
    fun a_move_onto_a_name_that_cannot_be_pinned_drops_the_pin() = runTest {
        // Given — a pinned object
        val fake = FakeBucketApi(bucketId = BUCKET, onDownload = { ByteArray(FILE_SIZE) })
        val bucket = bucket(cache(), delegate = fake)
        bucket.pinCachedFile(PINNED)

        // When — moved to a name whose downloads are never cached, which pinCachedFile refuses
        bucket.move(PINNED, UNPINNABLE)

        // Then — neither end is pinned, and no record of a pin is left for the new name
        assertFalse(bucket.isCachedFilePinned(PINNED))
        assertFalse(bucket.isCachedFilePinned(UNPINNABLE))
        assertFailsWith<IllegalArgumentException> { bucket.pinCachedFile(UNPINNABLE) }
        assertNull(
            store.queryCacheDao.get(RESERVED_TABLE_NAME, "pinned_file\u001F$BUCKET::$UNPINNABLE")
        )
    }

    @Test
    fun a_pinned_object_kept_in_its_hashed_file_is_spared_and_its_explicit_removals_take_the_pin() =
        runTest {
            // Given — a folder `album` holding cover.png, then an object named `album`, whose own
            // place the folder takes, so its copy lives in its hashed file; the object pinned
            server.objects["$BUCKET/$ALBUM_COVER"] = ByteArray(FILE_SIZE) { 1 }
            server.objects["$BUCKET/$ALBUM"] = ByteArray(FILE_SIZE) { 2 }
            val cache = cache(maxSizeBytes = 25)
            val bucket = bucket(cache)
            bucket.pinCachedFile(ALBUM)
            download(bucket, ALBUM_COVER, minute = 1)
            download(bucket, ALBUM, minute = 2)
            val album = assertNotNull(bucket.cachedFile(ALBUM))
            now = 3 * MINUTE
            assertNotNull(bucket.cachedFile(ALBUM_COVER))

            // When — a download takes the cache past its bound, the album the least recently used
            download(bucket, OLDER, minute = 4)
            val albumAfterPass = bucket.cachedFile(ALBUM)
            val coverAfterPass = bucket.cachedFile(ALBUM_COVER)
            // Then evicted, and once pinned again and cached, the file cache cleared
            bucket.evictCachedFile(ALBUM)
            val pinnedAfterEviction = bucket.isCachedFilePinned(ALBUM)
            val albumAfterEviction = bucket.cachedFile(ALBUM)
            bucket.pinCachedFile(ALBUM)
            download(bucket, ALBUM, minute = 5)
            cache.clearFiles()

            // Then — the pass spared the hashed copy and took the next file; each explicit removal
            // took the copy and the pin
            assertTrue(HASHED_DIRECTORY_NAME in album.path, album.path)
            assertNotNull(albumAfterPass)
            assertNull(coverAfterPass)
            assertFalse(pinnedAfterEviction)
            assertNull(albumAfterEviction)
            assertFalse(bucket.isCachedFilePinned(ALBUM))
            assertNull(bucket.cachedFile(ALBUM))
        }

    @Test
    fun eviction_and_a_clear_leave_queued_uploads_files_alone_with_pins_present() = runTest {
        // Given — a queued upload's file beside the cache, and a pinned object
        val uploads =
            QueuedUploadFiles(
                dataDirectoryProvider = { dataDirectory },
                engineLogger = EngineLogger(null, EngineLogLevel.INFO),
            )
        val staged = uploads.stage("bytes waiting to be sent".encodeToByteArray())
        uploads.endStaging(staged)
        val cache = cache(maxSizeBytes = 25)
        val bucket = bucket(cache)
        bucket.pinCachedFile(PINNED)
        download(bucket, PINNED, minute = 1)
        download(bucket, OLDER, minute = 2)

        // When — an eviction pass, then the file cache cleared
        download(bucket, NEWER, minute = 3)
        val olderAfterPass = bucket.cachedFile(OLDER)
        val stagedAfterPass = FileSystem.SYSTEM.exists(uploads.fileOf(staged))
        cache.clearFiles()

        // Then — the pass took an unpinned copy and the clear every copy and pin; the upload's
        // file stayed through both
        assertNull(olderAfterPass)
        assertTrue(stagedAfterPass)
        assertTrue(FileSystem.SYSTEM.exists(uploads.fileOf(staged)))
        assertFalse(bucket.isCachedFilePinned(PINNED))
    }

    @Test
    fun an_unpinned_objects_copies_are_evicted_again() = runTest {
        // Given
        val bucket = bucket(cache(maxSizeBytes = 25))
        bucket.pinCachedFile(PINNED)
        download(bucket, PINNED, minute = 1)

        // When
        bucket.unpinCachedFile(PINNED)
        download(bucket, OLDER, minute = 2)
        download(bucket, NEWER, minute = 3)

        // Then — the least recently used file went
        assertFalse(bucket.isCachedFilePinned(PINNED))
        assertNull(bucket.cachedFile(PINNED))
        assertNotNull(bucket.cachedFile(OLDER))
    }

    @Test
    fun an_object_pinned_while_an_eviction_scans_is_spared() = runTest {
        // Given — a database that pins the oldest file while the pass reads every row
        var pinDuringScan: (suspend () -> Unit)? = null
        val dao =
            object : InMemoryCachedFileDao() {
                override suspend fun all(): List<CachedFileRow> {
                    val rows = super.all()
                    pinDuringScan?.let { pin ->
                        pinDuringScan = null
                        pin()
                    }
                    return rows
                }
            }
        store = newTestStore(cachedFileDao = dao, nowMillis = { now })
        val cache = cache(maxSizeBytes = 25)
        val bucket = bucket(cache)
        download(bucket, PINNED, minute = 1)
        download(bucket, OLDER, minute = 2)

        // When — the third download's pass scans, and the oldest file is pinned meanwhile
        pinDuringScan = { cache.pinObject(BUCKET, PINNED) }
        download(bucket, NEWER, minute = 3)

        // Then — the pass looked at the pins again before it took a file
        assertNotNull(bucket.cachedFile(PINNED))
        assertNull(bucket.cachedFile(OLDER))
    }

    @Test
    fun evictCachedFile_deletes_a_pinned_objects_copies_and_its_pin() = runTest {
        // Given
        val bucket = bucket(cache())
        bucket.pinCachedFile(PINNED)
        download(bucket, PINNED, minute = 1, thumbnail = true)

        // When — one transform evicted, then the whole object
        bucket.evictCachedFile(PINNED) { size(64, 64) }
        val thumbnailAfterOne = bucket.thumbnailOf(PINNED)
        val pinnedAfterOne = bucket.isCachedFilePinned(PINNED)
        bucket.evictCachedFile(PINNED)

        // Then — the transform went alone and the pin stayed; the object's eviction took the rest
        assertNull(thumbnailAfterOne)
        assertTrue(pinnedAfterOne)
        assertNull(bucket.cachedFile(PINNED))
        assertFalse(bucket.isCachedFilePinned(PINNED))
    }

    @Test
    fun a_write_keeps_the_pin_a_move_takes_it_and_a_delete_drops_it() = runTest {
        // Given — a pinned object, cached, in a bucket whose writes succeed
        val fake = FakeBucketApi(bucketId = BUCKET, onDownload = { ByteArray(FILE_SIZE) })
        val bucket = bucket(cache(), delegate = fake)
        bucket.pinCachedFile(PINNED)
        bucket.downloadAuthenticated(PINNED) {}

        // When — it is uploaded again, downloaded, moved, copied, and the copy's source deleted
        bucket.upload(PINNED, ByteArray(FILE_SIZE))
        val cachedAfterUpload = bucket.cachedFile(PINNED)
        val pinnedAfterUpload = bucket.isCachedFilePinned(PINNED)
        bucket.downloadAuthenticated(PINNED) {}
        bucket.move(PINNED, MOVED)
        val pinnedAtSource = bucket.isCachedFilePinned(PINNED)
        val pinnedAtDestination = bucket.isCachedFilePinned(MOVED)
        val movedCopy = bucket.cachedFile(MOVED)
        bucket.copy(MOVED, COPIED)
        val copyPinned = bucket.isCachedFilePinned(COPIED)
        bucket.delete(MOVED)

        // Then — the upload dropped the copy and kept the pin; the move took both along; the copy
        // is a new object; the delete took the pin
        assertNull(cachedAfterUpload)
        assertTrue(pinnedAfterUpload)
        assertFalse(pinnedAtSource)
        assertTrue(pinnedAtDestination)
        assertNotNull(movedCopy)
        assertFalse(copyPinned)
        assertFalse(bucket.isCachedFilePinned(MOVED))
        assertNull(bucket.cachedFile(MOVED))
    }

    @Test
    fun a_move_that_may_have_landed_pins_both_ends_and_a_delete_that_may_have_keeps_it() = runTest {
        // Given — a pinned object, cached, in a bucket whose writes time out
        val fake = FakeBucketApi(bucketId = BUCKET, onDownload = { ByteArray(FILE_SIZE) })
        val bucket = bucket(cache(), delegate = fake)
        bucket.pinCachedFile(PINNED)
        bucket.downloadAuthenticated(PINNED) {}
        fake.onWrite = { throw HttpRequestTimeoutException(PINNED, 10_000) }

        // When
        assertFailsWith<HttpRequestTimeoutException> { bucket.move(PINNED, MOVED) }
        val cachedAfterMove = bucket.cachedFile(PINNED)
        assertFailsWith<HttpRequestTimeoutException> { bucket.delete(PINNED) }

        // Then — the object is at one end or the other, and the delete may not have landed
        assertNull(cachedAfterMove)
        assertTrue(bucket.isCachedFilePinned(PINNED))
        assertTrue(bucket.isCachedFilePinned(MOVED))
    }

    @Test
    fun a_path_that_cannot_be_cached_cannot_be_pinned() = runTest {
        // Given
        val bucket = bucket(cache())

        // When / Then — refused with the reason; unpinning and asking are harmless
        val refusal =
            assertFailsWith<IllegalArgumentException> { bucket.pinCachedFile(UNCACHEABLE) }
        assertTrue(refusal.message.orEmpty().startsWith("This object path cannot be cached"))
        bucket.unpinCachedFile(UNCACHEABLE)
        assertFalse(bucket.isCachedFilePinned(UNCACHEABLE))
    }

    @Test
    fun a_pin_made_while_the_cache_is_another_users_is_not_kept() = runTest {
        // Given — a cache that belongs to one user, then another signed in
        var identity = QueueIdentity.User("a").storedValue
        store = newTestStore(isCacheOwnedPerIdentity = true, identityProvider = { identity })
        val bucket = bucket(cache())
        bucket.pinCachedFile(PINNED)
        identity = QueueIdentity.User("b").storedValue

        // When — pinned before the cache is dropped for the new user
        bucket.pinCachedFile(OLDER)
        val pinnedForTheNewUser = bucket.isCachedFilePinned(OLDER)

        // Then — not kept for the new user, nor in the first user's cache; the first user's pin
        // is not shown to the new one
        assertFalse(pinnedForTheNewUser)
        assertFalse(bucket.isCachedFilePinned(PINNED))
        identity = QueueIdentity.User("a").storedValue
        assertFalse(bucket.isCachedFilePinned(OLDER))
        assertTrue(bucket.isCachedFilePinned(PINNED))
    }

    @Test
    fun the_cache_emptied_for_another_user_or_cleared_takes_every_pin() = runTest {
        // Given
        val cache = cache()
        val bucket = bucket(cache)

        // When — pinned, then the cache dropped for another user; pinned again, then local data
        // cleared; pinned again, then the file cache cleared
        bucket.pinCachedFile(PINNED)
        cache.clear { store.localData.dropCacheFor(QueueIdentity.User("b").storedValue) }
        val afterNewOwner = bucket.isCachedFilePinned(PINNED)
        bucket.pinCachedFile(PINNED)
        cache.clear { store.localData.clearLocalData() }
        val afterClearLocalData = bucket.isCachedFilePinned(PINNED)
        bucket.pinCachedFile(PINNED)
        download(bucket, PINNED, minute = 1)
        cache.clearFiles()

        // Then
        assertFalse(afterNewOwner)
        assertFalse(afterClearLocalData)
        assertFalse(bucket.isCachedFilePinned(PINNED))
        assertNull(bucket.cachedFile(PINNED))
    }

    @Test
    fun the_clients_clearFileCache_and_clearLocalData_take_the_pins() = runTest {
        // Given — a client over one database, as an app has it
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                queryCacheDao = FakeQueryCacheDao(),
                cachedFileDao = InMemoryCachedFileDao(),
                baseDirectoryProvider = { dataDirectory },
            )
        val bucket = client.storage.from(BUCKET)

        // When
        bucket.pinCachedFile(PINNED)
        bucket.downloadAuthenticated(PINNED) {}
        client.storage.clearFileCache()
        val afterClearFileCache = bucket.isCachedFilePinned(PINNED)
        bucket.pinCachedFile(PINNED)
        val pinnedAgain = bucket.isCachedFilePinned(PINNED)
        client.clearLocalData()

        // Then
        assertFalse(afterClearFileCache)
        assertNull(bucket.cachedFile(PINNED))
        assertTrue(pinnedAgain)
        assertFalse(bucket.isCachedFilePinned(PINNED))
        client.close()
    }

    private companion object {
        const val BUCKET = "media"
        const val PINNED = "pinned.png"
        const val OLDER = "older.png"
        const val NEWER = "newer.png"
        const val NEWEST = "newest.png"
        const val MOVED = "moved.png"
        const val COPIED = "copied.png"
        const val UNCACHEABLE = "/leading-slash.png"
        const val UNPINNABLE = "photo?v=1.png"
        const val ALBUM = "album"
        const val ALBUM_COVER = "album/cover.png"
        const val FILE_SIZE = 10
        const val MINUTE = 60_000L
    }
}
