// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.EnqueueOutcome
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredRejection
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestPostgrest
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.postgrestResultOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * A rejection is recorded and its mutation row removed as one step. If a crash still leaves both —
 * an entry on the rejected list for a write that is also pending — the entry is dropped once the
 * write syncs, so a later retry cannot send a write the server already accepted.
 */
class RejectionCrashWindowTest {

    private val store = newTestStore()

    @Test
    fun a_rejection_recorded_before_a_crash_is_cleared_when_the_write_later_syncs() = runTest {
        // Given — the process died between writing the rejected entry and deleting the mutation row
        store.mutationQueue.enqueueInsert(
            table = NOTES,
            body = Json.parseToJsonElement("""[{"id":"n1","title":"x"}]""") as JsonArray,
        )
        val pending = store.mutationQueue.allPendingMutations().single()
        store.addRejection(
            StoredRejection(
                id = pending.id,
                table = NOTES,
                operation = pending.operation,
                bodyJson = pending.bodyJson,
                createdAtMs = 0,
                rejectedAtMs = 0,
                message = "refused before the crash",
            )
        )

        // When — the write replays and this time the server accepts it
        newTestMutationQueueReplayer(
                store = store,
                supabase = RecordingPostgrestServer().supabaseClient(),
            )
            .syncAllTables()

        // Then — it is not still reported as rejected
        assertEquals(0, store.pendingMutationCount())
        assertEquals(emptyList(), store.mutationQueue.rejectedList.rejections().map { it.id })
    }

    @Test
    fun a_rejection_left_by_a_crash_is_cleared_when_the_write_is_accepted_at_the_head() = runTest {
        // Given — a queued write with a stale rejected entry of the same id
        val id =
            (store.mutationQueue.enqueueOwnedBy(USER) {
                    enqueueInsert(
                        table = NOTES,
                        body = Json.parseToJsonElement("""[{"id":"n1"}]""") as JsonArray,
                    )
                } as EnqueueOutcome.Queued)
                .id
        store.addRejection(
            StoredRejection(
                id = id,
                table = NOTES,
                operation = MutationOperation.INSERT.storedName,
                bodyJson = "[]",
                createdAtMs = 0,
                rejectedAtMs = 0,
                message = "stale",
            )
        )
        val replayer =
            newTestMutationQueueReplayer(
                store = store,
                supabase = RecordingPostgrestServer().supabaseClient(),
                currentIdentity = { QueueIdentity.User("user-a") },
            )

        // When — its caller sends it at the head and the server accepts it
        val outcome =
            replayer.sendAtHead(
                id,
                QueueIdentity.User("user-a"),
                send = { acceptedResult() },
                onAccepted = {},
            )

        // Then
        assertIs<MutationQueueReplayer.HeadSendOutcome.Sent<*>>(outcome)
        assertEquals(emptyList(), store.mutationQueue.rejectedList.rejections().map { it.id })
    }

    private fun acceptedResult(): PostgrestResult = postgrestResultOf("[]", newTestPostgrest())

    private companion object {
        const val USER = "user:user-a"
        const val NOTES = "notes"
    }
}
