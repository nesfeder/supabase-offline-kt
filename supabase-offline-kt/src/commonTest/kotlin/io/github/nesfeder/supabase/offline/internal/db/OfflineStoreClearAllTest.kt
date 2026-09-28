@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.time.ExperimentalTime

/**
 * Verifies [OfflineStore.clearAll] wipes both the cached SELECT payloads and the queued
 * mutation store, so an identity-clearing flow (logout, account switch, account deletion)
 * leaves no prior-user data or un-synced writes behind on disk.
 */
class OfflineStoreClearAllTest {

    @Test
    fun clearAll_wipes_cached_payloads_and_queued_mutations() = runTest {
        // Given a store holding a cached SELECT payload and a queued offline mutation
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        val store = buildStore(objectDao, mutationDao)
        store.addPayload("reports", "sig-A", """[{"id":"i-1","user_id":"u-1"}]""")
        store.enqueueInsert(table = "projects", body = JsonArray(emptyList()))
        assertTrue(objectDao.getRow("reports", "sig-A") != null, "precondition: payload seeded")
        assertTrue(mutationDao.inserted.isNotEmpty(), "precondition: mutation queued")

        // When the entire offline cache is cleared
        store.clearAll()

        // Then no cached payload and no queued mutation remain
        assertNull(objectDao.getRow("reports", "sig-A"), "cached payload should be gone")
        assertEquals(0, mutationDao.inserted.size, "queued mutations should be gone")
    }

    private fun buildStore(objectDao: ObjectDao, mutationDao: MutationDao): OfflineStore =
        newTestOfflineStore(
            objectDao = objectDao,
            mutationDao = mutationDao,
        )
}
