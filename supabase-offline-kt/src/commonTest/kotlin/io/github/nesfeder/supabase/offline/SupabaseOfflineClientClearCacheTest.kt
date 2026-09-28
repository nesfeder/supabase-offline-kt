@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.storage.writeBytes
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.time.ExperimentalTime

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
        val baseDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-clearcache-test-${Random.nextLong()}"
        createdDirs += baseDir
        // A never-hit client with no plugins: clearAllCache is local-only.
        val client = newTestOfflineClient(
            supabase = newTestSupabaseClient(plugins = {}),
            baseDirectoryProvider = { baseDir },
        )
        val storageFile = baseDir / "storage" / "avatars" / "me.png"
        writeBytes(storageFile, "cached-bytes".encodeToByteArray(), overwrite = true)
        assertTrue(FileSystem.SYSTEM.exists(storageFile), "precondition: storage file on disk")

        // When the entire local cache is cleared (logout / account switch / account deletion)
        client.clearAllCache()

        // Then the on-disk storage tree is gone
        assertFalse(FileSystem.SYSTEM.exists(baseDir / "storage"), "storage directory wiped from disk")
    }
}
