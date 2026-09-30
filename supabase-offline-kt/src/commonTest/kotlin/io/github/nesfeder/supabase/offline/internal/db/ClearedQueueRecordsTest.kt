// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

/**
 * After a clear the mutation queue's records are known to be empty, so a reader that runs straight
 * after it — a reactive read re-querying on the clear — writes none of them back.
 */
class ClearedQueueRecordsTest {

    @Test
    fun reading_the_queues_records_after_a_clear_writes_none_back() = runTest {
        // Given — a store holding both of the queue's reserved rows
        val objectDao = FakeObjectDao()
        listOf(REJECTED_MUTATIONS_SIGNATURE, UNCONFIRMED_SENDS_SIGNATURE).forEach { signature ->
            objectDao.seedPayload(RESERVED_TABLE_NAME, signature, "{}")
        }
        val store = newTestOfflineStore(objectDao = objectDao)

        // When — cleared, then read the way the overlay and the counts read them
        store.clearAll()
        val unconfirmed = store.mutationQueueState.unconfirmedSends()
        val rejections = store.mutationQueueState.rejections()

        // Then — every record reads empty, and nothing was written back
        assertTrue(unconfirmed.isEmpty() && rejections.isEmpty())
        assertEquals(emptyList(), objectDao.allForTable(RESERVED_TABLE_NAME))
    }

    @Test
    fun a_first_read_of_a_missing_record_that_races_a_clear_writes_no_row_back() = runTest {
        for (signature in listOf(UNCONFIRMED_SENDS_SIGNATURE)) {
            // Given — no row for the record, as a database from an engine that never wrote one has,
            // and its first read parked after it found none
            val objectDao = FakeObjectDao()
            val store = newTestOfflineStore(objectDao = objectDao)
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            objectDao.onGetSnapshot = { _, read ->
                if (read == signature) {
                    parked.complete(Unit)
                    release.await()
                }
            }
            val firstRead = async { store.mutationQueueState.unconfirmedSends() }
            parked.await()

            // When — the clear runs while that read is parked, and then the read finishes
            objectDao.onGetSnapshot = null
            store.clearAll()
            release.complete(Unit)
            firstRead.await()

            // Then — nothing was written back after the clear
            assertEquals(
                emptyList(),
                objectDao.allForTable(RESERVED_TABLE_NAME).map { row -> row.signature },
                "$signature wrote a row back after the clear",
            )
        }
    }

    @Test
    fun a_first_read_that_began_before_a_clear_does_not_put_its_records_back() = runTest {
        for (signature in listOf(UNCONFIRMED_SENDS_SIGNATURE)) {
            // Given — the record holds a mutation, and its first read is parked after it read its
            // row
            val objectDao = FakeObjectDao()
            objectDao.seedPayload(
                RESERVED_TABLE_NAME,
                UNCONFIRMED_SENDS_SIGNATURE,
                """{"version":1,"mutationIds":[7]}""",
            )
            val store = newTestOfflineStore(objectDao = objectDao)
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            objectDao.onGetSnapshot = { _, read ->
                if (read == signature) {
                    parked.complete(Unit)
                    release.await()
                }
            }
            val firstRead = async { store.mutationQueueState.unconfirmedSends() }
            parked.await()

            // When — the clear runs while that read is parked, and then the read finishes
            objectDao.onGetSnapshot = null
            store.clearAll()
            release.complete(Unit)
            firstRead.await()

            // Then — the record still reads as the clear left it
            assertTrue(
                store.mutationQueueState.unconfirmedSends().isEmpty(),
                "$signature read the values from before the clear",
            )
        }
    }
}
