// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeRemoteTable
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * A pass is reported as cleared, with nothing left queued, only when local data was cleared under
 * it. A cancellation from anywhere else is the caller's to see, and the queue stands as it was.
 */
class ReplayPassCancellationTest {

    @Test
    fun a_cancellation_the_transport_throws_is_not_reported_as_a_clear() = runTest {
        // Given — a queued insert, and a transport that cancels its request instead of answering
        val store = newTestStore()
        store.mutationQueue.enqueueInsert(
            TABLE,
            Json.parseToJsonElement("""[{"title":"note"}]""") as JsonArray,
        )
        val supabase = RecordingPostgrestServer().supabaseClient()
        val replayer =
            newTestMutationQueueReplayer(
                store = store,
                supabase = supabase,
                remoteTableFor = { _, table ->
                    FakeRemoteTable(
                        table = table,
                        postgrest = supabase.postgrest,
                        onInsert = { throw CancellationException(TRANSPORT_CANCELLATION) },
                    )
                },
            )

        // When
        val thrown = assertFailsWith<CancellationException> { replayer.sync() }

        // Then — thrown to the caller, not read as a clear that emptied the queue; the write stays
        assertEquals(TRANSPORT_CANCELLATION, thrown.message)
        assertEquals(1, store.pendingMutationCount())
    }

    private companion object {
        const val TABLE = "notes"
        const val TRANSPORT_CANCELLATION = "The request's own cancellation"
    }
}
