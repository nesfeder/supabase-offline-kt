// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.FakeFailingFileSystem
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestFileCache
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * Cache writes are atomic: a download is written to a temporary file beside its target and renamed
 * over it, so a reader — including a later process after a crash — sees the old copy or the new
 * one, never part of either.
 */
class StorageAtomicWriteTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: TestStore
    private lateinit var delegate: FakeBucketApi
    private val fileSystem = FakeFailingFileSystem()

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-atomic-${Random.nextLong().toULong()}"
        store = newTestStore()
        delegate = FakeBucketApi(bucketId = BUCKET)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun bucket(syncFile: (Path) -> Unit = ::syncFileToDisk) =
        newTestBucketApi(
            delegate = delegate,
            baseDirectory = dataDirectory,
            store = store,
            cache =
                newTestFileCache(
                    baseDirectory = dataDirectory,
                    store = store,
                    fileSystem = fileSystem,
                    syncFile = syncFile,
                ),
        )

    private val target: Path
        get() =
            objectFilePathOrNull(dataDirectory, BUCKET, PATH) ?: error("uncacheable fixture path")

    private suspend fun primeWithOldCopy() {
        delegate.onDownload = { OLD }
        bucket().downloadAuthenticated(PATH) {}
    }

    private suspend fun readOffline(): ByteArray {
        delegate.onDownload = { throw IOException("network unreachable") }
        return bucket().downloadAuthenticated(PATH) {}
    }

    @Test
    fun a_write_that_fails_part_way_leaves_the_previous_copy_whole() = runTest {
        // Given — the disk fills up part-way through the new copy
        primeWithOldCopy()
        fileSystem.failWritesAfterBytes = 3
        delegate.onDownload = { NEW }

        // When
        val downloaded = bucket().downloadAuthenticated(PATH) {}

        // Then — the caller has the new bytes; the cache still has the whole old copy and no
        // fragment
        assertContentEquals(NEW, downloaded)
        fileSystem.failWritesAfterBytes = null
        assertContentEquals(OLD, FileSystem.SYSTEM.read(target) { readByteArray() })
        assertContentEquals(OLD, readOffline())
        assertEquals(listOf(target), regularFiles())
    }

    @Test
    fun a_large_write_that_fails_part_way_leaves_the_previous_copy_whole() = runTest {
        // Given — a copy large enough that the disk refuses it while it is still being written,
        // not only when it is closed
        primeWithOldCopy()
        fileSystem.failWritesAfterBytes = 20_000
        val large = ByteArray(100_000) { index -> (index % 97).toByte() }
        delegate.onDownload = { large }

        // When
        val downloaded = bucket().downloadAuthenticated(PATH) {}

        // Then
        assertContentEquals(large, downloaded)
        fileSystem.failWritesAfterBytes = null
        assertContentEquals(OLD, FileSystem.SYSTEM.read(target) { readByteArray() })
        assertEquals(listOf(target), regularFiles())
    }

    @Test
    fun a_new_copy_is_written_beside_the_target_then_renamed_over_it() = runTest {
        // Given
        primeWithOldCopy()
        fileSystem.sinksOpened.clear()
        delegate.onDownload = { NEW }

        // When
        bucket().downloadAuthenticated(PATH) {}

        // Then — the only file written was a temporary one in the target's directory
        val written = fileSystem.sinksOpened.single()
        assertEquals(target.parent, written.parent)
        assertTrue(written != target, "the target was written in place")
        assertContentEquals(NEW, FileSystem.SYSTEM.read(target) { readByteArray() })
        assertEquals(listOf(target), regularFiles())
    }

    @Test
    fun a_new_copy_is_written_through_to_the_disk_before_it_is_renamed_over_the_target() = runTest {
        // Given — the sync and the rename are recorded in order
        val events = mutableListOf<String>()
        fileSystem.onMove = { source, _ -> events += "move ${source.name}" }
        delegate.onDownload = { NEW }

        // When
        bucket(syncFile = { path -> events += "sync ${path.name}" }).downloadAuthenticated(PATH) {}

        // Then — the temporary file was synced, then renamed; a power loss after the rename finds
        // the bytes on the disk
        val temporary = fileSystem.sinksOpened.single()
        assertEquals(listOf("sync ${temporary.name}", "move ${temporary.name}"), events)
        assertContentEquals(NEW, FileSystem.SYSTEM.read(target) { readByteArray() })
    }

    @Test
    fun a_sync_the_platform_refuses_still_caches_the_copy() = runTest {
        // Given — a file system with no sync
        delegate.onDownload = { NEW }

        // When
        val downloaded =
            bucket(syncFile = { throw okio.IOException("not supported") }).downloadAuthenticated(
                PATH
            ) {}

        // Then — cached all the same: the size check on reads covers what a power loss leaves
        assertContentEquals(NEW, downloaded)
        assertContentEquals(NEW, FileSystem.SYSTEM.read(target) { readByteArray() })
        assertContentEquals(NEW, readOffline())
    }

    @Test
    fun a_rename_the_file_system_refuses_leaves_the_previous_copy_and_no_fragment() = runTest {
        // Given
        primeWithOldCopy()
        fileSystem.failMoves = true
        delegate.onDownload = { NEW }

        // When
        val downloaded = bucket().downloadAuthenticated(PATH) {}

        // Then
        assertContentEquals(NEW, downloaded)
        assertContentEquals(OLD, FileSystem.SYSTEM.read(target) { readByteArray() })
        assertEquals(listOf(target), regularFiles())
    }

    @Test
    fun a_temporary_file_a_crash_left_behind_is_swept_and_never_served() = runTest {
        // Given — a process died mid-download more than a day ago, leaving a temporary file beside
        // the target
        primeWithOldCopy()
        val orphan = target.parent!! / ".supabase-offline-download-dead.tmp"
        FileSystem.SYSTEM.write(orphan) { write(NEW.copyOf(3)) }
        markFileUsed(orphan, Clock.System.now().toEpochMilliseconds() - 25L * 60L * 60L * 1000L)

        // When — this process's first download through the cache
        delegate.onDownload = { "other".encodeToByteArray() }
        bucket().downloadAuthenticated("u-1/other.png") {}

        // Then — the orphan is gone and the cached copy was never replaced by it
        assertTrue(!FileSystem.SYSTEM.exists(orphan), "the orphaned temporary file survived")
        assertContentEquals(OLD, readOffline())
    }

    private fun regularFiles(): List<Path> =
        FileSystem.SYSTEM.listRecursively(dataDirectory)
            .filter { FileSystem.SYSTEM.metadata(it).isRegularFile && it.name != "other.png" }
            .toList()

    private companion object {
        const val BUCKET = "avatars"
        const val PATH = "u-1/me.png"
        val OLD = "old-avatar-bytes".encodeToByteArray()
        val NEW = "new-avatar-bytes-that-are-longer".encodeToByteArray()
    }
}
