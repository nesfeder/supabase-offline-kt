// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray

/**
 * Verifies [OfflineStore.clearLocalData] wipes both the cached SELECT payloads and the queued
 * mutation store, so an identity-clearing flow (logout, account switch, account deletion) leaves no
 * prior-user data or un-synced writes behind on disk.
 */
class OfflineStoreClearAllTest {

    @Test
    fun clearAll_wipes_cached_payloads_and_queued_mutations() = runTest {
        // Given a store holding a cached SELECT payload and a queued offline mutation
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        val store = buildStore(queryCacheDao, mutationDao)
        store.queryCache.cacheResponse("reports", "sig-A", """[{"id":"i-1","user_id":"u-1"}]""")
        store.enqueueInsert(table = "projects", body = JsonArray(emptyList()))
        assertTrue(queryCacheDao.getRow("reports", "sig-A") != null, "precondition: payload seeded")
        assertTrue(mutationDao.inserted.isNotEmpty(), "precondition: mutation queued")

        // When the entire offline cache is cleared
        store.clearLocalData()

        // Then no cached payload and no queued mutation remain
        assertNull(queryCacheDao.getRow("reports", "sig-A"), "cached payload should be gone")
        assertEquals(0, mutationDao.inserted.size, "queued mutations should be gone")
    }

    private fun buildStore(queryCacheDao: QueryCacheDao, mutationDao: MutationDao): OfflineStore =
        newTestOfflineStore(
            queryCacheDao = queryCacheDao,
            mutationDao = mutationDao,
        )
}
