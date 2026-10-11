// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.testdoubles.FakeFailingFileSystem
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * An app that starts setting `dataDirectory` takes the default location's data along: the database
 * with its side files, then the file cache's two directories, which are deleted rather than left
 * behind, beyond a clear's reach, when they cannot move. No file moves when the database stays.
 */
class ConfiguredDirectoryMoveTest {

    private val root: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-configured-move-${Random.nextLong().toULong()}"
    private val default =
        DataLocation(
            root / "databases" / "supabase_offline.db",
            root / "files" / "supabase_offline",
        )
    private val configured =
        DataLocation(root / "configured" / "supabase_offline.db", root / "configured")
    private val engineLogger = EngineLogger(RecordingLogSink(), EngineLogLevel.INFO)

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
    }

    private fun write(path: Path, text: String) {
        path.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
        FileSystem.SYSTEM.write(path) { writeUtf8(text) }
    }

    private fun read(path: Path): String = FileSystem.SYSTEM.read(path) { readUtf8() }

    private fun exists(path: Path): Boolean = FileSystem.SYSTEM.exists(path)

    private fun leaveDataAtTheDefaultLocation() {
        write(default.databasePath, "database")
        write(default.dataDirectory / "storage" / "photos" / "a.jpg", "file")
        write(default.dataDirectory / "storage-hashed" / "0a1b", "hashed")
    }

    @Test
    fun the_file_cache_moves_with_the_database_into_the_configured_directory() {
        // Given
        leaveDataAtTheDefaultLocation()

        // When
        val opened = moveDefaultDatabaseIfUnclaimed(configured, default, engineLogger)

        // Then — nothing of it is left at the default location
        assertEquals(configured.databasePath, opened)
        assertEquals("database", read(configured.databasePath))
        assertEquals("file", read(configured.dataDirectory / "storage" / "photos" / "a.jpg"))
        assertEquals("hashed", read(configured.dataDirectory / "storage-hashed" / "0a1b"))
        assertFalse(exists(default.dataDirectory / "storage"))
        assertFalse(exists(default.dataDirectory / "storage-hashed"))
    }

    @Test
    fun a_file_cache_directory_that_cannot_move_is_deleted_rather_than_left_behind() {
        // Given — the configured directory already has a file cache directory
        leaveDataAtTheDefaultLocation()
        FileSystem.SYSTEM.createDirectories(configured.dataDirectory / "storage")

        // When
        moveDefaultDatabaseIfUnclaimed(configured, default, engineLogger)

        // Then
        assertEquals("database", read(configured.databasePath))
        assertFalse(exists(default.dataDirectory / "storage"))
        assertEquals("hashed", read(configured.dataDirectory / "storage-hashed" / "0a1b"))
    }

    @Test
    fun a_file_cache_that_cannot_be_read_still_leaves_the_moved_database_in_use() {
        // Given — a disk that refuses to look at the default file cache's directories
        leaveDataAtTheDefaultLocation()
        val disk =
            FakeFailingFileSystem().apply {
                failMetadataOf = { path ->
                    path.toString().startsWith((default.dataDirectory / "storage").toString())
                }
            }

        // When
        val opened = moveDefaultDatabaseIfUnclaimed(configured, default, engineLogger, disk)

        // Then — the client opens the database where it now is, not an empty one at the default
        assertEquals(configured.databasePath, opened)
        assertEquals("database", read(configured.databasePath))
        assertFalse(exists(default.databasePath))
    }

    @Test
    fun a_database_that_cannot_move_keeps_its_file_cache_with_it() {
        // Given — a disk that refuses every rename
        leaveDataAtTheDefaultLocation()
        val disk = FakeFailingFileSystem().apply { failMoves = true }

        // When
        val opened = moveDefaultDatabaseIfUnclaimed(configured, default, engineLogger, disk)

        // Then — the default database stays in use, with its files beside it
        assertEquals(default.databasePath, opened)
        assertEquals("database", read(default.databasePath))
        assertEquals("file", read(default.dataDirectory / "storage" / "photos" / "a.jpg"))
        assertFalse(exists(configured.databasePath))
    }

    @Test
    fun no_file_moves_when_the_configured_directory_keeps_its_own_database() {
        // Given — both locations hold a database
        leaveDataAtTheDefaultLocation()
        write(configured.databasePath, "configured database")

        // When
        val opened = moveDefaultDatabaseIfUnclaimed(configured, default, engineLogger)

        // Then — the default data, files included, is left for its own database
        assertEquals(configured.databasePath, opened)
        assertEquals("database", read(default.databasePath))
        assertEquals("file", read(default.dataDirectory / "storage" / "photos" / "a.jpg"))
    }

    @Test
    fun a_configured_directory_inside_the_default_file_cache_loses_nothing() {
        // Given — the app configured a directory inside the default file cache's own
        leaveDataAtTheDefaultLocation()
        val nested = default.dataDirectory / "storage" / "mine"
        val inside = DataLocation(nested / "supabase_offline.db", nested)

        // When
        val opened = moveDefaultDatabaseIfUnclaimed(inside, default, engineLogger)

        // Then — the database moved, and nothing that holds it was moved or deleted
        assertEquals(inside.databasePath, opened)
        assertEquals("database", read(inside.databasePath))
        assertEquals("file", read(default.dataDirectory / "storage" / "photos" / "a.jpg"))
    }
}
