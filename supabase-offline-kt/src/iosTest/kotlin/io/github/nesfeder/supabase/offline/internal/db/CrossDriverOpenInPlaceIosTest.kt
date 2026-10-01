// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.RecordingRoomCallback
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.SYSTEM

/**
 * A database file written by the other SQLite library opens in place under this build's.
 *
 * The bundled build opens a file the system SQLite wrote, and the system build (run with
 * `-Psupabase.offline.iosSqlite=system`) opens one the bundled SQLite wrote, so between the two
 * runs both directions are covered. An app that moves between the two libraries keeps its cache
 * and, more importantly, its unsent writes.
 */
class CrossDriverOpenInPlaceIosTest {

    private val directory =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-cross-driver-${Random.nextLong()}"
    private val databaseFile = directory / "supabase_offline.db"

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    private fun writeOtherDriversFile() {
        FileSystem.SYSTEM.createDirectories(directory)
        FileSystem.SYSTEM.write(databaseFile) { write(SqliteVariant.otherDriverDatabase.bytes()) }
    }

    @Test
    fun a_database_written_by_the_other_sqlite_opens_in_place_with_every_row() = runTest {
        // Given — the other library's file; its header says which SQLite last wrote it
        writeOtherDriversFile()
        val header = FileSystem.SYSTEM.read(databaseFile) { readByteArray(100) }
        val lastWriter =
            (96 until 100).fold(0) { number, index ->
                (number shl 8) or (header[index].toInt() and 0xff)
            }
        assertEquals(SqliteVariant.otherDriverDatabase.sqliteVersionNumber, lastWriter)
        val callback = RecordingRoomCallback()

        // When — opened the way createSupabaseOfflineClient opens it, with this build's SQLite
        val database =
            buildOfflineDatabase(
                offlineDatabaseBuilder(databaseFile.toString()).addCallback(callback)
            )
        val contents =
            try {
                database.readShippedFixtureContents()
            } finally {
                database.close()
            }

        // Then — every row, nothing recreated
        assertEquals(CROSS_DRIVER_CONTENTS, contents)
        assertEquals(0, callback.creates, "the existing database must not be created again")
        assertEquals(0, callback.destructiveMigrations, "no destructive recreate may run")
        assertEquals(1, callback.opens)
    }

    @Test
    fun the_other_sqlites_queue_replays_every_write_exactly_as_it_was_queued() = runTest {
        // Given
        writeOtherDriversFile()
        val database = buildOfflineDatabase(offlineDatabaseBuilder(databaseFile.toString()))
        val server = shippedQueueServer()

        // When
        val (synced, pendingAfter) =
            try {
                database.replayShippedQueue(server) to database.mutationDao().pendingAll()
            } finally {
                database.close()
            }

        // Then
        assertEquals(4, synced)
        assertEquals(SHIPPED_QUEUE_REPLAY, server.requests)
        assertEquals(emptyList(), pendingAfter)
    }
}
