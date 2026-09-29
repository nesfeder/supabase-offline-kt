package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeDeleteAllFailingMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeDeleteAllFailingObjectDao
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
            assertEquals(emptyList(), store.mutationQueueState.rejections())
            assertEquals(MutationOwners.Known(emptyMap()), store.mutationQueueState.owners())
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
        val (contents, rejections, owners) = try {
            val store = newTestOfflineStore(objectDao = reopened.objectDao(), mutationDao = reopened.mutationDao())
            Triple(reopened.readShippedFixtureContents(), store.mutationQueueState.rejections(), store.mutationQueueState.owners())
        } finally {
            reopened.close()
        }

        // Then
        assertEquals(SHIPPED_FIXTURE_CONTENTS, contents)
        assertEquals(listOf(ON_DISK_REJECTION), rejections)
        assertEquals(MutationOwners.Known(ON_DISK_OWNERS), owners)
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
            val evicted = objectDao.deleteOldestPayloadRows(limit = 10, keptSignatures = emptyList())

            // Then — only the cache rows counted and only they were evicted
            assertEquals(ShippedDatabaseFixture.OBJECT_ROWS.sumOf { row -> row.payload.length.toLong() }, size)
            assertEquals(ShippedDatabaseFixture.OBJECT_ROWS.size, evicted)
            assertEquals(2L, PreviousEngineQueries.reservedRowCount(path))
        } finally {
            database.close()
        }
    }

    /** Room's eviction query passes over the signatures it is told to keep, however old they are. */
    suspend fun roomEvictionKeepsTheListedSignatures(path: String) {
        // Given — the shipped database's cache rows
        ShippedDatabaseFixture.writeTo(path)
        val database = open(path, RecordingRoomCallback())
        try {
            val objectDao = database.objectDao()
            val kept = ShippedDatabaseFixture.OBJECT_ROWS.first()

            // When — every row is evicted but one a live reader shows
            val evicted = objectDao.deleteOldestPayloadRows(limit = 100, keptSignatures = listOf(kept.signature))

            // Then
            assertEquals(ShippedDatabaseFixture.OBJECT_ROWS.size - 1, evicted)
            assertEquals(listOf(kept.signature), objectDao.signaturesForTable(kept.table))
        } finally {
            database.close()
        }
    }

    /**
     * The pending writes of the shipped database, which recorded no owners, are adopted in place by
     * the identity of the first pass that sees them, and keep their order.
     */
    suspend fun anEarlierBuildsQueueIsAdoptedInPlace(path: String) {
        // Given — the shipped database, whose pending writes have no owners
        ShippedDatabaseFixture.writeTo(path)
        val database = open(path, RecordingRoomCallback())
        try {
            val store = newTestOfflineStore(objectDao = database.objectDao(), mutationDao = database.mutationDao())

            // When
            assertTrue(store.adoptOwnerlessMutations("user:user-a"))
        } finally {
            database.close()
        }

        // Then — after reopening, every pending write is the adopting user's, in the same order
        val reopened = open(path, RecordingRoomCallback())
        try {
            val store = newTestOfflineStore(objectDao = reopened.objectDao(), mutationDao = reopened.mutationDao())
            assertEquals(
                MutationOwners.Known(mapOf(1L to "user:user-a", 2L to "user:user-a", 3L to "user:user-a", 4L to "user:user-a")),
                store.mutationQueueState.owners(),
            )
            assertEquals(listOf(1L, 2L, 3L, 4L), store.pendingMutationsOf("user:user-a")?.map { row -> row.id })
            assertEquals(emptyList(), store.pendingMutationsOf("user:user-b")?.map { row -> row.id })
        } finally {
            reopened.close()
        }
    }

    /**
     * The mutation queue's multi-row changes run in one Room transaction: when one fails part-way,
     * none of its writes land — the rejected list, the queue and the owners stay as they were.
     */
    suspend fun aFailedMutationQueueTransactionChangesNothing(path: String) {
        // Given
        ShippedDatabaseFixture.writeTo(path)
        val database = open(path, RecordingRoomCallback())
        try {
            val objectDao = database.objectDao()
            val mutationDao = database.mutationDao()
            val store = newTestOfflineStore(objectDao = objectDao, mutationDao = mutationDao)
            val (rejectedRow, _) = store.mutationQueueState.rejectedRowWith(add = ON_DISK_REJECTION)

            // When — a transaction that writes the rejection and deletes the write, then fails
            val failure = runCatching {
                mutationDao.runInTransaction {
                    objectDao.upsert(rejectedRow)
                    mutationDao.deleteById(1L)
                    error("interrupted")
                }
            }

            // Then
            assertTrue(failure.isFailure)
            assertEquals(null, objectDao.get(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE))
            assertEquals(listOf(1L, 2L, 3L, 4L), mutationDao.pendingAll().map { row -> row.id })
        } finally {
            database.close()
        }
    }

    /**
     * Clearing local data is one transaction: when its mutation delete fails, the owner records,
     * the mutations and the cache are all still there on disk.
     */
    suspend fun aClearWhoseMutationDeleteFailsChangesNothing(path: String) {
        aClearThatFailsPartWayChangesNothing(path) { database ->
            newTestOfflineStore(
                objectDao = database.objectDao(),
                storageDao = database.storageDao(),
                mutationDao = FakeDeleteAllFailingMutationDao(database.mutationDao()),
            )
        }
    }

    /**
     * Clearing local data is one transaction: when its object-cache delete fails, the owner
     * records, the mutations and the cache are all still there on disk.
     */
    suspend fun aClearWhoseObjectCacheDeleteFailsChangesNothing(path: String) {
        aClearThatFailsPartWayChangesNothing(path) { database ->
            newTestOfflineStore(
                objectDao = FakeDeleteAllFailingObjectDao(database.objectDao()),
                storageDao = database.storageDao(),
                mutationDao = database.mutationDao(),
            )
        }
    }

    private suspend fun aClearThatFailsPartWayChangesNothing(path: String, storeFailingIn: (OfflineDatabase) -> OfflineStore) {
        // Given — the shipped database with owner records, and a clear that fails part-way
        ShippedDatabaseFixture.writeTo(path)
        val database = open(path, RecordingRoomCallback())
        val cleared = try {
            database.writeReservedRows()
            runCatching { storeFailingIn(database).clearAll() }
        } finally {
            database.close()
        }

        // When
        val reopened = open(path, RecordingRoomCallback())
        try {
            val store = newTestOfflineStore(objectDao = reopened.objectDao(), mutationDao = reopened.mutationDao())

            // Then — nothing was cleared: every mutation still has the owner it had
            assertTrue(cleared.isFailure)
            assertEquals(SHIPPED_FIXTURE_CONTENTS, reopened.readShippedFixtureContents())
            assertEquals(MutationOwners.Known(ON_DISK_OWNERS), store.mutationQueueState.owners())
            assertEquals(listOf(ON_DISK_REJECTION), store.mutationQueueState.rejections())
        } finally {
            reopened.close()
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
