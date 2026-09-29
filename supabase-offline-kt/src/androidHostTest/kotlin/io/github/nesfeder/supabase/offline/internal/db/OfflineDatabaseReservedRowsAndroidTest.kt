package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The engine's reserved `object_cache` rows on a real Android database file: an older build that
 * opens the file reads around them, they persist across reopening without disturbing the schema, and
 * this build's eviction, size and queue-order queries behave on real SQLite as they do in memory.
 * See [ReservedRowsDatabaseScenarios].
 */
@RunWith(AndroidJUnit4::class)
class OfflineDatabaseReservedRowsAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "reserved-rows")
    private val dbPath = File(directory, "supabase_offline.db").absolutePath
    private val scenarios = ReservedRowsDatabaseScenarios { path, callback ->
        getRoomDatabase(getDatabaseBuilder(context, path).addCallback(callback))
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
