// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
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
 * @param open Opens the file at the given path through the library's own database builder, with the
 *   given callback added.
 */
internal class ReservedRowsDatabaseScenarios(
    private val open: (path: String, callback: RecordingRoomCallback) -> OfflineDatabase
) {

    /**
     * The queue's state — owners in the rows, and the reserved rows — survives a close and reopen,
     * and leaves every fixture row as it was.
     */
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
        val (contents, rejections, owners) =
            try {
                val store =
                    newTestOfflineStore(
                        objectDao = reopened.objectDao(),
                        mutationDao = reopened.mutationDao(),
                    )
                Triple(
                    reopened.readShippedFixtureContents(),
                    store.mutationQueueState.rejections(),
                    reopened.pendingOwners(),
                )
            } finally {
                reopened.close()
            }

        // Then
        assertEquals(SHIPPED_FIXTURE_CONTENTS.withOwners(), contents)
        assertEquals(listOf(ON_DISK_REJECTION), rejections)
        assertEquals(ON_DISK_PENDING_OWNERS, owners)
        assertEquals(0, callback.creates)
        assertEquals(0, callback.destructiveMigrations)
        assertEquals(ShippedDatabaseFixture.VERSION, ShippedDatabaseFixture.readUserVersion(path))
        assertEquals(
            ShippedDatabaseFixture.IDENTITY_HASH,
            ShippedDatabaseFixture.readIdentityHash(path),
        )
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
            val evicted =
                objectDao.deleteOldestPayloadRows(limit = 10, keptSignatures = emptyList())

            // Then — only the cache rows counted and only they were evicted
            assertEquals(
                ShippedDatabaseFixture.OBJECT_ROWS.sumOf { row -> row.payload.length.toLong() },
                size,
            )
            assertEquals(ShippedDatabaseFixture.OBJECT_ROWS.size, evicted)
            assertEquals(1L, RawDatabaseQueries.reservedRowCount(path))
        } finally {
            database.close()
        }
    }

    /**
     * Room's delete of every table's rows but the engine's own: the cached queries go, and the
     * reserved rows stay.
     */
    suspend fun roomDropsTheCachedQueriesAndKeepsTheEnginesRows(path: String) {
        // Given — the shipped database's cache rows, and two rows of the engine's own
        ShippedDatabaseFixture.writeTo(path)
        val database = open(path, RecordingRoomCallback())
        try {
            val objectDao = database.objectDao()
            objectDao.upsert(ObjectRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE, "{}", 0))
            objectDao.upsert(ObjectRow(RESERVED_TABLE_NAME, CACHE_OWNER_SIGNATURE, "{}", 0))
            val reservedBefore = RawDatabaseQueries.reservedRowCount(path)

            // When
            objectDao.deleteAllExceptTable(RESERVED_TABLE_NAME)

            // Then
            ShippedDatabaseFixture.OBJECT_ROWS.map { row -> row.table }
                .distinct()
                .forEach { table -> assertEquals(emptyList(), objectDao.signaturesForTable(table)) }
            assertEquals(reservedBefore, RawDatabaseQueries.reservedRowCount(path))
            assertEquals(2L, reservedBefore)
        } finally {
            database.close()
        }
    }

    /**
     * Room's queries over query-shape records: found and deleted by prefix, and those whose payload
     * is gone deleted as orphans, while the rest stay.
     */
    suspend fun roomFindsAndDropsQueryShapeRecords(path: String) {
        // Given — one cached payload, its shape record, and a record whose payload is gone
        ShippedDatabaseFixture.writeTo(path)
        val database = open(path, RecordingRoomCallback())
        try {
            val objectDao = database.objectDao()
            val payload = ShippedDatabaseFixture.OBJECT_ROWS.first()
            val prefix = "$QUERY_SHAPE_SIGNATURE_PREFIX${payload.table}\u001F"
            objectDao.upsert(ObjectRow(RESERVED_TABLE_NAME, prefix + payload.signature, "{}", 0))
            objectDao.upsert(ObjectRow(RESERVED_TABLE_NAME, prefix + "gone?x=eq.1", "{}", 0))

            // When / Then — both found by prefix; the orphan dropped; the rest by prefix
            assertEquals(2, objectDao.rowsWithSignaturePrefix(RESERVED_TABLE_NAME, prefix).size)
            assertEquals(1, objectDao.deleteOrphanedQueryShapes())
            assertEquals(
                listOf(prefix + payload.signature),
                objectDao.rowsWithSignaturePrefix(RESERVED_TABLE_NAME, prefix).map { row ->
                    row.signature
                },
            )
            objectDao.deleteWithSignaturePrefix(RESERVED_TABLE_NAME, prefix)
            assertEquals(
                emptyList(),
                objectDao.rowsWithSignaturePrefix(RESERVED_TABLE_NAME, prefix),
            )
            assertEquals(
                ShippedDatabaseFixture.OBJECT_ROWS.size,
                objectDao.signaturesForTable(payload.table).size,
            )
        } finally {
            database.close()
        }
    }

    /**
     * Room's eviction query passes over the signatures it is told to keep, however old they are.
     */
    suspend fun roomEvictionKeepsTheListedSignatures(path: String) {
        // Given — the shipped database's cache rows
        ShippedDatabaseFixture.writeTo(path)
        val database = open(path, RecordingRoomCallback())
        try {
            val objectDao = database.objectDao()
            val kept = ShippedDatabaseFixture.OBJECT_ROWS.first()

            // When — every row is evicted but one a live reader shows
            val evicted =
                objectDao.deleteOldestPayloadRows(
                    limit = 100,
                    keptSignatures = listOf(kept.signature),
                )

            // Then
            assertEquals(ShippedDatabaseFixture.OBJECT_ROWS.size - 1, evicted)
            assertEquals(listOf(kept.signature), objectDao.signaturesForTable(kept.table))
        } finally {
            database.close()
        }
    }

    /**
     * Pending writes that recorded no owner are adopted in place by the identity of the first pass
     * that sees them, and keep their order.
     */
    suspend fun aQueueWithoutOwnersIsAdoptedInPlace(path: String) {
        // Given — the fixture, whose pending writes have no owners
        ShippedDatabaseFixture.writeTo(path)
        val database = open(path, RecordingRoomCallback())
        try {
            val store =
                newTestOfflineStore(
                    objectDao = database.objectDao(),
                    mutationDao = database.mutationDao(),
                )

            // When
            store.adoptOwnerlessMutations("user:user-a")
        } finally {
            database.close()
        }

        // Then — after reopening, every pending write is the adopting user's, in the same order
        val reopened = open(path, RecordingRoomCallback())
        try {
            val store =
                newTestOfflineStore(
                    objectDao = reopened.objectDao(),
                    mutationDao = reopened.mutationDao(),
                )
            assertEquals(
                listOf(1L, 2L, 3L, 4L).map { id -> id to "user:user-a" },
                reopened.pendingOwners(),
            )
            assertEquals(
                listOf(1L, 2L, 3L, 4L),
                store.pendingMutationsOf("user:user-a").map { row -> row.id },
            )
            assertEquals(emptyList(), store.pendingMutationsOf("user:user-b").map { row -> row.id })
        } finally {
            reopened.close()
        }
    }

    /**
     * The mutation queue's multi-row changes run in one Room transaction: when one fails part-way,
     * none of its writes land — the rejected list and the queue stay as they were.
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
     * Clearing local data is one transaction: when its mutation delete fails, the queue's state,
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
     * Clearing local data is one transaction: when its object-cache delete fails, the queue's
     * state, the mutations and the cache are all still there on disk.
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

    private suspend fun aClearThatFailsPartWayChangesNothing(
        path: String,
        storeFailingIn: (OfflineDatabase) -> OfflineStore,
    ) {
        // Given — the fixture with the queue's state written, and a clear that fails part-way
        ShippedDatabaseFixture.writeTo(path)
        val database = open(path, RecordingRoomCallback())
        val cleared =
            try {
                database.writeReservedRows()
                runCatching { storeFailingIn(database).clearAll() }
            } finally {
                database.close()
            }

        // When
        val reopened = open(path, RecordingRoomCallback())
        try {
            val store =
                newTestOfflineStore(
                    objectDao = reopened.objectDao(),
                    mutationDao = reopened.mutationDao(),
                )

            // Then — nothing was cleared: every mutation still has the owner it had
            assertTrue(cleared.isFailure)
            assertEquals(
                SHIPPED_FIXTURE_CONTENTS.withOwners(),
                reopened.readShippedFixtureContents(),
            )
            assertEquals(ON_DISK_PENDING_OWNERS, reopened.pendingOwners())
            assertEquals(listOf(ON_DISK_REJECTION), store.mutationQueueState.rejections())
        } finally {
            reopened.close()
        }
    }

    /** Room replays pending writes by id, whatever their wall-clock times say. */
    suspend fun roomReplaysPendingWritesInInsertionOrder(path: String) {
        // Given — a write added after the fixture's, stamped with a clock set back
        ShippedDatabaseFixture.writeTo(path)
        RawDatabaseQueries.insertWriteWithEarlierClock(path, id = 10)

        // When
        val database = open(path, RecordingRoomCallback())
        val ids =
            try {
                database.mutationDao().pendingAll().map { row -> row.id }
            } finally {
                database.close()
            }

        // Then — insertion order; the clock's order would have put it first
        assertEquals(listOf(1L, 2L, 3L, 4L, 10L), ids)
    }
}
