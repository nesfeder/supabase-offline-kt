// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.exceptions.HttpRequestException
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
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * supabase-kt 3.6 puts an object name in the URL as written: `x?y.png` requests `x`, a different
 * object, and `a%b.png` fails. Whatever the download returns, the cache never keeps another
 * object's bytes under the name the caller asked for, and a name that fails upstream fails as
 * supabase-kt fails it. From 3.8, which encodes the name, each is cached as any other.
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
    fun a_name_with_a_question_mark_never_caches_the_object_its_url_reaches() = runTest {
        assertCachesOnlyItsOwnBytes(name = "x?y.png", reached = "x")
    }

    @Test
    fun a_name_with_a_hash_never_caches_the_object_its_url_reaches() = runTest {
        assertCachesOnlyItsOwnBytes(name = "x#y.png", reached = "x")
    }

    @Test
    fun a_folder_name_with_a_question_mark_never_caches_the_object_its_url_reaches() = runTest {
        assertCachesOnlyItsOwnBytes(name = "dir/x?y/z.png", reached = "dir/x")
    }

    @Test
    fun a_name_with_a_percent_sign_downloads_as_supabase_kt_does() = runTest {
        // Given
        val name = "a%b.png"
        server.objects["$BUCKET/$name"] = OWN.encodeToByteArray()

        // When
        val failure = runCatching { bucket().downloadAuthenticated(name) {} }.exceptionOrNull()

        // Then — supabase-kt's own failure, if any, and nothing but the name's bytes cached
        if (failure != null) assertIs<HttpRequestException>(failure)
        assertCachedBytesAreItsOwn(name)
    }

    @Test
    fun a_cached_file_of_such_a_name_holds_its_own_bytes_or_is_refused() = runTest {
        // Given — the object the name means, and another at the path its URL is cut to
        val name = "x?y.png"
        server.objects["$BUCKET/$name"] = OWN.encodeToByteArray()
        server.objects["$BUCKET/x"] = OTHER.encodeToByteArray()

        // When
        val ensured = runCatching { bucket().ensureCachedFile(name) }

        // Then — refused as uncacheable, or the name's own bytes
        val file = ensured.getOrNull()
        if (file == null) {
            assertIs<IllegalArgumentException>(ensured.exceptionOrNull())
        } else {
            assertEquals(OWN, FileSystem.SYSTEM.read(file.path.toPath()) { readUtf8() })
        }
    }

    /**
     * Downloads [name] while the server holds another object at [reached], the path a name sent
     * unencoded is cut to, then checks that the cache holds [name]'s own bytes or nothing.
     */
    private suspend fun assertCachesOnlyItsOwnBytes(name: String, reached: String) {
        // Given — the object the name means, and another at the path its URL is cut to
        server.objects["$BUCKET/$name"] = OWN.encodeToByteArray()
        server.objects["$BUCKET/$reached"] = OTHER.encodeToByteArray()

        // When — supabase-kt returns whichever object its request reached
        bucket().downloadAuthenticated(name) {}

        // Then — nothing cached, or the name's own bytes
        assertCachedBytesAreItsOwn(name)
    }

    /** Fails when the cache holds anything but [name]'s own bytes under [name]. */
    private suspend fun assertCachedBytesAreItsOwn(name: String) {
        val row = store.getStorageByKey(BUCKET, name) ?: return
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
