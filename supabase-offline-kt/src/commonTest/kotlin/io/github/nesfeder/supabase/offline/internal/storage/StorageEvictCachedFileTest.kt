// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.storage.ImageTransformation
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * A bucket's own file-cache upkeep: `evictCachedFile(path)` deletes one object's copies, the
 * original and every image transform, and nothing else; `evictCachedFile(path) { … }` deletes one
 * transform's copy alone; `cachedFile(path) { … }` finds a transform's copy without a request. Each
 * runs the real supabase-kt bucket against [RecordingStorageServer], because only the real bucket
 * turns a transform into a different request.
 */
class StorageEvictCachedFileTest {

    private lateinit var dataDirectory: Path
    private lateinit var server: RecordingStorageServer
    private val cachedFileDao = InMemoryCachedFileDao()

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-evict-${Random.nextLong().toULong()}"
        server = RecordingStorageServer()
        for (key in listOf("$BUCKET/$PATH", "$BUCKET/$NEIGHBOR", "$OTHER_BUCKET/$PATH")) {
            server.objects[key] = CONTENT.encodeToByteArray()
        }
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun newClient(): OfflineClient =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            cachedFileDao = cachedFileDao,
            baseDirectoryProvider = { dataDirectory },
        )

    private fun exists(file: CachedFile): Boolean = FileSystem.SYSTEM.exists(file.path.toPath())

    private fun read(file: CachedFile): String =
        FileSystem.SYSTEM.read(file.path.toPath()) { readUtf8() }

    /** Downloads [path]'s original and two thumbnails into [bucket]'s cache. */
    private suspend fun cacheEveryCopy(bucket: OfflineBucketApi, path: String = PATH) {
        bucket.downloadAuthenticated(path) {}
        bucket.downloadAuthenticated(path) { transform { size(64, 64) } }
        bucket.downloadAuthenticated(path) { transform { size(32, 32) } }
    }

    @Test
    fun cachedFile_with_a_transform_finds_that_transforms_copy_and_null_for_any_other() = runTest {
        // Given — a thumbnail, and nothing else, downloaded
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        val thumbnail =
            bucket
                .downloadAuthenticated(PATH) {
                    transform {
                        size(64, 64)
                        cover()
                    }
                }
                .decodeToString()
        val sent = server.requests.size

        // When — looked up with the transform, set in another order, and with others
        val found =
            bucket.cachedFile(PATH) {
                size(64, 64)
                cover()
            }
        val foundSetOtherwise =
            bucket.cachedFile(PATH) {
                resize = ImageTransformation.Resize.COVER
                height = 64
                width = 64
            }
        val otherSize = bucket.cachedFile(PATH) { size(32, 32) }
        val withoutTheResize = bucket.cachedFile(PATH) { size(64, 64) }
        val original = bucket.cachedFile(PATH)
        val nothingSet = bucket.cachedFile(PATH) {}

        // Then — the thumbnail's own copy, whatever order its transform was set in; nothing for
        // a transform or an original that was never downloaded; and no request
        assertNotNull(found)
        assertEquals(thumbnail, read(found))
        assertEquals(found.path, foundSetOtherwise?.path)
        assertNull(otherSize)
        assertNull(withoutTheResize)
        assertNull(original)
        assertNull(nothingSet)
        assertEquals(sent, server.requests.size)
        client.close()
    }

    @Test
    fun cachedFile_with_a_transform_finds_a_public_downloads_copy() = runTest {
        // Given
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.downloadPublic(PATH) { transform { size(64, 64) } }

        // When
        val found = bucket.cachedFile(PATH) { size(64, 64) }

        // Then
        assertNotNull(found)
        assertTrue(exists(found))
        client.close()
    }

    @Test
    fun a_transform_that_sets_nothing_is_the_original() = runTest {
        // Given
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.downloadAuthenticated(PATH) {}

        // When
        val nothingSet = bucket.cachedFile(PATH) {}

        // Then
        assertEquals(bucket.cachedFile(PATH), nothingSet)
        assertNotNull(nothingSet)
        client.close()
    }

    @Test
    fun the_copy_found_with_a_transform_is_the_one_served_offline() = runTest {
        // Given
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.downloadAuthenticated(PATH) { transform { size(64, 64) } }
        server.online = false

        // When
        val offline =
            bucket.downloadAuthenticated(PATH) { transform { size(64, 64) } }.decodeToString()

        // Then
        assertEquals(offline, read(assertNotNull(bucket.cachedFile(PATH) { size(64, 64) })))
        client.close()
    }

    @Test
    fun evicting_an_object_deletes_its_copy_and_every_transform_and_nothing_else() = runTest {
        // Given — the object's original and two thumbnails, a neighbor whose path starts with
        // the object's, and the same path in another bucket
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        cacheEveryCopy(bucket)
        bucket.ensureCachedFile(NEIGHBOR)
        client.storage.from(OTHER_BUCKET).ensureCachedFile(PATH)
        val evicted =
            listOfNotNull(
                bucket.cachedFile(PATH),
                bucket.cachedFile(PATH) { size(64, 64) },
                bucket.cachedFile(PATH) { size(32, 32) },
            )
        val sent = server.requests.size

        // When
        bucket.evictCachedFile(PATH)

        // Then — the object's three copies are gone, row and file; the others stay; nothing sent
        assertEquals(3, evicted.size)
        evicted.forEach { file -> assertFalse(exists(file), "a copy of the object was left") }
        assertNull(bucket.cachedFile(PATH))
        assertNull(bucket.cachedFile(PATH) { size(64, 64) })
        assertNull(bucket.cachedFile(PATH) { size(32, 32) })
        assertEquals(2, cachedFileDao.all().size)
        assertTrue(exists(assertNotNull(bucket.cachedFile(NEIGHBOR))))
        assertTrue(exists(assertNotNull(client.storage.from(OTHER_BUCKET).cachedFile(PATH))))
        assertEquals(sent, server.requests.size)
        client.close()
    }

    @Test
    fun evicting_one_transform_deletes_its_copy_alone() = runTest {
        // Given
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        cacheEveryCopy(bucket)
        val thumbnail = assertNotNull(bucket.cachedFile(PATH) { size(64, 64) })

        // When
        bucket.evictCachedFile(PATH) { size(64, 64) }

        // Then
        assertFalse(exists(thumbnail))
        assertNull(bucket.cachedFile(PATH) { size(64, 64) })
        assertTrue(exists(assertNotNull(bucket.cachedFile(PATH) { size(32, 32) })))
        assertTrue(exists(assertNotNull(bucket.cachedFile(PATH))))
        assertEquals(2, cachedFileDao.all().size)
        client.close()
    }

    @Test
    fun evicting_with_a_transform_that_sets_nothing_deletes_the_original_alone() = runTest {
        // Given
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        cacheEveryCopy(bucket)
        val original = assertNotNull(bucket.cachedFile(PATH))

        // When
        bucket.evictCachedFile(PATH) {}

        // Then
        assertFalse(exists(original))
        assertNull(bucket.cachedFile(PATH))
        assertNotNull(bucket.cachedFile(PATH) { size(64, 64) })
        assertNotNull(bucket.cachedFile(PATH) { size(32, 32) })
        client.close()
    }

    @Test
    fun a_download_on_the_wire_when_its_object_is_evicted_is_handed_over_but_not_cached() =
        runTest {
            // Given — a download parked at the server
            val client = newClient()
            val bucket = client.storage.from(BUCKET)
            server.gate = CompletableDeferred()
            val download = async { bucket.downloadAuthenticated(PATH) {} }
            server.requestArrived.await()

            // When — the object is evicted, then the response arrives
            bucket.evictCachedFile(PATH)
            server.gate?.complete(Unit)

            // Then
            assertEquals(CONTENT, download.await().decodeToString())
            assertNull(bucket.cachedFile(PATH))
            client.close()
        }

    @Test
    fun a_transforms_download_on_the_wire_when_the_transform_is_evicted_is_not_cached() = runTest {
        // Given
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        server.gate = CompletableDeferred()
        val download = async {
            bucket.downloadAuthenticated(PATH) { transform { size(64, 64) } }
        }
        server.requestArrived.await()

        // When
        bucket.evictCachedFile(PATH) { size(64, 64) }
        server.gate?.complete(Unit)

        // Then
        assertTrue(download.await().decodeToString().endsWith(CONTENT))
        assertNull(bucket.cachedFile(PATH) { size(64, 64) })
        client.close()
    }

    @Test
    fun evicting_a_transform_leaves_a_download_of_the_original_on_the_wire_to_be_cached() =
        runTest {
            // Given — the original's download parked at the server
            val client = newClient()
            val bucket = client.storage.from(BUCKET)
            server.gate = CompletableDeferred()
            val download = async { bucket.downloadAuthenticated(PATH) {} }
            server.requestArrived.await()

            // When — a thumbnail is evicted meanwhile
            bucket.evictCachedFile(PATH) { size(64, 64) }
            server.gate?.complete(Unit)
            download.await()

            // Then — the original is cached all the same
            assertEquals(CONTENT, read(assertNotNull(bucket.cachedFile(PATH))))
            client.close()
        }

    @Test
    fun an_ensureCachedFile_running_when_its_object_is_evicted_downloads_it_once_more() = runTest {
        // Given — an ensureCachedFile parked at the server
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        server.gate = CompletableDeferred()
        val ensured = async { bucket.ensureCachedFile(PATH) }
        server.requestArrived.await()

        // When
        bucket.evictCachedFile(PATH)
        server.gate?.complete(Unit)
        val file = ensured.await()

        // Then — the first download was not kept; a second one was
        assertEquals(2, server.requests.size)
        assertEquals(CONTENT, read(file))
        assertEquals(file, bucket.cachedFile(PATH))
        client.close()
    }

    @Test
    fun a_path_that_cannot_be_cached_has_no_copy_to_find_or_evict() = runTest {
        // Given
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.ensureCachedFile(PATH)

        // When / Then — nothing found, nothing thrown, and the cached object untouched
        assertNull(bucket.cachedFile(UNCACHEABLE) { size(64, 64) })
        bucket.evictCachedFile(UNCACHEABLE)
        bucket.evictCachedFile(UNCACHEABLE) { size(64, 64) }
        assertNotNull(bucket.cachedFile(PATH))
        client.close()
    }

    @Test
    fun a_closed_client_refuses_every_one_of_them() = runTest {
        // Given
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        client.close()

        // When / Then
        assertFailsWith<IllegalStateException> { bucket.cachedFile(PATH) { size(64, 64) } }
        assertFailsWith<IllegalStateException> { bucket.evictCachedFile(PATH) }
        assertFailsWith<IllegalStateException> { bucket.evictCachedFile(PATH) { size(64, 64) } }
    }

    private companion object {
        const val BUCKET = "avatars"
        const val OTHER_BUCKET = "documents"
        const val PATH = "u-1/me.png"
        const val NEIGHBOR = "u-1/me.png.bak"
        const val UNCACHEABLE = "../escape.png"
        const val CONTENT = "full-size-image"
    }
}
