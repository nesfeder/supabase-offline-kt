// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.SYSTEM

/**
 * The engine's reserved `object_cache` rows on a real iOS database file: an older build that opens
 * the file reads around them, they persist across reopening without disturbing the schema, and this
 * build's eviction, size and queue-order queries behave on real SQLite as they do in memory. See
 * [ReservedRowsDatabaseScenarios].
 */
class OfflineDatabaseReservedRowsIosTest {

    private val directory =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-reserved-rows-${Random.nextLong()}"
    private val dbPath = (directory / "supabase_offline.db").toString()
    private val scenarios = ReservedRowsDatabaseScenarios { path, callback ->
        getRoomDatabase(getDatabaseBuilder(path).addCallback(callback))
    }

    @BeforeTest
    fun setUp() {
        FileSystem.SYSTEM.createDirectories(directory)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    @Test
    fun an_older_build_reads_around_reserved_rows() = runTest {
        scenarios.anOlderBuildReadsAroundReservedRows(dbPath)
    }

    @Test
    fun reserved_rows_persist_across_reopening() = runTest {
        scenarios.reservedRowsPersistAcrossReopening(dbPath)
    }

    @Test
    fun room_never_evicts_or_counts_reserved_rows() = runTest {
        scenarios.roomNeverEvictsOrCountsReservedRows(dbPath)
    }

    @Test
    fun room_eviction_keeps_the_listed_signatures() = runTest {
        scenarios.roomEvictionKeepsTheListedSignatures(dbPath)
    }

    @Test
    fun room_finds_and_drops_query_shape_records() = runTest {
        scenarios.roomFindsAndDropsQueryShapeRecords(dbPath)
    }

    @Test
    fun room_drops_the_cached_queries_and_keeps_the_engines_rows() = runTest {
        scenarios.roomDropsTheCachedQueriesAndKeepsTheEnginesRows(dbPath)
    }

    @Test
    fun room_replays_pending_writes_in_insertion_order() = runTest {
        scenarios.roomReplaysPendingWritesInInsertionOrder(dbPath)
    }

    @Test
    fun an_earlier_builds_queue_is_adopted_in_place() = runTest {
        scenarios.anEarlierBuildsQueueIsAdoptedInPlace(dbPath)
    }

    @Test
    fun a_failed_mutation_queue_transaction_changes_nothing() = runTest {
        scenarios.aFailedMutationQueueTransactionChangesNothing(dbPath)
    }

    @Test
    fun a_clear_whose_mutation_delete_fails_changes_nothing() = runTest {
        scenarios.aClearWhoseMutationDeleteFailsChangesNothing(dbPath)
    }

    @Test
    fun a_clear_whose_object_cache_delete_fails_changes_nothing() = runTest {
        scenarios.aClearWhoseObjectCacheDeleteFailsChangesNothing(dbPath)
    }
}
