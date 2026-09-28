@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.db.DbPathProvider
import io.github.nesfeder.supabase.offline.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.storage.writeBytes
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeStorageDao
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val client = SupabaseOfflineClientImpl(
            supabase = testSupabaseClient(),
            store = buildStore(),
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

    private fun buildStore(): OfflineStore = OfflineStore(
        objectDao = FakeObjectDao(),
        storageDao = FakeStorageDao(),
        mutationDao = FakeMutationDao(),
        dbPathProvider = object : DbPathProvider { override fun dbPath(): Path = "/tmp/test.db".toPath() },
        settings = OfflineCacheSettings(
            maxDbSizeBytes = null,
            evictionBatchSize = 0,
            minRetainedBytes = 0,
        ),
        nowMillis = { 0L },
        dbSizeProvider = { null },
    )

    /** Minimal never-hit Supabase client; `clearAllCache` is local-only and never touches it. */
    private fun testSupabaseClient(): SupabaseClient = createSupabaseClient(
        supabaseUrl = "https://unit-test.supabase.co",
        supabaseKey = "unit-test-anon-key",
    ) {
        httpEngine = MockEngine { respondOk("[]") }
    }
}
