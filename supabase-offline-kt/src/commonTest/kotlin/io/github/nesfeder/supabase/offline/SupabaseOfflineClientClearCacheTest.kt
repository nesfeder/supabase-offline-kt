// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.RecordingCachedFileDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Verifies [OfflineClient.clearLocalData] wipes downloaded storage files from disk (not just the
 * Room metadata rows), so identity-clearing flows leave no cached objects behind.
 *
 * Runs against the real okio [FileSystem.SYSTEM] with a per-test temp base dir; the Supabase client
 * is a never-hit [MockEngine] stub since `clearLocalData` is a local-only operation.
 */
class SupabaseOfflineClientClearCacheTest {

    private val createdDirs = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        createdDirs.forEach { FileSystem.SYSTEM.deleteRecursively(it, mustExist = false) }
    }

    @Test
    fun a_clear_cancelled_under_its_locks_still_leaves_the_log_to_the_background() = runTest {
        // Given — a read holds the write-ahead log, and the clear's caller is cancelled while the
        // rewrite runs under the clear's locks
        val held = AtomicBoolean(true)
        val emptied = AtomicBoolean(false)
        val clear = CompletableDeferred<Job>()
        val client =
            newTestOfflineClient(
                scrubDatabase = {
                    clear.await().cancel()
                    false
                },
                emptyWriteAheadLog = {
                    emptied.store(!held.load())
                    emptied.load()
                },
            )
        val job =
            launch(Dispatchers.Default, start = CoroutineStart.LAZY) { client.clearLocalData() }
        clear.complete(job)
        job.start()
        job.join()

        // When — the read ends
        held.store(false)
        val emptiedInTime =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(10.seconds) {
                    while (!emptied.load()) delay(10.milliseconds)
                    true
                }
            }

        // Then — the background emptied it, though the clear was cancelled
        assertTrue(job.isCancelled, "the clear was not cancelled")
        assertEquals(true, emptiedInTime, "nothing emptied the log after the cancelled clear")
        client.close()
    }

    @Test
    fun clearLocalData_deletes_the_storage_directory_from_disk() = runTest {
        // Given a client whose offline cache has a downloaded storage file on disk
        val baseDir =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-clearcache-test-${Random.nextLong()}"
        createdDirs += baseDir
        // A never-hit client with no plugins: clearLocalData is local-only.
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
        client.clearLocalData()

        // Then the on-disk storage tree is gone
        assertFalse(
            FileSystem.SYSTEM.exists(baseDir / "storage"),
            "storage directory wiped from disk",
        )
    }

    @Test
    fun clearing_deletes_every_row_once_and_scrubs_the_database_after() = runTest {
        // Given — a client with a cached storage row, whose database scrub records what it found
        val cachedFileDao = RecordingCachedFileDao()
        val store = newTestStore(cachedFileDao = cachedFileDao)
        store.addStorage("avatars", "me.png", "/tmp/me.png".toPath(), 3, null)
        var rowsLeftAtScrub: Int? = null
        val client =
            newTestOfflineClient(
                supabase = newTestSupabaseClient(plugins = {}),
                cachedFileDao = cachedFileDao,
                scrubDatabase = {
                    rowsLeftAtScrub = cachedFileDao.all().size
                    true
                },
            )

        // When
        client.clearLocalData()

        // Then — one clear, not one per lock holder, and the scrub ran after its deletes
        assertEquals(1, cachedFileDao.clears)
        assertEquals(0, rowsLeftAtScrub, "the scrub ran before the rows were deleted")
    }
}
