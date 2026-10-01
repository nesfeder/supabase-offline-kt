// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * After a clear the mutation queue's records are known to be empty, so a reader that runs straight
 * after it — a reactive read re-querying on the clear — writes none of them back, and a first read
 * that began before the clear does not put back what it read.
 */
class ClearedQueueRecordsTest {

    @Test
    fun reading_the_queues_records_after_a_clear_writes_none_back() = runTest {
        // Given — a store holding the rejected list and a keyed insert whose send started
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE, "{}")
        val store = newTestOfflineStore(objectDao = objectDao)
        val id =
            store.enqueueInsert("todos", Json.parseToJsonElement("""[{"id":"x"}]""") as JsonArray)
        store.markSendUnconfirmed(id)

        // When — cleared, then read the way the overlay and the counts read them
        store.clearLocalData()
        val unconfirmed = store.unconfirmedSends()
        val rejections = store.mutationQueueState.rejections()

        // Then — every record reads empty, and nothing was written back
        assertTrue(unconfirmed.isEmpty() && rejections.isEmpty())
        assertEquals(emptyList(), objectDao.allForTable(RESERVED_TABLE_NAME))
    }

    @Test
    fun a_first_read_that_began_before_a_clear_does_not_put_its_records_back() = runTest {
        // Given — a keyed insert whose send started, and a first read of the started sends parked
        // after it read the rows
        val mutationDao = FakeMutationDao()
        val writer = newTestOfflineStore(mutationDao = mutationDao)
        val id =
            writer.enqueueInsert("todos", Json.parseToJsonElement("""[{"id":"x"}]""") as JsonArray)
        writer.markSendUnconfirmed(id)
        val store = newTestOfflineStore(mutationDao = mutationDao)
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        mutationDao.afterUnconfirmedSendIdsRead = {
            parked.complete(Unit)
            release.await()
        }
        val firstRead = async { store.unconfirmedSends() }
        parked.await()

        // When — the clear runs while that read is parked, and then the read finishes
        mutationDao.afterUnconfirmedSendIdsRead = null
        store.clearLocalData()
        release.complete(Unit)
        firstRead.await()

        // Then — the record still reads as the clear left it
        assertTrue(store.unconfirmedSends().isEmpty(), "read the values from before the clear")
    }
}
