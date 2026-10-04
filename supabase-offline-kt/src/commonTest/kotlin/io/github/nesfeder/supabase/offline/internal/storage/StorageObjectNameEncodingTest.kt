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
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * The library's own on-disk encoding of an object name with spaces, `%`, `?` and `#`: the cache is
 * keyed by the name as the caller wrote it, not by its URL form, and the local file is named by it.
 * Pinned on every supabase-kt minor, through the file cache; what reaches the wire, from 3.8 on, is
 * in `StorageObjectNameUrlEncodingTest`.
 */
class StorageObjectNameEncodingTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: TestStore

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-encoding-${Random.nextLong().toULong()}"
        store = newTestStore()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    @Test
    fun the_name_as_written_keys_the_cache_row_and_names_the_local_file() = runTest {
        // Given
        val cache = newTestFileCache(baseDirectory = dataDirectory, store = store)
        val entry = assertNotNull(cache.entryFor(BUCKET, NAME))

        // When
        cache.write(entry, CONTENT.encodeToByteArray(), cache.currentGeneration())

        // Then
        val row = assertNotNull(store.getStorageByKey(BUCKET, NAME))
        assertEquals("$BUCKET::$NAME", row.key)
        val file = dataDirectory / "storage" / BUCKET / "a b" / "my file%?#.png"
        assertEquals(file.toString(), row.localPath)
        assertEquals(CONTENT, FileSystem.SYSTEM.read(file) { readUtf8() })
    }

    private companion object {
        const val BUCKET = "avatars"
        const val NAME = "a b/my file%?#.png"
        const val CONTENT = "encoded-name-bytes"
    }
}
