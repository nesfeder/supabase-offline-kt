// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeDeleteAllFailingMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeDeleteAllFailingQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingRoomCallback
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
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
                    newTestStore(
                        queryCacheDao = reopened.queryCacheDao(),
                        mutationDao = reopened.mutationDao(),
                    )
                Triple(
                    reopened.readShippedFixtureContents(),
                    store.mutationQueue.rejectedList.rejections(),
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
        assertEquals(
            ShippedDatabaseFixture.CURRENT_VERSION,
            ShippedDatabaseFixture.readUserVersion(path),
        )
        assertEquals(
            ShippedDatabaseFixture.CURRENT_IDENTITY_HASH,
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
            val queryCacheDao = database.queryCacheDao()

            // When
            val size = queryCacheDao.totalPayloadBytes()
            val evicted =
                queryCacheDao.deleteOldestPayloadRows(limit = 10, keptCacheKeys = emptyList())

            // Then — only the cache rows counted and only they were evicted
            assertEquals(
                ShippedDatabaseFixture.CACHED_QUERY_ROWS.sumOf { row ->
                    row.payload.length.toLong()
                },
                size,
            )
            assertEquals(ShippedDatabaseFixture.CACHED_QUERY_ROWS.size, evicted)
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
            val queryCacheDao = database.queryCacheDao()
            queryCacheDao.upsert(
                CachedQueryRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY, "{}", 0)
            )
            queryCacheDao.upsert(
                CachedQueryRow(RESERVED_TABLE_NAME, CACHE_OWNER_CACHE_KEY, "{}", 0)
            )
            val reservedBefore = RawDatabaseQueries.reservedRowCount(path)

            // When
            queryCacheDao.deleteAllExceptTable(RESERVED_TABLE_NAME)

            // Then
            ShippedDatabaseFixture.CACHED_QUERY_ROWS.map { row -> row.table }
                .distinct()
                .forEach { table ->
                    assertEquals(emptyList(), queryCacheDao.cacheKeysForTable(table))
                }
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
            val queryCacheDao = database.queryCacheDao()
            val payload = ShippedDatabaseFixture.CACHED_QUERY_ROWS.first()
            val prefix = "$QUERY_SHAPE_CACHE_KEY_PREFIX${payload.table}\u001F"
            queryCacheDao.upsert(
                CachedQueryRow(RESERVED_TABLE_NAME, prefix + payload.cacheKey, "{}", 0)
            )
            queryCacheDao.upsert(
                CachedQueryRow(RESERVED_TABLE_NAME, prefix + "gone?x=eq.1", "{}", 0)
            )

            // When / Then — both found by prefix; the orphan dropped; the rest by prefix
            assertEquals(2, queryCacheDao.rowsWithCacheKeyPrefix(RESERVED_TABLE_NAME, prefix).size)
            assertEquals(1, queryCacheDao.deleteOrphanedQueryShapes())
            assertEquals(
                listOf(prefix + payload.cacheKey),
                queryCacheDao.rowsWithCacheKeyPrefix(RESERVED_TABLE_NAME, prefix).map { row ->
                    row.cacheKey
                },
            )
            queryCacheDao.deleteWithCacheKeyPrefix(RESERVED_TABLE_NAME, prefix)
            assertEquals(
                emptyList(),
                queryCacheDao.rowsWithCacheKeyPrefix(RESERVED_TABLE_NAME, prefix),
            )
            assertEquals(
                ShippedDatabaseFixture.CACHED_QUERY_ROWS.size,
                queryCacheDao.cacheKeysForTable(payload.table).size,
            )
        } finally {
            database.close()
        }
    }

    /**
     * Room's eviction query passes over the signatures it is told to keep, however old they are.
     */
    suspend fun roomEvictionKeepsTheListedCacheKeys(path: String) {
        // Given — the shipped database's cache rows
        ShippedDatabaseFixture.writeTo(path)
        val database = open(path, RecordingRoomCallback())
        try {
            val queryCacheDao = database.queryCacheDao()
            val kept = ShippedDatabaseFixture.CACHED_QUERY_ROWS.first()

            // When — every row is evicted but one a live reader shows
            val evicted =
                queryCacheDao.deleteOldestPayloadRows(
                    limit = 100,
                    keptCacheKeys = listOf(kept.cacheKey),
                )

            // Then
            assertEquals(ShippedDatabaseFixture.CACHED_QUERY_ROWS.size - 1, evicted)
            assertEquals(listOf(kept.cacheKey), queryCacheDao.cacheKeysForTable(kept.table))
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
                newTestStore(
                    queryCacheDao = database.queryCacheDao(),
                    mutationDao = database.mutationDao(),
                )

            // When
            store.mutationQueue.adoptOwnerlessMutations("user:user-a")
        } finally {
            database.close()
        }

        // Then — after reopening, every pending write is the adopting user's, in the same order
        val reopened = open(path, RecordingRoomCallback())
        try {
            val store =
                newTestStore(
                    queryCacheDao = reopened.queryCacheDao(),
                    mutationDao = reopened.mutationDao(),
                )
            assertEquals(
                listOf(1L, 2L, 3L, 4L).map { id -> id to "user:user-a" },
                reopened.pendingOwners(),
            )
            assertEquals(
                listOf(1L, 2L, 3L, 4L),
                store.mutationQueue.pendingMutationsOf("user:user-a").map { row -> row.id },
            )
            assertEquals(
                emptyList(),
                store.mutationQueue.pendingMutationsOf("user:user-b").map { row -> row.id },
            )
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
            val queryCacheDao = database.queryCacheDao()
            val mutationDao = database.mutationDao()
            val store = newTestStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)
            val (rejectedRow, _) =
                store.mutationQueue.rejectedList.rejectedRowWith(add = ON_DISK_REJECTION)

            // When — a transaction that writes the rejection and deletes the write, then fails
            val failure = runCatching {
                mutationDao.runInTransaction {
                    queryCacheDao.upsert(rejectedRow)
                    mutationDao.deleteById(1L)
                    error("interrupted")
                }
            }

            // Then
            assertTrue(failure.isFailure)
            assertEquals(null, queryCacheDao.get(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY))
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
            newTestStore(
                queryCacheDao = database.queryCacheDao(),
                cachedFileDao = database.cachedFileDao(),
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
            newTestStore(
                queryCacheDao = FakeDeleteAllFailingQueryCacheDao(database.queryCacheDao()),
                cachedFileDao = database.cachedFileDao(),
                mutationDao = database.mutationDao(),
            )
        }
    }

    private suspend fun aClearThatFailsPartWayChangesNothing(
        path: String,
        storeFailingIn: (OfflineDatabase) -> TestStore,
    ) {
        // Given — the fixture with the queue's state written, and a clear that fails part-way
        ShippedDatabaseFixture.writeTo(path)
        val database = open(path, RecordingRoomCallback())
        val cleared =
            try {
                database.writeReservedRows()
                runCatching { storeFailingIn(database).localData.clearLocalData() }
            } finally {
                database.close()
            }

        // When
        val reopened = open(path, RecordingRoomCallback())
        try {
            val store =
                newTestStore(
                    queryCacheDao = reopened.queryCacheDao(),
                    mutationDao = reopened.mutationDao(),
                )

            // Then — nothing was cleared: every mutation still has the owner it had
            assertTrue(cleared.isFailure)
            assertEquals(
                SHIPPED_FIXTURE_CONTENTS.withOwners(),
                reopened.readShippedFixtureContents(),
            )
            assertEquals(ON_DISK_PENDING_OWNERS, reopened.pendingOwners())
            assertEquals(listOf(ON_DISK_REJECTION), store.mutationQueue.rejectedList.rejections())
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
