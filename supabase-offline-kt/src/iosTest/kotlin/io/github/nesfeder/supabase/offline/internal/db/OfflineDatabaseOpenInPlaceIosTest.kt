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
 * Persistence continuity on iOS: a `supabase_offline.db` written by an install of the engine's
 * previous home — schema version 2, cached queries, a storage row and unsent writes — must open in
 * place through the library's own database builder, with every row intact.
 *
 * Opening also proves the identity hash compiled into this build equals the shipped one: Room
 * checks `room_master_table` against its generated hash on open and refuses a mismatch. A schema
 * version 1 file must likewise migrate in place, through `MIGRATION_1_2`, keeping its queued
 * writes.
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
    fun a_shipped_schema_version_2_database_opens_in_place_with_every_row() = runTest {
        // Given — the shipped database file, written with the raw driver, never through Room
        FileSystem.SYSTEM.createDirectories(directory)
        val dbPath = (directory / "supabase_offline.db").toString()
        ShippedDatabaseFixture.writeTo(dbPath, defaultSQLiteDriver())
        val callback = RecordingRoomCallback()

        // When — opened the way createSupabaseOfflineClient opens it
        val database = getRoomDatabase(getDatabaseBuilder(dbPath).addCallback(callback))
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
        assertEquals(
            ShippedDatabaseFixture.VERSION,
            ShippedDatabaseFixture.readUserVersion(dbPath, defaultSQLiteDriver()),
        )
        assertEquals(
            ShippedDatabaseFixture.IDENTITY_HASH,
            ShippedDatabaseFixture.readIdentityHash(dbPath, defaultSQLiteDriver()),
        )
    }

    @Test
    fun a_shipped_schema_version_1_database_migrates_in_place_keeping_every_row() = runTest {
        // Given — an install that never ran a schema version 2 build, written without Room
        FileSystem.SYSTEM.createDirectories(directory)
        val dbPath = (directory / "supabase_offline.db").toString()
        ShippedDatabaseFixture.writeVersion1To(dbPath, defaultSQLiteDriver())
        val callback = RecordingRoomCallback()

        // When — opened the way createSupabaseOfflineClient opens it
        val database = getRoomDatabase(getDatabaseBuilder(dbPath).addCallback(callback))
        val contents =
            try {
                database.readShippedFixtureContents()
            } finally {
                database.close()
            }

        // Then — MIGRATION_1_2 carried every queued write across; nothing was recreated
        assertEquals(SHIPPED_VERSION_1_FIXTURE_CONTENTS_AFTER_MIGRATION, contents)
        assertEquals(0, callback.creates, "the existing database must not be created again")
        assertEquals(0, callback.destructiveMigrations, "no destructive recreate may run")
        assertEquals(1, callback.opens)
        assertEquals(
            ShippedDatabaseFixture.VERSION,
            ShippedDatabaseFixture.readUserVersion(dbPath, defaultSQLiteDriver()),
        )
        assertEquals(
            ShippedDatabaseFixture.IDENTITY_HASH,
            ShippedDatabaseFixture.readIdentityHash(dbPath, defaultSQLiteDriver()),
        )
    }

    @Test
    fun the_shipped_queue_replays_every_write_exactly_as_it_was_queued() = runTest {
        // Given — the shipped database, opened in place through the library's own builder
        FileSystem.SYSTEM.createDirectories(directory)
        val dbPath = (directory / "supabase_offline.db").toString()
        ShippedDatabaseFixture.writeTo(dbPath, defaultSQLiteDriver())
        val database = getRoomDatabase(getDatabaseBuilder(dbPath))
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
