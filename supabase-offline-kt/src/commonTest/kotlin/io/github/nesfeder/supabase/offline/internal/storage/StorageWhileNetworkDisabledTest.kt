// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * While the app has turned the library's network access off, the offline bucket sends nothing: a
 * download is answered from the file cache, and every call that must reach Storage — an upload, a
 * delete, a move — throws `NetworkDisabledException` at once and leaves the cached copies as they
 * were.
 */
class StorageWhileNetworkDisabledTest {

    private val baseDirectory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-disabled-${Random.nextLong()}"
    private val networkAccess = NetworkAccess()
    private var downloads = 0
    private val fake =
        FakeBucketApi(
            bucketId = BUCKET,
            onDownload = {
                downloads++
                PAYLOAD
            },
        )
    private val bucket =
        newTestBucketApi(
            delegate = fake,
            baseDirectory = baseDirectory,
            networkAccess = networkAccess,
        )

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(baseDirectory, mustExist = false)
    }

    @Test
    fun a_download_while_access_is_off_is_answered_from_the_file_cache() = runTest {
        // Given — a file downloaded, and cached, while access was on
        bucket.downloadAuthenticated(OBJECT_PATH)

        // When
        networkAccess.disable()
        val bytes = bucket.downloadAuthenticated(OBJECT_PATH)
        val cached = bucket.ensureCachedFile(OBJECT_PATH)

        // Then — the cached bytes, and the server asked once only
        assertContentEquals(PAYLOAD, bytes)
        assertNotNull(cached)
        assertEquals(1, downloads)
    }

    @Test
    fun a_download_of_a_file_never_cached_while_access_is_off_throws() = runTest {
        // Given
        networkAccess.disable()

        // When / Then
        assertFailsWith<NetworkDisabledException> { bucket.downloadAuthenticated(OBJECT_PATH) }
        assertFailsWith<NetworkDisabledException> { bucket.ensureCachedFile(OBJECT_PATH) }
        assertEquals(0, downloads)
    }

    @Test
    fun uploads_and_deletes_while_access_is_off_throw_and_leave_the_cache_alone() = runTest {
        // Given — a cached file, then access off
        bucket.downloadAuthenticated(OBJECT_PATH)
        networkAccess.disable()

        // When
        assertFailsWith<NetworkDisabledException> {
            bucket.upload(OBJECT_PATH, byteArrayOf(9)) { upsert = true }
        }
        assertFailsWith<NetworkDisabledException> { bucket.delete(OBJECT_PATH) }
        assertFailsWith<NetworkDisabledException> { bucket.move(OBJECT_PATH, "elsewhere.png") }

        // Then — nothing reached Storage, and the cached copy still answers
        assertEquals(emptyList(), fake.uploads)
        assertEquals(emptyList(), fake.deletedPaths)
        assertEquals(emptyList(), fake.moves)
        assertNotNull(bucket.cachedFile(OBJECT_PATH))
    }

    private companion object {
        const val BUCKET = "avatars"
        const val OBJECT_PATH = "user/avatar.png"
        val PAYLOAD = byteArrayOf(1, 2, 3)
    }
}
