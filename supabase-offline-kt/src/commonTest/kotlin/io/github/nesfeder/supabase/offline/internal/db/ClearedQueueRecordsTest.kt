package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * After a clear the mutation queue's records are known to be empty, so a reader that runs straight
 * after it — a reactive read re-querying on the clear — writes none of them back.
 */
class ClearedQueueRecordsTest {

    @Test
    fun reading_the_queues_records_after_a_clear_writes_none_back() = runTest {
        // Given — a store holding all four reserved rows
        val objectDao = FakeObjectDao()
        listOf(
            REJECTED_MUTATIONS_SIGNATURE,
            MUTATION_OWNERS_SIGNATURE,
            SYNCED_MUTATION_COVERAGE_SIGNATURE,
            UNCONFIRMED_SENDS_SIGNATURE,
        ).forEach { signature -> objectDao.seedPayload(RESERVED_TABLE_NAME, signature, "{}") }
        val store = newTestOfflineStore(objectDao = objectDao)

        // When — cleared, then read the way the overlay and the counts read them
        store.clearAll()
        val unconfirmed = store.mutationQueueState.unconfirmedSends()
        val coverage = store.mutationQueueState.syncedMutationCoverage()
        val rejections = store.mutationQueueState.rejections()
        store.mutationQueueState.owners()

        // Then — every record reads empty, and nothing was written back
        assertTrue(unconfirmed.isEmpty() && coverage.isEmpty() && rejections.isEmpty())
        assertEquals(emptyList(), objectDao.allForTable(RESERVED_TABLE_NAME))
    }
}
