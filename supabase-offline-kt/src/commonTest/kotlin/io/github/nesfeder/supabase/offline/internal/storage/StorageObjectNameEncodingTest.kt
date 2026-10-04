// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Object names with spaces, `%`, `?` and `#` round-trip through the download, the cache and the
 * local file.
 *
 * Since supabase-kt 3.7 the bucket URL-encodes each segment of an object path on the wire, so such
 * a name reaches a different URL than it did on 3.6. The cache is keyed by the name as the caller
 * wrote it, not by its URL form, so the same name must address the same entry however it was
 * encoded. This pins both halves: what goes on the wire, and what lands on disk.
 */
class StorageObjectNameEncodingTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: TestStore
    private lateinit var server: RecordingStorageServer

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-encoding-${Random.nextLong().toULong()}"
        store = newTestStore()
        server = RecordingStorageServer()
        server.objects["$BUCKET/$NAME"] = CONTENT.encodeToByteArray()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun bucket() =
        newTestBucketApi(
            delegate = server.supabaseClient().storage.from(BUCKET),
            baseDirectory = dataDirectory,
            store = store,
        )

    @Test
    fun each_path_segment_is_percent_encoded_on_the_wire() = runTest {
        // Given
        val bucket = bucket()

        // When
        bucket.downloadAuthenticated(NAME) {}

        // Then
        assertEquals(
            "/storage/v1/object/authenticated/$BUCKET/a%20b/my%20file%25%3F%23.png",
            server.requests.single().encodedPath,
        )
    }

    @Test
    fun the_name_as_written_keys_the_cache_row_and_names_the_local_file() = runTest {
        // Given
        val bucket = bucket()

        // When
        bucket.downloadAuthenticated(NAME) {}

        // Then
        val row = assertNotNull(store.getStorageByKey(BUCKET, NAME))
        assertEquals("$BUCKET::$NAME", row.key)
        val file = dataDirectory / "storage" / BUCKET / "a b" / "my file%?#.png"
        assertEquals(file.toString(), row.localPath)
        assertEquals(CONTENT, FileSystem.SYSTEM.read(file) { readUtf8() })
    }

    @Test
    fun the_cached_copy_is_served_offline_under_the_same_name() = runTest {
        // Given
        val bucket = bucket()
        bucket.downloadAuthenticated(NAME) {}

        // When
        server.online = false
        val offline = bucket.downloadAuthenticated(NAME) {}.decodeToString()

        // Then
        assertEquals(CONTENT, offline)
    }

    @Test
    fun a_not_found_evicts_the_entry_under_the_same_name() = runTest {
        // Given
        val bucket = bucket()
        bucket.downloadAuthenticated(NAME) {}
        val file = assertNotNull(store.getStorageByKey(BUCKET, NAME)).localPath.toPath()

        // When — the object is deleted on the server
        server.objects.remove("$BUCKET/$NAME")
        assertFailsWith<NotFoundRestException> { bucket.downloadAuthenticated(NAME) {} }

        // Then
        assertNull(store.getStorageByKey(BUCKET, NAME))
        assertFalse(FileSystem.SYSTEM.exists(file))
    }

    private companion object {
        const val BUCKET = "avatars"
        const val NAME = "a b/my file%?#.png"
        const val CONTENT = "encoded-name-bytes"
    }
}
