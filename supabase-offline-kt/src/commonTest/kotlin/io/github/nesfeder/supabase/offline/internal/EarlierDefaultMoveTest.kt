// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.internal.db.OpenDatabaseRegistry
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * Moving the data an earlier default location kept to the current one: the database with its side
 * files, then the file cache, which is deleted rather than left behind when it cannot move.
 */
class EarlierDefaultMoveTest {

    private val root: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-earlier-move-${Random.nextLong().toULong()}"
    private val earlier =
        DataLocation(root / "documents" / "supabase_offline.db", root / "documents" / "files")
    private val current =
        DataLocation(root / "support" / "supabase_offline.db", root / "support" / "files")
    private val logs = RecordingLogSink()
    private val engineLogger = EngineLogger(logs, EngineLogLevel.INFO)

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
    }

    private fun write(path: Path, text: String) {
        path.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
        FileSystem.SYSTEM.write(path) { writeUtf8(text) }
    }

    private fun read(path: Path): String = FileSystem.SYSTEM.read(path) { readUtf8() }

    private fun leaveDataAtTheEarlierLocation() {
        write(earlier.databasePath, "database")
        write(root / "documents" / "supabase_offline.db-wal", "log")
        write(earlier.dataDirectory / "storage" / "a.jpg", "file")
    }

    @Test
    fun the_database_its_log_and_the_file_cache_move_to_the_current_location() {
        // Given
        leaveDataAtTheEarlierLocation()

        // When
        val opened = moveEarlierDefaultIfUnclaimed(current, earlier, engineLogger)

        // Then
        assertEquals(current, opened)
        assertEquals("database", read(current.databasePath))
        assertEquals("log", read(root / "support" / "supabase_offline.db-wal"))
        assertEquals("file", read(current.dataDirectory / "storage" / "a.jpg"))
        assertFalse(FileSystem.SYSTEM.exists(root / "documents" / "supabase_offline.db-wal"))
        assertFalse(FileSystem.SYSTEM.exists(earlier.databasePath))
        assertFalse(FileSystem.SYSTEM.exists(earlier.dataDirectory))
    }

    @Test
    fun a_file_cache_that_cannot_move_is_deleted_rather_than_left_to_outlive_a_clear() {
        // Given — the current location already has a file cache directory
        leaveDataAtTheEarlierLocation()
        FileSystem.SYSTEM.createDirectories(current.dataDirectory)

        // When
        val opened = moveEarlierDefaultIfUnclaimed(current, earlier, engineLogger)

        // Then — the database moved, and the earlier files are gone
        assertEquals(current, opened)
        assertEquals("database", read(current.databasePath))
        assertFalse(FileSystem.SYSTEM.exists(earlier.dataDirectory))
    }

    @Test
    fun nothing_moves_when_the_current_location_has_a_database_and_the_app_is_told() {
        // Given
        leaveDataAtTheEarlierLocation()
        write(current.databasePath, "current")

        // When
        val opened = moveEarlierDefaultIfUnclaimed(current, earlier, engineLogger)

        // Then
        assertEquals(current, opened)
        assertEquals("current", read(current.databasePath))
        assertEquals("database", read(earlier.databasePath))
        assertTrue(logs.entriesAt(EngineLogLevel.WARNING).isNotEmpty())
    }

    @Test
    fun a_database_another_client_holds_stays_where_it_is() {
        // Given
        leaveDataAtTheEarlierLocation()
        val claim = OpenDatabaseRegistry.register(earlier.databasePath)

        // When
        val opened =
            try {
                moveEarlierDefaultIfUnclaimed(current, earlier, engineLogger)
            } finally {
                claim.release()
            }

        // Then
        assertEquals(earlier, opened)
        assertEquals("database", read(earlier.databasePath))
        assertFalse(FileSystem.SYSTEM.exists(current.databasePath))
    }

    @Test
    fun a_database_that_cannot_move_stays_in_use_where_it_is() {
        // Given — a directory that is not empty where the database's log would move to
        leaveDataAtTheEarlierLocation()
        write(root / "support" / "supabase_offline.db-wal" / "taken", "in the way")

        // When
        val opened = moveEarlierDefaultIfUnclaimed(current, earlier, engineLogger)

        // Then — the earlier location is opened, whole
        assertEquals(earlier, opened)
        assertEquals("database", read(earlier.databasePath))
        assertEquals("log", read(root / "documents" / "supabase_offline.db-wal"))
        assertEquals("file", read(earlier.dataDirectory / "storage" / "a.jpg"))
        assertTrue(logs.entriesAt(EngineLogLevel.WARNING).isNotEmpty())
    }

    @Test
    fun a_move_that_fails_part_way_puts_back_the_log_it_had_moved() {
        // Given — a log and an index beside the database, and the index's destination taken
        leaveDataAtTheEarlierLocation()
        write(root / "documents" / "supabase_offline.db-shm", "index")
        write(root / "support" / "supabase_offline.db-shm" / "taken", "in the way")

        // When — the log moves first, then the index cannot
        val opened = moveEarlierDefaultIfUnclaimed(current, earlier, engineLogger)

        // Then — the earlier database is opened with its log back beside it, holding its newest
        // writes, and nothing of it is left at the current location
        assertEquals(earlier, opened)
        assertEquals("log", read(root / "documents" / "supabase_offline.db-wal"))
        assertEquals("index", read(root / "documents" / "supabase_offline.db-shm"))
        assertEquals("database", read(earlier.databasePath))
        assertFalse(FileSystem.SYSTEM.exists(root / "support" / "supabase_offline.db-wal"))
        assertFalse(FileSystem.SYSTEM.exists(current.databasePath))
    }

    @Test
    fun with_nothing_at_the_earlier_location_the_current_one_is_opened() {
        // Given — nothing anywhere

        // When
        val opened = moveEarlierDefaultIfUnclaimed(current, earlier, engineLogger)

        // Then
        assertEquals(current, opened)
        assertTrue(logs.entriesAt(EngineLogLevel.WARNING).isEmpty())
    }
}
