package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.RecordingRoomCallback
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reserved-row and queue-order scenarios both platform test classes run against a real Room
 * database on disk. Only opening the database differs by platform, so it is passed in.
 *
 * @param open Opens the file at the given path through the library's own database builder, with
 *   the given callback added.
 */
internal class ReservedRowsDatabaseScenarios(
    private val open: (path: String, callback: RecordingRoomCallback) -> OfflineDatabase,
) {

    /**
     * An older build of the engine opens a database that holds reserved rows: its table reads never
     * see them, its size and eviction queries treat them as ordinary rows, and nothing fails. This
     * build then reopens the file in place with every app row intact.
     */
    suspend fun anOlderBuildReadsAroundReservedRows(path: String) {
        // Given — the shipped database, with this build's reserved rows added
        ShippedDatabaseFixture.writeTo(path)
        val writer = open(path, RecordingRoomCallback())
        try {
            writer.writeReservedRows()
        } finally {
            writer.close()
        }

        // When — the older build's own queries run over it
        val appRows = PreviousEngineQueries.signaturesForTable(path, "todos")
        val sizeWithReservedRows = PreviousEngineQueries.totalPayloadBytes(path)
        PreviousEngineQueries.deleteOldest(path, limit = 2)

        // Then — table reads saw only app rows; its eviction took the reserved rows, being oldest
        assertEquals(ShippedDatabaseFixture.OBJECT_ROWS.map { row -> row.signature }.toSet(), appRows.toSet())
        assertEquals(ShippedDatabaseFixture.OBJECT_ROWS.size, appRows.size)
        assertTrue(sizeWithReservedRows > ShippedDatabaseFixture.OBJECT_ROWS.sumOf { row -> row.payload.length.toLong() })
        assertEquals(0L, PreviousEngineQueries.reservedRowCount(path))

        // And — this build reopens it in place, and reads the now-missing rows as nothing recorded
        val callback = RecordingRoomCallback()
        val reopened = open(path, callback)
        try {
            assertEquals(SHIPPED_FIXTURE_CONTENTS, reopened.readShippedFixtureContents())
            val store = newTestOfflineStore(objectDao = reopened.objectDao(), mutationDao = reopened.mutationDao())
            assertEquals(emptyList(), store.outboxState.rejections())
        } finally {
            reopened.close()
        }
        assertEquals(0, callback.creates)
        assertEquals(0, callback.destructiveMigrations)
        assertEquals(ShippedDatabaseFixture.IDENTITY_HASH, ShippedDatabaseFixture.readIdentityHash(path))
    }

    /** Reserved rows survive a close and reopen, and leave every shipped row as it was. */
    suspend fun reservedRowsPersistAcrossReopening(path: String) {
        // Given
        ShippedDatabaseFixture.writeTo(path)
        val writer = open(path, RecordingRoomCallback())
        try {
            writer.writeReservedRows()
        } finally {
            writer.close()
        }

        // When
        val callback = RecordingRoomCallback()
        val reopened = open(path, callback)
        val (contents, rejections, owner) = try {
            val store = newTestOfflineStore(objectDao = reopened.objectDao(), mutationDao = reopened.mutationDao())
            Triple(reopened.readShippedFixtureContents(), store.outboxState.rejections(), store.outboxState.queueOwner())
        } finally {
            reopened.close()
        }

        // Then
        assertEquals(SHIPPED_FIXTURE_CONTENTS, contents)
        assertEquals(listOf(ON_DISK_REJECTION), rejections)
        assertEquals("user:user-a", owner)
        assertEquals(0, callback.creates)
        assertEquals(0, callback.destructiveMigrations)
        assertEquals(ShippedDatabaseFixture.VERSION, ShippedDatabaseFixture.readUserVersion(path))
        assertEquals(ShippedDatabaseFixture.IDENTITY_HASH, ShippedDatabaseFixture.readIdentityHash(path))
    }

    /** This build's size and eviction queries leave reserved rows out, on real SQLite. */
    suspend fun roomNeverEvictsOrCountsReservedRows(path: String) {
        // Given — reserved rows stamped older than every cached payload
        ShippedDatabaseFixture.writeTo(path)
        val database = open(path, RecordingRoomCallback())
        try {
            database.writeReservedRows()
            val objectDao = database.objectDao()

            // When
            val size = objectDao.totalPayloadBytes()
            val evicted = objectDao.deleteOldestPayloadRows(limit = 10)

            // Then — only the cache rows counted and only they were evicted
            assertEquals(ShippedDatabaseFixture.OBJECT_ROWS.sumOf { row -> row.payload.length.toLong() }, size)
            assertEquals(ShippedDatabaseFixture.OBJECT_ROWS.size, evicted)
            assertEquals(2L, PreviousEngineQueries.reservedRowCount(path))
        } finally {
            database.close()
        }
    }

    /** Room replays pending writes by id, whatever their wall-clock times say. */
    suspend fun roomReplaysPendingWritesInInsertionOrder(path: String) {
        // Given — a write added after the shipped ones, stamped with a clock set back
        ShippedDatabaseFixture.writeTo(path)
        PreviousEngineQueries.insertWriteWithEarlierClock(path, id = 10)

        // When
        val database = open(path, RecordingRoomCallback())
        val ids = try {
            database.mutationDao().pendingAll().map { row -> row.id }
        } finally {
            database.close()
        }

        // Then — insertion order; the older build's clock order would have put it first
        assertEquals(listOf(1L, 2L, 3L, 4L, 10L), ids)
        assertEquals(10L, PreviousEngineQueries.pendingIds(path).first())
    }
}
