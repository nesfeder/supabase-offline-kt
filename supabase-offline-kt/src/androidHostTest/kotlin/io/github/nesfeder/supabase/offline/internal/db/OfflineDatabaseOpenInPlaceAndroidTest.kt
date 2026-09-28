package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.nesfeder.supabase.offline.testdoubles.RecordingRoomCallback
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Persistence continuity on Android: a `supabase_offline.db` written by an install of the engine's
 * previous home — schema version 2, cached queries, a storage row and unsent writes — must open in
 * place through the library's own database builder, with every row intact.
 *
 * Opening also proves the identity hash compiled into this build equals the shipped one: Room
 * checks `room_master_table` against its generated hash on open and refuses a mismatch. A schema
 * version 1 file must likewise migrate in place, through `MIGRATION_1_2`, keeping its queued writes.
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
    fun a_shipped_schema_version_2_database_opens_in_place_with_every_row() = runTest {
        // Given — the shipped database file, written with the raw driver, never through Room
        directory.mkdirs()
        val dbPath = File(directory, "supabase_offline.db").absolutePath
        ShippedDatabaseFixture.writeTo(dbPath, BundledSQLiteDriver())
        val callback = RecordingRoomCallback()

        // When — opened the way createSupabaseOfflineClient opens it
        val database = getRoomDatabase(getDatabaseBuilder(context, dbPath).addCallback(callback))
        val contents = try {
            database.readShippedFixtureContents()
        } finally {
            database.close()
        }

        // Then — every row reads back; Room neither created nor recreated the file
        assertEquals(SHIPPED_FIXTURE_CONTENTS, contents)
        assertEquals(0, callback.creates, "the existing database must not be created again")
        assertEquals(0, callback.destructiveMigrations, "no destructive recreate may run")
        assertEquals(1, callback.opens)
        assertEquals(ShippedDatabaseFixture.VERSION, ShippedDatabaseFixture.readUserVersion(dbPath, BundledSQLiteDriver()))
        assertEquals(ShippedDatabaseFixture.IDENTITY_HASH, ShippedDatabaseFixture.readIdentityHash(dbPath, BundledSQLiteDriver()))
    }

    @Test
    fun a_shipped_schema_version_1_database_migrates_in_place_keeping_every_row() = runTest {
        // Given — an install that never ran a schema version 2 build, written without Room
        directory.mkdirs()
        val dbPath = File(directory, "supabase_offline.db").absolutePath
        ShippedDatabaseFixture.writeVersion1To(dbPath, BundledSQLiteDriver())
        val callback = RecordingRoomCallback()

        // When — opened the way createSupabaseOfflineClient opens it
        val database = getRoomDatabase(getDatabaseBuilder(context, dbPath).addCallback(callback))
        val contents = try {
            database.readShippedFixtureContents()
        } finally {
            database.close()
        }

        // Then — MIGRATION_1_2 carried every queued write across; nothing was recreated
        assertEquals(SHIPPED_VERSION_1_FIXTURE_CONTENTS_AFTER_MIGRATION, contents)
        assertEquals(0, callback.creates, "the existing database must not be created again")
        assertEquals(0, callback.destructiveMigrations, "no destructive recreate may run")
        assertEquals(1, callback.opens)
        assertEquals(ShippedDatabaseFixture.VERSION, ShippedDatabaseFixture.readUserVersion(dbPath, BundledSQLiteDriver()))
        assertEquals(ShippedDatabaseFixture.IDENTITY_HASH, ShippedDatabaseFixture.readIdentityHash(dbPath, BundledSQLiteDriver()))
    }

    @Test
    fun the_shipped_queue_replays_every_write_exactly_as_it_was_queued() = runTest {
        // Given — the shipped database, opened in place through the library's own builder
        directory.mkdirs()
        val dbPath = File(directory, "supabase_offline.db").absolutePath
        ShippedDatabaseFixture.writeTo(dbPath, BundledSQLiteDriver())
        val database = getRoomDatabase(getDatabaseBuilder(context, dbPath))
        val server = shippedQueueServer()

        // When — the queue replays through a real store over the database's DAOs
        val (synced, pendingAfter) = try {
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
