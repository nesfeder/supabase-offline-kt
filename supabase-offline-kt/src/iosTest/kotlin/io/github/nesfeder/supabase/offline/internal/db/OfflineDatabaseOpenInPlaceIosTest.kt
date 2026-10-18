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
 * Persistence continuity on iOS: a `supabase_offline.db` with schema version 1 on disk — cached
 * queries, a storage row and unsent writes, written by the raw driver — opens in place through the
 * library's own database builder, migrated to this build's schema with every row intact, and its
 * queue replays.
 */
class OfflineDatabaseOpenInPlaceIosTest {

    private val directory =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-open-in-place-${Random.nextLong()}"

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    @Test
    fun a_schema_version_1_database_opens_in_place_with_every_row() = runTest {
        // Given — the database file, written with the raw driver, never through Room
        FileSystem.SYSTEM.createDirectories(directory)
        val databasePath = (directory / "supabase_offline.db").toString()
        ShippedDatabaseFixture.writeTo(databasePath, defaultSQLiteDriver())
        val callback = RecordingRoomCallback()

        // When — opened the way createSupabaseOfflineClient opens it
        val database =
            buildOfflineDatabase(offlineDatabaseBuilder(databasePath).addCallback(callback))
        val contents =
            try {
                database.readShippedFixtureContents()
            } finally {
                database.close()
            }

        // Then — every row reads back; Room neither created nor recreated the file
        assertEquals(SHIPPED_FIXTURE_CONTENTS, contents)
        assertEquals(0, callback.creates, "the existing database must not be created again")
        assertEquals(0, callback.destructiveMigrations, "no destructive recreate may run")
        assertEquals(1, callback.opens)
        // Migrated to this build's schema in place, with no row lost
        assertEquals(
            ShippedDatabaseFixture.CURRENT_VERSION,
            ShippedDatabaseFixture.readUserVersion(databasePath, defaultSQLiteDriver()),
        )
        assertEquals(
            ShippedDatabaseFixture.CURRENT_IDENTITY_HASH,
            ShippedDatabaseFixture.readIdentityHash(databasePath, defaultSQLiteDriver()),
        )
    }

    @Test
    fun a_migrated_queue_keeps_every_write_with_no_base_until_one_is_written() = runTest {
        // Given — 0.1.0's file, written with the raw driver
        FileSystem.SYSTEM.createDirectories(directory)
        val databasePath = (directory / "supabase_offline.db").toString()
        ShippedDatabaseFixture.writeTo(databasePath, defaultSQLiteDriver())
        val callback = RecordingRoomCallback()

        // When — migrated as it opens
        val database =
            buildOfflineDatabase(offlineDatabaseBuilder(databasePath).addCallback(callback))
        val bases =
            try {
                database.basesBeforeAndAfterOneIsWritten()
            } finally {
                database.close()
            }

        // Then
        assertEquals(MIGRATED_QUEUE_BASES, bases)
        assertEquals(0, callback.destructiveMigrations, "no destructive recreate may run")
    }

    @Test
    fun the_queue_on_disk_replays_every_write_exactly_as_it_was_queued() = runTest {
        // Given — the database, opened in place through the library's own builder
        FileSystem.SYSTEM.createDirectories(directory)
        val databasePath = (directory / "supabase_offline.db").toString()
        ShippedDatabaseFixture.writeTo(databasePath, defaultSQLiteDriver())
        val database = buildOfflineDatabase(offlineDatabaseBuilder(databasePath))
        val server = shippedQueueServer()

        // When — the queue replays through a real store over the database's DAOs
        val (synced, pendingAfter) =
            try {
                database.replayShippedQueue(server) to database.mutationDao().pendingAll()
            } finally {
                database.close()
            }

        // Then — each queued write reaches the server with its filter and options intact
        assertEquals(4, synced)
        assertEquals(SHIPPED_QUEUE_REPLAY, server.requests)
        assertEquals(emptyList(), pendingAfter)
    }
}
