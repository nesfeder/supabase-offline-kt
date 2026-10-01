// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A download that reached the server is the caller's, whatever happens to the cache copy: a cache
 * write that fails is logged and the bytes are still returned.
 */
class StorageCacheWriteTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: OfflineStore
    private lateinit var delegate: FakeBucketApi
    private val logger = RecordingOfflineLogger()

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-cache-write-${Random.nextLong().toULong()}"
        store = newTestOfflineStore()
        delegate = FakeBucketApi(bucketId = BUCKET)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun bucket() =
        newTestBucketApi(
            delegate = delegate,
            baseDirectory = dataDirectory,
            store = store,
            engineLogger = logger.asEngineLogger(),
        )

    /** Makes every cache write fail: the bucket's cache directory is a plain file. */
    private fun breakTheCacheDirectory() {
        FileSystem.SYSTEM.createDirectories(dataDirectory / "storage")
        FileSystem.SYSTEM.write(dataDirectory / "storage" / BUCKET) { writeUtf8("not a directory") }
    }

    @Test
    fun a_download_whose_cache_write_fails_still_returns_its_bytes() = runTest {
        // Given — the server answers, but the disk refuses the copy
        breakTheCacheDirectory()
        delegate.onDownload = { BYTES }

        // When
        val downloaded = bucket().downloadAuthenticated(PATH) {}

        // Then — the caller gets the bytes; nothing is tracked; the failure is logged, without the
        // path
        assertContentEquals(BYTES, downloaded)
        assertNull(store.getStorageByKey(BUCKET, PATH))
        val warnings = logger.entriesAt(OfflineLogLevel.WARNING).map { it.message }
        assertEquals(1, warnings.size, "expected one warning, got $warnings")
        assertTrue(
            warnings.none { PATH in it },
            "the object path must not reach a warning: $warnings",
        )
    }

    @Test
    fun a_public_download_whose_cache_write_fails_still_returns_its_bytes() = runTest {
        // Given
        breakTheCacheDirectory()
        delegate.onDownload = { BYTES }

        // When
        val downloaded = bucket().downloadPublic(PATH) {}

        // Then
        assertContentEquals(BYTES, downloaded)
        assertNull(store.getStorageByKey(BUCKET, PATH))
    }

    @Test
    fun a_download_whose_cache_row_cannot_be_written_still_returns_its_bytes() = runTest {
        // Given — the database refuses the row (a full disk, a closed or malformed database)
        val dao = RecordingStorageDao()
        dao.failUpserts = true
        val failingStore = newTestOfflineStore(storageDao = dao)
        delegate.onDownload = { BYTES }

        // When
        val downloaded =
            newTestBucketApi(
                    delegate = delegate,
                    baseDirectory = dataDirectory,
                    store = failingStore,
                    engineLogger = logger.asEngineLogger(),
                )
                .downloadAuthenticated(PATH) {}

        // Then — the caller gets its bytes, and no untracked file is left behind
        assertContentEquals(BYTES, downloaded)
        assertEquals(emptyList(), dao.all())
        val files =
            if (FileSystem.SYSTEM.exists(dataDirectory)) {
                FileSystem.SYSTEM.listRecursively(dataDirectory)
                    .filter { FileSystem.SYSTEM.metadata(it).isRegularFile }
                    .toList()
            } else {
                emptyList()
            }
        assertEquals(emptyList(), files)
    }

    private companion object {
        const val BUCKET = "avatars"
        const val PATH = "u-1/me.png"
        val BYTES = "image-bytes".encodeToByteArray()
    }
}
