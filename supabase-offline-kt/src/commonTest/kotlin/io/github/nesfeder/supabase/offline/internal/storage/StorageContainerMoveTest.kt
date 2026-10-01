// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * A cache row's recorded file path is never authority. The file an entry lives in is derived from
 * its bucket and object path every time, because the recorded path goes stale: iOS moves an app's
 * container across updates and reinstalls, Android moves `filesDir` to adopted storage, and rows
 * written by earlier versions recorded paths under mappings this version no longer uses.
 */
class StorageContainerMoveTest {

    private lateinit var root: Path
    private lateinit var store: OfflineStore
    private lateinit var delegate: FakeBucketApi

    @BeforeTest
    fun setUp() {
        root =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-container-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(root)
        store = newTestOfflineStore()
        delegate = FakeBucketApi(bucketId = BUCKET)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
    }

    private fun bucketIn(dataDirectory: Path) =
        newTestBucketApi(delegate = delegate, baseDirectory = dataDirectory, store = store)

    /** Caches `a.txt` under container A, then moves the whole container to B, as iOS does. */
    private suspend fun cacheThenMoveContainer(): Path {
        delegate.onDownload = { "BYTES".encodeToByteArray() }
        bucketIn(root / "containerA" / "data").downloadAuthenticated("a.txt") {}
        FileSystem.SYSTEM.atomicMove(root / "containerA", root / "containerB")
        return root / "containerB" / "data"
    }

    @Test
    fun a_cached_copy_is_served_after_the_app_container_moves() = runTest {
        // Given
        val moved = cacheThenMoveContainer()

        // When — offline, in the moved container
        delegate.onDownload = { throw IOException("offline") }
        val bucket = bucketIn(moved)
        val served = bucket.downloadAuthenticated("a.txt") {}.decodeToString()
        val cached = bucket.cachedFile("a.txt")

        // Then — served from, and reported at, the file's new location
        assertEquals("BYTES", served)
        assertEquals((moved / "storage" / BUCKET / "a.txt").toString(), assertNotNull(cached).path)
    }

    @Test
    fun a_row_recorded_under_a_directory_that_no_longer_exists_still_serves_the_derived_file() =
        runTest {
            // Given — an existing install: the row records a path in an old container that is gone,
            // and the file is where the current container keeps it
            val dataDirectory = root / "current" / "data"
            val file = dataDirectory / "storage" / BUCKET / "u-1" / "me.png"
            FileSystem.SYSTEM.createDirectories(file.parent!!)
            FileSystem.SYSTEM.write(file) { writeUtf8("SHIPPED") }
            store.addStorage(
                BUCKET,
                "u-1/me.png",
                "/var/mobile/Containers/Data/Application/OLD-UUID/Documents/supabase_offline/storage/$BUCKET/u-1/me.png"
                    .toPath(),
                7,
                null,
            )

            // When
            delegate.onDownload = { throw IOException("offline") }
            val bucket = bucketIn(dataDirectory)

            // Then
            assertEquals("SHIPPED", bucket.downloadAuthenticated("u-1/me.png") {}.decodeToString())
            assertEquals(file.toString(), assertNotNull(bucket.cachedFile("u-1/me.png")).path)
        }

    @Test
    fun an_invalidation_after_the_container_moves_leaves_no_untracked_file() = runTest {
        // Given
        val moved = cacheThenMoveContainer()

        // When — the object is replaced on the server
        bucketIn(moved).update("a.txt", "NEW".encodeToByteArray()) {}

        // Then — the stale file is gone with its row
        assertFalse(
            FileSystem.SYSTEM.exists(moved / "storage" / BUCKET / "a.txt"),
            "the invalidated file survived, untracked",
        )
        assertNull(store.getStorageByKey(BUCKET, "a.txt"))
    }

    @Test
    fun a_legacy_row_for_a_bucket_prefixed_path_never_serves_another_objects_bytes() = runTest {
        // Given — an earlier version cached "media/x.txt" at storage/media/x.txt, stripping the
        // bucket
        // name, which is also where the object "x.txt" belongs
        val dataDirectory = root / "data"
        val legacyFile = dataDirectory / "storage" / BUCKET / "x.txt"
        FileSystem.SYSTEM.createDirectories(legacyFile.parent!!)
        FileSystem.SYSTEM.write(legacyFile) { writeUtf8("PREFIXED") }
        store.addStorage(BUCKET, "$BUCKET/x.txt", legacyFile, 8, null)

        // When — "x.txt" is downloaded, then "media/x.txt" is read offline
        delegate.onDownload = { path ->
            if (path == "x.txt") "PLAIN".encodeToByteArray() else error("unexpected $path")
        }
        bucketIn(dataDirectory).downloadAuthenticated("x.txt") {}
        delegate.onDownload = { throw IOException("offline") }
        val served = runCatching {
            bucketIn(dataDirectory).downloadAuthenticated("$BUCKET/x.txt") {}.decodeToString()
        }

        // Then — never x.txt's bytes; and x.txt keeps its own copy
        assertNotEquals("PLAIN", served.getOrNull(), "media/x.txt was served x.txt's bytes")
        assertTrue(served.isFailure)
        assertEquals(
            "PLAIN",
            bucketIn(dataDirectory).downloadAuthenticated("x.txt") {}.decodeToString(),
        )
    }

    @Test
    fun a_legacy_row_is_dropped_with_the_file_nothing_else_can_own() = runTest {
        // Given — an earlier version stored a non-ASCII name verbatim; this one hashes it
        val dataDirectory = root / "data"
        val legacyFile = dataDirectory / "storage" / BUCKET / "caf\u00E9.txt"
        FileSystem.SYSTEM.createDirectories(legacyFile.parent!!)
        FileSystem.SYSTEM.write(legacyFile) { writeUtf8("OLD") }
        store.addStorage(BUCKET, "caf\u00E9.txt", legacyFile, 3, null)

        // When
        val cached = bucketIn(dataDirectory).cachedFile("caf\u00E9.txt")

        // Then — a miss, and neither the row nor the file outlives it untracked
        assertNull(cached)
        assertNull(store.getStorageByKey(BUCKET, "caf\u00E9.txt"))
        assertFalse(
            FileSystem.SYSTEM.exists(legacyFile),
            "the legacy file was left behind, untracked",
        )
    }

    @Test
    fun a_legacy_file_another_cached_object_owns_is_kept() = runTest {
        // Given — the bucket-prefix legacy row for "media/x.txt", and "x.txt" cached in that file
        val dataDirectory = root / "data"
        val sharedFile = dataDirectory / "storage" / BUCKET / "x.txt"
        store.addStorage(BUCKET, "$BUCKET/x.txt", sharedFile, 8, null)
        delegate.onDownload = { "PLAIN".encodeToByteArray() }
        bucketIn(dataDirectory).downloadAuthenticated("x.txt") {}

        // When
        val cached = bucketIn(dataDirectory).cachedFile("$BUCKET/x.txt")

        // Then — the legacy row is gone; x.txt keeps its file and its row
        assertNull(cached)
        assertNull(store.getStorageByKey(BUCKET, "$BUCKET/x.txt"))
        assertEquals("PLAIN", FileSystem.SYSTEM.read(sharedFile) { readUtf8() })
        assertNotNull(bucketIn(dataDirectory).cachedFile("x.txt"))
    }

    private companion object {
        const val BUCKET = "media"
    }
}
