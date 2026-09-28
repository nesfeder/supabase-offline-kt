package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A rejection is recorded and its queue row removed as one step. If a crash still leaves both — an
 * entry on the rejected list for a write that is also pending — the entry is dropped once the write
 * syncs, so a later retry cannot send a write the server already accepted.
 */
class RejectionCrashWindowTest {

    private val store = newTestOfflineStore()

    @Test
    fun a_rejection_recorded_before_a_crash_is_cleared_when_the_write_later_syncs() = runTest {
        // Given — the process died between writing the rejected entry and deleting the queue row
        store.enqueueInsert(table = NOTES, body = Json.parseToJsonElement("""[{"id":"n1","title":"x"}]""") as JsonArray)
        val pending = store.allPendingMutations().single()
        store.outboxState.addRejection(
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
        assertEquals(emptyList(), store.outboxState.rejections().map { it.id })
    }

    private companion object {
        const val NOTES = "notes"
    }
}
