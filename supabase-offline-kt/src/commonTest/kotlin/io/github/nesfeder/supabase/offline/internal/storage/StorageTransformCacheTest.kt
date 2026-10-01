// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

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
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-transform-${Random.nextLong().toULong()}"
        store = newTestOfflineStore()
        server = RecordingStorageServer()
        server.objects["$BUCKET/$PATH"] = ORIGINAL.encodeToByteArray()
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
    fun a_thumbnail_and_the_full_image_are_cached_separately() = runTest {
        // Given — the list fetches a thumbnail, the profile screen the full image
        val bucket = bucket()
        val thumbnail =
            bucket.downloadAuthenticated(PATH) { transform { size(64, 64) } }.decodeToString()
        val full = bucket.downloadAuthenticated(PATH) {}.decodeToString()

        // When — both are read again offline
        server.online = false
        val offlineThumbnail =
            bucket.downloadAuthenticated(PATH) { transform { size(64, 64) } }.decodeToString()
        val offlineFull = bucket.downloadAuthenticated(PATH) {}.decodeToString()

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
        bucket.downloadPublic(PATH) {}
        bucket.downloadPublic(PATH) { transform { size(32, 32) } }

        // When
        server.online = false
        val offlineFull = bucket.downloadPublic(PATH) {}.decodeToString()

        // Then
        assertEquals(ORIGINAL, offlineFull)
    }

    @Test
    fun two_transforms_of_one_object_are_cached_separately() = runTest {
        // Given
        val bucket = bucket()
        val small =
            bucket.downloadAuthenticated(PATH) { transform { size(32, 32) } }.decodeToString()
        val large =
            bucket.downloadAuthenticated(PATH) { transform { size(256, 256) } }.decodeToString()

        // When
        server.online = false

        // Then
        assertEquals(
            small,
            bucket.downloadAuthenticated(PATH) { transform { size(32, 32) } }.decodeToString(),
        )
        assertEquals(
            large,
            bucket.downloadAuthenticated(PATH) { transform { size(256, 256) } }.decodeToString(),
        )
    }

    @Test
    fun an_untransformed_object_keeps_its_cache_key_and_file_location() = runTest {
        // Given — a thumbnail cached beside it must not disturb the original's entry
        val bucket = bucket()

        // When
        bucket.downloadAuthenticated(PATH) { transform { size(64, 64) } }
        bucket.downloadAuthenticated(PATH) {}

        // Then — the row is keyed "$bucket::$path" and the file sits at
        // <data>/storage/<bucket>/<path>,
        // exactly where installs of earlier versions keep theirs
        val row = assertNotNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals("$BUCKET::$PATH", row.key)
        assertEquals(PATH, row.path)
        assertEquals(
            (dataDirectory / "storage" / BUCKET / "u-1" / "me.png").toString(),
            row.localPath,
        )
        assertEquals(ORIGINAL, FileSystem.SYSTEM.read(row.localPath.toPath()) { readUtf8() })
    }

    @Test
    fun an_object_under_render_image_is_cached_as_the_original() = runTest {
        // Given — an untransformed object whose own path contains "render/image/"
        server.objects["$BUCKET/render/image/cat.png"] = ORIGINAL.encodeToByteArray()

        // When
        bucket().downloadAuthenticated("render/image/cat.png") {}

        // Then
        assertNotNull(
            bucket().cachedFile("render/image/cat.png"),
            "cached as a transformed variant, not as the original",
        )
    }

    @Test
    fun the_original_is_served_offline_when_the_request_could_not_even_be_built() = runTest {
        // Given — cached online, then read through a client whose access-token provider fails
        // offline, as a third-party auth provider's refresh does; supabase-kt throws before it
        // builds the request, so the variant the caller asked for is never observed
        bucket().downloadAuthenticated(PATH) {}
        val tokenFails =
            newTestSupabaseClient(engine = server.engine) {
                accessToken = { throw IOException("token refresh failed: offline") }
                install(Storage) { resumable { cache = MemoryResumableCache() } }
            }
        val offline =
            newTestBucketApi(
                delegate = tokenFails.storage.from(BUCKET),
                baseDirectory = dataDirectory,
                store = store,
            )

        // When
        val original = offline.downloadAuthenticated(PATH) {}.decodeToString()
        val asThumbnail =
            offline.downloadAuthenticated(PATH) { transform { size(64, 64) } }.decodeToString()

        // Then — the original, for both: a full image in place of a thumbnail is what earlier
        // versions served, and a thumbnail is never served in place of the full image
        assertEquals(ORIGINAL, original)
        assertEquals(ORIGINAL, asThumbnail)
    }

    @Test
    fun a_transformed_variant_is_never_served_when_the_request_could_not_be_built() = runTest {
        // Given — only a thumbnail is cached
        bucket().downloadAuthenticated(PATH) { transform { size(64, 64) } }
        val tokenFails =
            newTestSupabaseClient(engine = server.engine) {
                accessToken = { throw IOException("token refresh failed: offline") }
                install(Storage) { resumable { cache = MemoryResumableCache() } }
            }
        val offline =
            newTestBucketApi(
                delegate = tokenFails.storage.from(BUCKET),
                baseDirectory = dataDirectory,
                store = store,
            )

        // When / Then — the full image was asked for; the thumbnail must not stand in for it
        assertFailsWith<IOException> { offline.downloadAuthenticated(PATH) {} }
    }

    private companion object {
        const val BUCKET = "avatars"
        const val PATH = "u-1/me.png"
        const val ORIGINAL = "full-size-image"
    }
}
