// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * The engine's queue state and reserved `object_cache` rows on a real Android database file: they
 * persist across reopening without disturbing the schema, and this build's eviction, size and
 * queue-order queries behave on real SQLite as they do in memory. See
 * [ReservedRowsDatabaseScenarios].
 */
@RunWith(AndroidJUnit4::class)
class OfflineDatabaseReservedRowsAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "reserved-rows")
    private val databasePath = File(directory, "supabase_offline.db").absolutePath
    private val scenarios = ReservedRowsDatabaseScenarios { path, callback ->
        buildOfflineDatabase(offlineDatabaseBuilder(context, path).addCallback(callback))
    }

    @BeforeTest
    fun setUp() {
        directory.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun reserved_rows_persist_across_reopening() = runTest {
        scenarios.reservedRowsPersistAcrossReopening(databasePath)
    }

    @Test
    fun room_never_evicts_or_counts_reserved_rows() = runTest {
        scenarios.roomNeverEvictsOrCountsReservedRows(databasePath)
    }

    @Test
    fun room_eviction_keeps_the_listed_cache_keys() = runTest {
        scenarios.roomEvictionKeepsTheListedCacheKeys(databasePath)
    }

    @Test
    fun room_finds_and_drops_query_shape_records() = runTest {
        scenarios.roomFindsAndDropsQueryShapeRecords(databasePath)
    }

    @Test
    fun room_drops_the_cached_queries_and_keeps_the_engines_rows() = runTest {
        scenarios.roomDropsTheCachedQueriesAndKeepsTheEnginesRows(databasePath)
    }

    @Test
    fun room_replays_pending_writes_in_insertion_order() = runTest {
        scenarios.roomReplaysPendingWritesInInsertionOrder(databasePath)
    }

    @Test
    fun a_queue_without_owners_is_adopted_in_place() = runTest {
        scenarios.aQueueWithoutOwnersIsAdoptedInPlace(databasePath)
    }

    @Test
    fun a_failed_mutation_queue_transaction_changes_nothing() = runTest {
        scenarios.aFailedMutationQueueTransactionChangesNothing(databasePath)
    }

    @Test
    fun a_failed_patch_transaction_changes_nothing() = runTest {
        scenarios.aFailedPatchTransactionChangesNothing(databasePath)
    }

    @Test
    fun a_clear_whose_mutation_delete_fails_changes_nothing() = runTest {
        scenarios.aClearWhoseMutationDeleteFailsChangesNothing(databasePath)
    }

    @Test
    fun a_clear_whose_object_cache_delete_fails_changes_nothing() = runTest {
        scenarios.aClearWhoseObjectCacheDeleteFailsChangesNothing(databasePath)
    }
}
