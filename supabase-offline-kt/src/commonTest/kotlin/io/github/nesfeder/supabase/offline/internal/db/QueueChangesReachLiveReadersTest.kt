// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray

/**
 * A live reader re-reads its table's mutation rows only when the store says they changed, so every
 * change to the queue must say so: a rejection, a retry, a replay and a clear each reach a reader
 * already showing the table, not only the enqueue and the removal.
 */
class QueueChangesReachLiveReadersTest {

    private val store =
        newTestOfflineStore(objectDao = FakeObjectDao(), mutationDao = FakeMutationDao())

    @Test
    fun a_rejected_write_leaves_a_live_reader() =
        runTest(UnconfinedTestDispatcher()) {
            // Given — a reader showing a queued write
            store.addPayload(TABLE, SIGNATURE, SERVER_ROWS, authoritativeEmpty = true)
            val shown = startReader(SIGNATURE)
            val id = store.enqueueInsert(TABLE, rows("""[{"id":2,"title":"queued"}]"""))
            assertEquals("""[{"id":1,"title":"server"},{"id":2,"title":"queued"}]""", shown.value)

            // When
            store.rejectMutation(
                store.allPendingMutations().single { it.id == id },
                "refused",
                null,
            )

            // Then
            assertEquals(SERVER_ROWS, shown.value)
        }

    @Test
    fun a_retried_rejection_returns_to_a_live_reader() =
        runTest(UnconfinedTestDispatcher()) {
            // Given — a rejected write, and a reader showing the table without it
            store.addPayload(TABLE, SIGNATURE, SERVER_ROWS, authoritativeEmpty = true)
            val id = store.enqueueInsert(TABLE, rows("""[{"id":2,"title":"queued"}]"""))
            store.rejectMutation(
                store.allPendingMutations().single { it.id == id },
                "refused",
                null,
            )
            val shown = startReader(SIGNATURE)
            assertEquals(SERVER_ROWS, shown.value)

            // When
            store.retryRejectedMutation(id, null)

            // Then
            assertEquals("""[{"id":1,"title":"server"},{"id":2,"title":"queued"}]""", shown.value)
        }

    @Test
    fun a_replayed_keyless_insert_shows_once_after_its_query_refreshes() =
        runTest(UnconfinedTestDispatcher()) {
            // Given — a reader showing a keyless INSERT; a second cached query keeps it after sync
            store.addPayload(TABLE, SIGNATURE, SERVER_ROWS, authoritativeEmpty = true)
            store.addPayload(TABLE, OTHER_SIGNATURE, SERVER_ROWS, authoritativeEmpty = true)
            val shown = startReader(SIGNATURE)
            val id = store.enqueueInsert(TABLE, rows("""[{"title":"keyless"}]"""))
            assertEquals("""[{"id":1,"title":"server"},{"title":"keyless"}]""", shown.value)

            // When — it replays, and a refresh started after the replay brings its row back
            store.markMutationSynced(id, TABLE)
            store.addPayload(
                TABLE,
                SIGNATURE,
                """[{"id":1,"title":"server"},{"id":9,"title":"keyless"}]""",
                requestStartedAtTick = store.monotonicTickNow(),
                authoritativeEmpty = true,
            )

            // Then — the server's copy only, not the replayed write beside it
            assertEquals("""[{"id":1,"title":"server"},{"id":9,"title":"keyless"}]""", shown.value)
        }

    @Test
    fun a_clear_takes_the_queued_write_from_a_live_reader() =
        runTest(UnconfinedTestDispatcher()) {
            // Given — a reader showing a queued write
            store.addPayload(TABLE, SIGNATURE, SERVER_ROWS, authoritativeEmpty = true)
            val shown = startReader(SIGNATURE)
            store.enqueueInsert(TABLE, rows("""[{"id":2,"title":"queued"}]"""))
            assertEquals("""[{"id":1,"title":"server"},{"id":2,"title":"queued"}]""", shown.value)

            // When
            store.clearAll()

            // Then — nothing is known any more: no payload, and no queued write
            assertNull(shown.value)
        }

    /** Starts a live reader of [TABLE]'s [signature], holding what it shows last. */
    private fun TestScope.startReader(signature: String): StateFlow<String?> {
        val shown = MutableStateFlow<String?>(null)
        backgroundScope.launch {
            store.overlaidPayloadFlow(TABLE, signature).collect { payload -> shown.value = payload }
        }
        return shown
    }

    private fun rows(json: String): JsonArray = OfflineJson.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
        const val SIGNATURE = "notes?select=*"
        const val OTHER_SIGNATURE = "notes?select=*&order=id"
        const val SERVER_ROWS = """[{"id":1,"title":"server"}]"""
    }
}
