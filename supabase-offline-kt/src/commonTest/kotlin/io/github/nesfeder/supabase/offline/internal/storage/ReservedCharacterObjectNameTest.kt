// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

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
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * supabase-kt percent-encodes each segment of an object name in the URL, so a name holding `?`, `#`
 * or `%` requests its own object: `x?y.png` never reaches `x`, and `a%b.png` is sent as any other
 * name. The cache keeps that object's bytes under the name the caller asked for. (supabase-kt 3.6
 * put the name in the URL as written, and the library then refused to cache such names.)
 */
class ReservedCharacterObjectNameTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: TestStore
    private lateinit var server: RecordingStorageServer

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-cut-short-${Random.nextLong().toULong()}"
        store = newTestStore()
        server = RecordingStorageServer()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    @Test
    fun a_name_with_a_question_mark_caches_its_own_object() = runTest {
        assertCachesItsOwnBytes(name = "x?y.png", cutTo = "x")
    }

    @Test
    fun a_name_with_a_hash_caches_its_own_object() = runTest {
        assertCachesItsOwnBytes(name = "x#y.png", cutTo = "x")
    }

    @Test
    fun a_folder_name_with_a_question_mark_caches_its_own_object() = runTest {
        assertCachesItsOwnBytes(name = "dir/x?y/z.png", cutTo = "dir/x")
    }

    @Test
    fun a_name_with_a_bare_percent_sign_caches_its_own_object() = runTest {
        assertCachesItsOwnBytes(name = "a%b.png", cutTo = "a")
    }

    @Test
    fun a_cached_file_of_such_a_name_holds_its_own_bytes() = runTest {
        // Given — the object the name means, and another at the path an unencoded URL is cut to
        val name = "x?y.png"
        server.objects["$BUCKET/$name"] = OWN.encodeToByteArray()
        server.objects["$BUCKET/x"] = OTHER.encodeToByteArray()

        // When
        val file = bucket().ensureCachedFile(name)

        // Then
        assertEquals(OWN, FileSystem.SYSTEM.read(file.path.toPath()) { readUtf8() })
    }

    /**
     * Downloads [name] while the server holds another object at [cutTo], the path the name would
     * reach were it sent unencoded, then checks that the download and the cache hold [name]'s own
     * bytes.
     */
    private suspend fun assertCachesItsOwnBytes(name: String, cutTo: String) {
        // Given
        server.objects["$BUCKET/$name"] = OWN.encodeToByteArray()
        server.objects["$BUCKET/$cutTo"] = OTHER.encodeToByteArray()

        // When
        val downloaded = bucket().downloadAuthenticated(name) {}.decodeToString()

        // Then
        assertEquals(OWN, downloaded, name)
        val row = assertNotNull(store.getStorageByKey(BUCKET, name), name)
        assertEquals(OWN, FileSystem.SYSTEM.read(row.localPath.toPath()) { readUtf8() }, name)
    }

    private fun bucket() =
        newTestBucketApi(
            delegate = server.supabaseClient().storage.from(BUCKET),
            baseDirectory = dataDirectory,
            store = store,
        )

    private companion object {
        const val BUCKET = "avatars"
        const val OWN = "the-named-object"
        const val OTHER = "another-object"
    }
}
