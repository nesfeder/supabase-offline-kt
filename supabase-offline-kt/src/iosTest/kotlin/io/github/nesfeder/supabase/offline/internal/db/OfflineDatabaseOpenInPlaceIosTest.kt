package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.RecordingRoomCallback
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Persistence continuity on iOS: a `supabase_offline.db` written by an install of the engine's
 * previous home — schema version 2, cached queries, a storage row and unsent writes — must open in
 * place through the library's own database builder, with every row intact.
 *
 * Opening also proves the identity hash compiled into this build equals the shipped one: Room
 * checks `room_master_table` against its generated hash on open and refuses a mismatch.
 */
class OfflineDatabaseOpenInPlaceIosTest {

    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
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
        ShippedDatabaseFixture.writeTo(dbPath)
        val callback = RecordingRoomCallback()

        // When — opened the way createSupabaseOfflineClient opens it
        val database = getRoomDatabase(getDatabaseBuilder(dbPath).addCallback(callback))
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
        assertEquals(ShippedDatabaseFixture.VERSION, ShippedDatabaseFixture.readUserVersion(dbPath))
        assertEquals(ShippedDatabaseFixture.IDENTITY_HASH, ShippedDatabaseFixture.readIdentityHash(dbPath))
    }
}
