// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.offline.testdoubles.RecordingRoomCallback
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Persistence continuity on Android: a `supabase_offline.db` with schema version 1 on disk — cached
 * queries, a storage row and unsent writes, written by the raw driver — opens in place through the
 * library's own database builder, with every row intact, and its queue replays.
 *
 * Opening also proves the identity hash compiled into this build equals the exported one: Room
 * checks `room_master_table` against its generated hash on open and refuses a mismatch.
 */
@RunWith(AndroidJUnit4::class)
class OfflineDatabaseOpenInPlaceAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "open-in-place")

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun a_schema_version_1_database_opens_in_place_with_every_row() = runTest {
        // Given — the database file, written with the raw driver, never through Room
        directory.mkdirs()
        val databasePath = File(directory, "supabase_offline.db").absolutePath
        ShippedDatabaseFixture.writeTo(databasePath, BundledSQLiteDriver())
        val callback = RecordingRoomCallback()

        // When — opened the way createSupabaseOfflineClient opens it
        val database =
            buildOfflineDatabase(
                offlineDatabaseBuilder(context, databasePath).addCallback(callback)
            )
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
            ShippedDatabaseFixture.readUserVersion(databasePath, BundledSQLiteDriver()),
        )
        assertEquals(
            ShippedDatabaseFixture.IDENTITY_HASH,
            ShippedDatabaseFixture.readIdentityHash(databasePath, BundledSQLiteDriver()),
        )
    }

    @Test
    fun the_queue_on_disk_replays_every_write_exactly_as_it_was_queued() = runTest {
        // Given — the database, opened in place through the library's own builder
        directory.mkdirs()
        val databasePath = File(directory, "supabase_offline.db").absolutePath
        ShippedDatabaseFixture.writeTo(databasePath, BundledSQLiteDriver())
        val database = buildOfflineDatabase(offlineDatabaseBuilder(context, databasePath))
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
