// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Verifies [SupabaseOfflineClientImpl.clearAllCache] wipes downloaded storage files from disk (not
 * just the Room metadata rows), so identity-clearing flows leave no cached objects behind.
 *
 * Runs against the real okio [FileSystem.SYSTEM] with a per-test temp base dir; the Supabase client
 * is a never-hit [MockEngine] stub since `clearAllCache` is a local-only operation.
 */
class SupabaseOfflineClientClearCacheTest {

    private val createdDirs = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        createdDirs.forEach { FileSystem.SYSTEM.deleteRecursively(it, mustExist = false) }
    }

    @Test
    fun clearAllCache_wipes_storage_directory_from_disk() = runTest {
        // Given a client whose offline cache has a downloaded storage file on disk
        val baseDir =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-clearcache-test-${Random.nextLong()}"
        createdDirs += baseDir
        // A never-hit client with no plugins: clearAllCache is local-only.
        val client =
            newTestOfflineClient(
                supabase = newTestSupabaseClient(plugins = {}),
                baseDirectoryProvider = { baseDir },
            )
        val storageFile = baseDir / "storage" / "avatars" / "me.png"
        FileSystem.SYSTEM.createDirectories(storageFile.parent!!)
        FileSystem.SYSTEM.write(storageFile) { write("cached-bytes".encodeToByteArray()) }
        assertTrue(FileSystem.SYSTEM.exists(storageFile), "precondition: storage file on disk")

        // When the entire local cache is cleared (logout / account switch / account deletion)
        client.clearAllCache()

        // Then the on-disk storage tree is gone
        assertFalse(
            FileSystem.SYSTEM.exists(baseDir / "storage"),
            "storage directory wiped from disk",
        )
    }

    @Test
    fun clearing_deletes_every_row_once_and_scrubs_the_database_after() = runTest {
        // Given — a client with a cached storage row, whose database scrub records what it found
        val storageDao = RecordingStorageDao()
        val store = newTestOfflineStore(storageDao = storageDao)
        store.addStorage("avatars", "me.png", "/tmp/me.png".toPath(), 3, null)
        var rowsLeftAtScrub: Int? = null
        val client =
            newTestOfflineClient(
                supabase = newTestSupabaseClient(plugins = {}),
                store = store,
                scrubDatabase = { rowsLeftAtScrub = storageDao.all().size },
            )

        // When
        client.clearAllCache()

        // Then — one clear, not one per lock holder, and the scrub ran after its deletes
        assertEquals(1, storageDao.clears)
        assertEquals(0, rowsLeftAtScrub, "the scrub ran before the rows were deleted")
    }
}
