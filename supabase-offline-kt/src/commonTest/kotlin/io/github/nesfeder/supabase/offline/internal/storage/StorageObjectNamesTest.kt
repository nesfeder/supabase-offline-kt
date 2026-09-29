// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Two objects with different names are two cache entries, however the file system would spell their
 * names: Unicode normalization forms, lone surrogates and names longer than a file name may be.
 * Such names are stored under a hash of the object's key; plain ASCII names keep their verbatim
 * file, where installs of earlier versions already have them.
 */
class StorageObjectNamesTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: OfflineStore
    private lateinit var delegate: FakeBucketApi

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-names-${Random.nextLong().toULong()}"
        store = newTestOfflineStore()
        delegate = FakeBucketApi(bucketId = BUCKET)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun bucket() =
        newTestBucketApi(delegate = delegate, baseDirectory = dataDirectory, store = store)

    /** Caches [first] and [second], then checks each is served its own bytes. */
    private suspend fun assertTwoObjects(first: String, second: String) {
        delegate.onDownload = { path ->
            (if (path == first) "FIRST" else "SECOND").encodeToByteArray()
        }
        val bucket = bucket()
        bucket.downloadAuthenticated(first) {}
        bucket.downloadAuthenticated(second) {}
        val cachedFirst = assertNotNull(bucket.cachedFile(first))
        val cachedSecond = assertNotNull(bucket.cachedFile(second))
        assertNotEquals(cachedFirst.path, cachedSecond.path, "two objects share one cache file")
        delegate.onDownload = { throw IOException("offline") }
        assertEquals("FIRST", bucket.downloadAuthenticated(first) {}.decodeToString())
        assertEquals("SECOND", bucket.downloadAuthenticated(second) {}.decodeToString())
    }

    @Test
    fun names_in_two_unicode_normalization_forms_are_two_objects() = runTest {
        // Given / When / Then — NFC "café" and NFD "cafe" + combining acute; a normalization-
        // insensitive file system (APFS) would make them one file
        assertTwoObjects("café.txt", "café.txt")
    }

    @Test
    fun names_with_different_lone_surrogates_are_two_objects() = runTest {
        // Given / When / Then — both would be encoded as "x?.txt"
        assertTwoObjects("x\uD800.txt", "x\uD801.txt")
    }

    @Test
    fun a_name_longer_than_a_file_name_may_be_still_caches() = runTest {
        // Given — 304 bytes, over the 255-byte limit of a file name
        val long = "a".repeat(300) + ".txt"
        delegate.onDownload = { "LONG".encodeToByteArray() }

        // When
        val downloaded = bucket().downloadAuthenticated(long) {}.decodeToString()
        val ensured = bucket().ensureCachedFile(long)

        // Then
        assertEquals("LONG", downloaded)
        assertEquals("LONG", FileSystem.SYSTEM.read(ensured.path.toPath()) { readUtf8() })
    }

    @Test
    fun a_plain_ascii_name_keeps_its_verbatim_file() = runTest {
        // Given
        delegate.onDownload = { "PLAIN".encodeToByteArray() }

        // When
        bucket().downloadAuthenticated("u-1/Avatar_2.PNG") {}

        // Then — where earlier versions put it, so existing caches stay warm
        assertEquals(
            (dataDirectory / "storage" / BUCKET / "u-1" / "Avatar_2.PNG").toString(),
            assertNotNull(bucket().cachedFile("u-1/Avatar_2.PNG")).path,
        )
    }

    private companion object {
        const val BUCKET = "media"
    }
}
