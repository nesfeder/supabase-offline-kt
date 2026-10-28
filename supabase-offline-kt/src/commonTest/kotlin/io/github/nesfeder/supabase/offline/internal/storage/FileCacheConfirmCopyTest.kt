// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestFileCache
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A 304 confirms a cached copy only while it is still the copy the download asked about: the one
 * cached under the ETag its `If-None-Match` named, never dropped since the download began, and
 * whole on disk. A confirmed copy's fetch time becomes the time of the 304.
 */
class FileCacheConfirmCopyTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: TestStore
    private lateinit var cache: StorageFileCache
    private var clock = 1_000L

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-confirm-${Random.nextLong().toULong()}"
        store = newTestStore(nowMillis = { clock })
        cache = newTestFileCache(baseDirectory = dataDirectory, store = store)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private val entry: FileCacheEntry
        get() = assertNotNull(cache.entryFor(BUCKET, PATH))

    /** Caches [content] as the object's copy, sent under [eTag]. */
    private suspend fun cacheCopy(eTag: String?, content: String = CONTENT) {
        val start = cache.beginDownload()
        cache.write(entry, content.encodeToByteArray(), start, eTag)
        cache.endDownload(start)
    }

    private suspend fun fetchedAt(): Long? =
        store.cachedFileDao.getByKey(cachedFileKeyOf(BUCKET, PATH))?.lastFetchedEpochMs

    @Test
    fun the_copy_asked_about_is_confirmed_and_its_fetch_time_moves_to_now() = runTest {
        // Given
        cacheCopy(eTag = ETAG)
        val asking = cache.beginDownload()

        // When
        clock = 9_000L
        val confirmed = cache.confirmCopy(entry, ETAG, asking)
        cache.endDownload(asking)

        // Then — and its ETags are read back for the next request
        assertTrue(confirmed)
        assertEquals(9_000L, fetchedAt())
        assertEquals(mapOf(entry.key to ETAG), cache.eTagsOf(BUCKET, PATH))
    }

    @Test
    fun a_copy_dropped_and_cached_again_since_the_download_began_is_not_confirmed() = runTest {
        // Given — the download asking about the copy began; then the object was written, and its
        // new copy happens to carry the same ETag
        cacheCopy(eTag = ETAG)
        val asking = cache.beginDownload()
        cache.invalidateObject(BUCKET, PATH)
        cacheCopy(eTag = ETAG, content = "newer bytes")

        // When
        clock = 9_000L
        val confirmed = cache.confirmCopy(entry, ETAG, asking)
        cache.endDownload(asking)

        // Then — the 304 may predate the write: the newer copy keeps its own fetch time
        assertFalse(confirmed)
        assertEquals(1_000L, fetchedAt())
    }

    @Test
    fun a_copy_cached_under_another_etag_is_not_confirmed() = runTest {
        // Given — another download replaced the copy the request asked about
        cacheCopy(eTag = ETAG)
        val asking = cache.beginDownload()
        cacheCopy(eTag = "\"another\"", content = "other bytes")

        // When
        clock = 9_000L
        val confirmed = cache.confirmCopy(entry, ETAG, asking)
        cache.endDownload(asking)

        // Then
        assertFalse(confirmed)
        assertEquals(1_000L, fetchedAt())
    }

    @Test
    fun a_copy_cut_short_on_disk_or_cleared_is_not_confirmed() = runTest {
        // Given — a copy whose file no longer has the recorded size
        cacheCopy(eTag = ETAG)
        val asking = cache.beginDownload()
        FileSystem.SYSTEM.write(entry.file) { writeUtf8("cut") }

        // When — and once the file cache is cleared
        val cutShort = cache.confirmCopy(entry, ETAG, asking)
        cacheCopy(eTag = ETAG)
        cache.clearFiles()
        cacheCopy(eTag = ETAG)
        val cleared = cache.confirmCopy(entry, ETAG, asking)
        cache.endDownload(asking)

        // Then
        assertFalse(cutShort)
        assertFalse(cleared)
    }

    @Test
    fun a_copy_without_an_etag_is_never_asked_about() = runTest {
        // Given — a copy the server sent without an ETag
        cacheCopy(eTag = null)

        // When
        val eTags = cache.eTagsOf(BUCKET, PATH)

        // Then
        assertEquals(emptyMap(), eTags)
    }

    private companion object {
        const val BUCKET = "media"
        const val PATH = "photos/a.png"
        const val CONTENT = "the original bytes"
        const val ETAG = "\"abc123\""
    }
}
