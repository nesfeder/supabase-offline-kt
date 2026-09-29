package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestPostgrest
import io.github.nesfeder.supabase.offline.testdoubles.postgrestResultOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A rejection is recorded and its mutation row removed as one step. If a crash still leaves both —
 * an entry on the rejected list for a write that is also pending — the entry is dropped once the
 * write syncs, so a later retry cannot send a write the server already accepted.
 */
class RejectionCrashWindowTest {

    private val store = newTestOfflineStore()

    @Test
    fun a_rejection_recorded_before_a_crash_is_cleared_when_the_write_later_syncs() = runTest {
        // Given — the process died between writing the rejected entry and deleting the mutation row
        store.enqueueInsert(table = NOTES, body = Json.parseToJsonElement("""[{"id":"n1","title":"x"}]""") as JsonArray)
        val pending = store.allPendingMutations().single()
        store.mutationQueueState.addRejection(
            StoredRejection(
                id = pending.id,
                table = NOTES,
                operation = pending.operation,
                bodyJson = pending.bodyJson,
                createdAtMs = 0,
                rejectedAtMs = 0,
                message = "refused before the crash",
            ),
        )

        // When — the write replays and this time the server accepts it
        OfflineMutationSyncer(store = store, supabase = RecordingPostgrestServer().supabaseClient()).syncAllTables()

        // Then — it is not still reported as rejected
        assertEquals(0, store.pendingMutationCount())
        assertEquals(emptyList(), store.mutationQueueState.rejections().map { it.id })
    }

    @Test
    fun a_rejection_left_by_a_crash_is_cleared_when_the_write_is_accepted_at_the_head() = runTest {
        // Given — a queued write with a stale rejected entry of the same id
        val id = (store.enqueueOwnedBy(USER) { enqueueInsert(table = NOTES, body = Json.parseToJsonElement("""[{"id":"n1"}]""") as JsonArray) }
            as EnqueueOutcome.Queued).id
        store.mutationQueueState.addRejection(
            StoredRejection(id = id, table = NOTES, operation = MutationOperation.INSERT, bodyJson = "[]", createdAtMs = 0, rejectedAtMs = 0, message = "stale"),
        )
        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = RecordingPostgrestServer().supabaseClient(),
            currentIdentity = { QueueIdentity.User("user-a") },
        )

        // When — its caller sends it at the head and the server accepts it
        val outcome = syncer.sendAtHead(id, QueueIdentity.User("user-a"), send = { acceptedResult() }, onAccepted = {})

        // Then
        assertIs<OfflineMutationSyncer.HeadSendOutcome.Sent>(outcome)
        assertEquals(emptyList(), store.mutationQueueState.rejections().map { it.id })
    }

    private fun acceptedResult(): PostgrestResult = postgrestResultOf("[]", newTestPostgrest())

    private companion object {
        const val USER = "user:user-a"
        const val NOTES = "notes"
    }
}
