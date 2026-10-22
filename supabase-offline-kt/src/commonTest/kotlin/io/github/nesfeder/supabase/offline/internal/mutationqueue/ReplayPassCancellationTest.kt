// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.testdoubles.FakeRemoteTable
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.postgrestResultOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * A pass is reported as cleared, with nothing left queued, only when local data was cleared under
 * it. A cancellation from anywhere else is a failure of that pass alone: reported as one, with the
 * queue standing for a later pass, never thrown at a caller that was not canceled.
 */
class ReplayPassCancellationTest {

    private val logger = EngineLogger(null, EngineLogLevel.INFO)

    @Test
    fun a_cancellation_the_transport_throws_is_a_failure_of_the_pass_not_a_clear() = runTest {
        // Given — a queued insert, and a transport that cancels its request instead of answering
        val store = newTestStore()
        store.mutationQueue.enqueueInsert(TABLE, note())
        val replayer = replayerOver(store) { throw CancellationException(TRANSPORT_CANCELLATION) }

        // When
        val report = replayer.sync()

        // Then — a failure on the device, with the cancellation as its cause, and the write stays
        val stop = assertIs<SyncStop.TransientFailure>(report.stop)
        val cause = assertIs<IllegalStateException>(stop.cause)
        assertEquals(TRANSPORT_CANCELLATION, cause.cause?.message)
        assertEquals(1 to 0, report.remainingCount to report.syncedCount)
        assertEquals(1, store.pendingMutationCount())
    }

    @Test
    fun the_librarys_own_passes_go_on_after_a_cancellation_the_transport_threw() = runTest {
        // Given — a queued insert, a transport that cancels the first request and answers the
        // next, and the engine's own pass loop over the replayer
        val store = newTestStore()
        store.mutationQueue.enqueueInsert(TABLE, note())
        var sends = 0
        val replayer =
            replayerOver(store) { postgrest ->
                if (++sends == 1) throw CancellationException(TRANSPORT_CANCELLATION)
                postgrestResultOf("[]", postgrest)
            }
        val automaticReplay =
            AutomaticReplay(scope = backgroundScope, engineLogger = logger) { replayer.sync() }

        // When — a pass fails on the cancellation, and another is asked for after the wait a
        // failed pass leaves
        automaticReplay.requestPass()
        runCurrent()
        val leftByTheFirst = store.pendingMutationCount()
        automaticReplay.requestPass()
        advanceTimeBy(2.seconds)
        runCurrent()

        // Then — the loop survived the cancellation, and its next pass sent the write
        assertEquals(1, leftByTheFirst)
        assertEquals(2, sends)
        assertEquals(0, store.pendingMutationCount())
    }

    /** A replayer over [store] whose every INSERT goes to [onInsert]. */
    private fun replayerOver(
        store: TestStore,
        onInsert: suspend (postgrest: Postgrest) -> PostgrestResult,
    ): MutationQueueReplayer {
        val supabase = RecordingPostgrestServer().supabaseClient()
        return newTestMutationQueueReplayer(
            store = store,
            supabase = supabase,
            remoteTableFor = { _, table ->
                FakeRemoteTable(
                    table = table,
                    postgrest = supabase.postgrest,
                    onInsert = { onInsert(supabase.postgrest) },
                )
            },
        )
    }

    private fun note(): JsonArray = Json.parseToJsonElement("""[{"title":"note"}]""") as JsonArray

    private companion object {
        const val TABLE = "notes"
        const val TRANSPORT_CANCELLATION = "The request's own cancellation"
    }
}
