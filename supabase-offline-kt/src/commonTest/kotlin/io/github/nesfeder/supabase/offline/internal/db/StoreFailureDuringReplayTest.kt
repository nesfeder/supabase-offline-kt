package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A pass whose local store fails around a send — a full disk while it records the send — stops
 * there with the mutation still queued, and the next pass, and the next write at the head, go out
 * as soon as the store works again.
 */
class StoreFailureDuringReplayTest {

    private val objectDao = FakeObjectDao()
    private val store = newTestOfflineStore(objectDao = objectDao)
    private val server = RecordingPostgrestServer()
    private val replayer = MutationQueueReplayer(
        store = store,
        supabase = server.supabaseClient(),
        currentIdentity = { QueueIdentity.Anonymous },
    )

    @Test
    fun a_store_failure_during_a_pass_does_not_stop_the_passes_after_it() = runTest {
        // Given — a queued keyed INSERT, and a disk that fails the write recording its send, once
        store.enqueueOwnedBy(QueueIdentity.Anonymous.storedValue) {
            enqueueInsert(table = TABLE, body = rows("""[{"id":"k1","title":"Buy milk"}]"""))
        }
        objectDao.failNextUpsertOf { row -> row.table == RESERVED_TABLE_NAME && row.signature == UNCONFIRMED_SENDS_SIGNATURE }

        // When — one pass meets the failure, and the next runs once the disk recovers
        val failed = runCatching { replayer.sync() }
        val next = replayer.sync()

        // Then — the first sent nothing and kept the write; the next sent it
        assertTrue(failed.isFailure || failed.getOrThrow().stop is SyncStop.TransientFailure, "got $failed")
        assertEquals(1, next.syncedCount)
        assertEquals(null, next.stop)
        assertEquals(1, server.requests.size)
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun a_store_failure_during_a_pass_does_not_hold_back_later_writes_at_the_head() = runTest {
        // Given — a pass that met a failing disk
        store.enqueueOwnedBy(QueueIdentity.Anonymous.storedValue) {
            enqueueInsert(table = TABLE, body = rows("""[{"id":"k1","title":"Buy milk"}]"""))
        }
        objectDao.failNextUpsertOf { row -> row.table == RESERVED_TABLE_NAME && row.signature == UNCONFIRMED_SENDS_SIGNATURE }
        runCatching { replayer.sync() }
        replayer.sync()

        // When — the app writes again
        val builder = OfflinePostgrestQueryBuilderImpl(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = store,
            queueIdentity = { QueueIdentity.Anonymous },
            headSender = replayer,
        )
        val later = builder.insertJson(rows("""[{"id":"k2","title":"Eggs"}]"""))

        // Then — it is sent at once, not queued behind a send that is no longer happening
        assertIs<MutationOutcome.Applied>(later)
        assertEquals(2, server.requests.size)
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
    }
}
