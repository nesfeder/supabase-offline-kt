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
 * Schema version 1 to 2 on Android: the bytes 0.1.0 leaves on disk, written by the raw driver,
 * upgrade through Room's own migration — never the destructive fallback — with every queued write
 * kept, each with no base, and the queue then replays as it was queued.
 */
@RunWith(AndroidJUnit4::class)
class OfflineDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "migration-1-2")
    private val databasePath = File(directory, "supabase_offline.db").absolutePath

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun version_1_bytes_upgrade_to_version_2_keeping_every_row_with_no_base() = runTest {
        // Given — 0.1.0's file, written with the raw driver, never through Room
        directory.mkdirs()
        ShippedDatabaseFixture.writeTo(databasePath, BundledSQLiteDriver())
        val callback = RecordingRoomCallback()

        // When — opened through the library's builder, which runs the migration
        val database =
            buildOfflineDatabase(
                offlineDatabaseBuilder(context, databasePath).addCallback(callback)
            )
        val (contents, bases) =
            try {
                database.readShippedFixtureContents() to database.basesBeforeAndAfterOneIsWritten()
            } finally {
                database.close()
            }

        // Then — every row, its fields intact and its bases null, and the new columns hold values
        assertEquals(SHIPPED_FIXTURE_CONTENTS, contents)
        assertEquals(MIGRATED_QUEUE_BASES, bases)
        assertEquals(0, callback.creates, "the existing database must not be created again")
        assertEquals(0, callback.destructiveMigrations, "no destructive recreate may run")
        assertEquals(
            ShippedDatabaseFixture.CURRENT_VERSION,
            ShippedDatabaseFixture.readUserVersion(databasePath, BundledSQLiteDriver()),
        )
        assertEquals(
            ShippedDatabaseFixture.CURRENT_IDENTITY_HASH,
            ShippedDatabaseFixture.readIdentityHash(databasePath, BundledSQLiteDriver()),
        )
    }

    @Test
    fun the_migrated_queue_replays_every_write_as_it_was_queued() = runTest {
        // Given — 0.1.0's file, migrated as it opens
        directory.mkdirs()
        ShippedDatabaseFixture.writeTo(databasePath, BundledSQLiteDriver())
        val database = buildOfflineDatabase(offlineDatabaseBuilder(context, databasePath))
        val server = shippedQueueServer()

        // When
        val (synced, pendingAfter) =
            try {
                database.replayShippedQueue(server) to database.mutationDao().pendingAll()
            } finally {
                database.close()
            }

        // Then — the writes queued before the upgrade go out unguarded, byte for byte as before
        assertEquals(4, synced)
        assertEquals(SHIPPED_QUEUE_REPLAY, server.requests)
        assertEquals(emptyList(), pendingAfter)
    }

    @Test
    fun a_migrated_database_opens_again_without_another_migration() = runTest {
        // Given — migrated once
        directory.mkdirs()
        ShippedDatabaseFixture.writeTo(databasePath, BundledSQLiteDriver())
        buildOfflineDatabase(offlineDatabaseBuilder(context, databasePath)).let { database ->
            try {
                database.mutationDao().countAll()
            } finally {
                database.close()
            }
        }
        val callback = RecordingRoomCallback()

        // When
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

        // Then
        assertEquals(SHIPPED_FIXTURE_CONTENTS, contents)
        assertEquals(0, callback.destructiveMigrations)
        assertEquals(1, callback.opens)
    }
}
