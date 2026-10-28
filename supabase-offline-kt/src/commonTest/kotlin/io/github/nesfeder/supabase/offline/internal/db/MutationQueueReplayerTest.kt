// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.internal.mutationqueue.awaitSettledSession
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRemoteTable
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.postgrestResultOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Behavioral pins for [MutationQueueReplayer] — the replay path that pushes queued offline
 * mutations to Supabase. The replayer previously had no test coverage; these cases lock in FIFO
 * replay, per-failure-class handling (network pause vs. permanent drop vs. transient 401 vs.
 * bounded unknown-error dead-letter), the single-sweep-per-table `distinct()` fix, the concurrency
 * mutex, and the refuse-to-replay-unfiltered guard for legacy null-descriptor UPDATE/DELETE rows.
 *
 * Each case drives the real [MutationQueue] over the shared in-memory DAO fakes and a scripted
 * [FakeRemoteTable]; only the remote verbs are faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MutationQueueReplayerTest {

    private val table = "projects"
    private var createdAtCounter = 0L

    @Test
    fun replays_pending_mutations_in_fifo_order_and_marks_them_synced() = runTest {
        // Given three queued INSERTs and a builder that accepts every write
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        dao.insert(insertRow("""[{"n":"b"}]"""))
        dao.insert(insertRow("""[{"n":"c"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onInsert = { postgrestResultOf("[]", client.postgrest) },
            )
        val replayer = replayer(store, client, remoteTable)

        // When
        val synced = replayer.syncTable(table = table)

        // Then: every mutation replayed once, in enqueue order, and none stays pending
        assertEquals(3, synced)
        assertEquals(
            listOf("""[{"n":"a"}]""", """[{"n":"b"}]""", """[{"n":"c"}]"""),
            remoteTable.recordedInsertBodies.map { it.toString() },
        )
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun network_failure_pauses_the_table_and_preserves_order() = runTest {
        // Given two queued INSERTs and a builder whose first write hits a connectivity failure
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        dao.insert(insertRow("""[{"n":"b"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onInsert = { throw ConnectTimeoutException("connect timed out", null) },
            )
        val replayer = replayer(store, client, remoteTable)

        // When
        val synced = replayer.syncTable(table = table)

        // Then: the whole table pauses on the first mutation; nothing synced or dropped, order kept
        assertEquals(0, synced)
        assertEquals(
            1,
            remoteTable.recordedInsertBodies.size,
            "only the first mutation was attempted",
        )
        val pending = store.mutationQueue.pendingMutationsForTable(table)
        assertEquals(
            listOf("""[{"n":"a"}]""", """[{"n":"b"}]"""),
            pending.map { it.bodyJson },
        )
    }

    @Test
    fun permanent_failure_moves_the_mutation_to_the_rejected_list_and_continues() = runTest {
        // Given two queued INSERTs; the first fails with a permanent 403, the second would succeed
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        dao.insert(insertRow("""[{"n":"b"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val forbidden = responseExceptionWithStatus(HttpStatusCode.Forbidden)
        var call = 0
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onInsert = {
                    call++
                    if (call == 1) throw forbidden else postgrestResultOf("[]", client.postgrest)
                },
            )
        val replayer = replayer(store, client, remoteTable)

        // When
        val synced = replayer.syncTable(table = table)

        // Then: the 403 mutation leaves the queue for the rejected list, the replayer continues,
        // and
        // the second mutation syncs
        assertEquals(1, synced)
        assertEquals(2, remoteTable.recordedInsertBodies.size, "both mutations were attempted")
        assertEquals(0, store.pendingMutationCount())
        assertEquals(
            listOf(403),
            store.mutationQueue.rejectedList.rejections().map { it.serverError?.status },
        )
    }

    @Test
    fun unauthorized_keeps_the_mutation_pending() = runTest {
        // Given a queued INSERT that fails with 401 — routine token-refresh race after reconnect
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val unauthorized = responseExceptionWithStatus(HttpStatusCode.Unauthorized)
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onInsert = { throw unauthorized },
            )
        val replayer = replayer(store, client, remoteTable)

        // When: 401 is transient, so it is rethrown (not permanent) — and NOT dropped
        assertFailsWith<ResponseException> { replayer.syncTable(table = table) }

        // Then: the queued write survives for a later sync (once the token is refreshed)
        assertEquals(1, store.pendingMutationCount())
    }

    @Test
    fun repeated_unauthorized_never_dead_letters_the_mutation() = runTest {
        // Given a queued INSERT that keeps failing with 401 across many sync triggers — a bad-token
        // window right after reconnect (NetworkRegained fires on every Online transition, so a
        // flapping reconnect can drive several rapid replays before supabase-kt's refresh lands)
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val unauthorized = responseExceptionWithStatus(HttpStatusCode.Unauthorized)
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onInsert = { throw unauthorized },
            )
        val replayer = replayer(store, client, remoteTable)

        // When: far more than MAX_REPLAY_FAILURES (5) consecutive 401 replays occur, each rethrows
        repeat(8) {
            assertFailsWith<ResponseException> { replayer.syncTable(table = table) }
            assertEquals(
                1,
                store.pendingMutationCount(),
                "401 is excluded from the dead-letter budget and must never drop the write",
            )
        }
    }

    /**
     * Inverted: a write that kept failing with a 500 used to be dead-lettered (deleted) on its
     * fifth replay in a process. A server error is transient, so it is now kept for as long as it
     * takes — the entry point still reports each failure to its caller.
     */
    @Test
    fun unknown_error_rethrows_on_every_replay_and_never_dead_letters() = runTest {
        // Given a queued INSERT that always fails with a 500
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val serverError = responseExceptionWithStatus(HttpStatusCode.InternalServerError)
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onInsert = { throw serverError },
            )
        val replayer = replayer(store, client, remoteTable)

        // When: far more replays than the old five-strike budget
        repeat(8) {
            assertFailsWith<ResponseException> { replayer.syncTable(table = table) }
            assertEquals(1, store.pendingMutationCount(), "mutation stays pending across retries")
        }

        // Then: every replay attempted it, and it is still queued
        assertEquals(8, remoteTable.recordedInsertBodies.size, "insert attempted on every replay")
    }

    @Test
    fun concurrent_syncAllTables_replays_each_mutation_exactly_once() =
        runTest(UnconfinedTestDispatcher()) {
            // Given one queued INSERT; the first sync to hold the lock parks inside insert() until
            // released, giving the second sync a chance to double-replay if the mutex were absent
            val dao = FakeMutationDao()
            dao.insert(insertRow("""[{"n":"a"}]"""))
            val store = buildStore(dao)
            val client = testClient()
            val reachedInsert = CompletableDeferred<Unit>()
            val releaseInsert = CompletableDeferred<Unit>()
            var insertCalls = 0
            val remoteTable =
                FakeRemoteTable(
                    table = table,
                    postgrest = client.postgrest,
                    onInsert = {
                        insertCalls++
                        if (!reachedInsert.isCompleted) reachedInsert.complete(Unit)
                        releaseInsert.await()
                        postgrestResultOf("[]", client.postgrest)
                    },
                )
            val replayer = replayer(store, client, remoteTable)

            // When: two syncAllTables run concurrently
            val first = launch { replayer.syncAllTables() }
            val second = launch { replayer.syncAllTables() }
            reachedInsert.await() // first holds the lock, parked in insert()
            releaseInsert.complete(Unit) // let it finish
            advanceUntilIdle()
            first.join()
            second.join()

            // Then: the mutex serialized both; the second saw the mutation already synced
            assertEquals(1, insertCalls, "mutation must replay exactly once")
            assertEquals(0, store.pendingMutationCount())
        }

    @Test
    fun syncAllTables_visits_each_table_once_even_with_many_pending() = runTest {
        // Given three queued INSERTs on the same table
        val dao = FakeMutationDao()
        repeat(3) { index -> dao.insert(insertRow("""[{"n":"$index"}]""")) }
        val store = buildStore(dao)
        val client = testClient()
        var factoryCalls = 0
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onInsert = { postgrestResultOf("[]", client.postgrest) },
            )
        val replayer =
            newTestMutationQueueReplayer(
                store = store,
                supabase = client,
                remoteTableFor = { _, _ ->
                    factoryCalls++
                    remoteTable
                },
            )

        // When
        val synced = replayer.syncAllTables()

        // Then: the table is swept once (not once per pending mutation), all three synced
        assertEquals(3, synced)
        assertEquals(1, factoryCalls, "a table with N pending mutations is visited once")
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun null_descriptor_delete_is_refused_and_never_executed() = runTest {
        // Given a legacy DELETE row with no captured filter descriptor
        val dao = FakeMutationDao()
        dao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.DELETE.storedName,
                bodyJson = "{}",
                filterJson = null,
                createdAtMs = createdAtCounter++,
                syncedAtMs = null,
            )
        )
        val store = buildStore(dao)
        val client = testClient()
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onDelete = {
                    postgrestResultOf("[]", client.postgrest)
                }, // would succeed if ever called
            )
        val replayer = replayer(store, client, remoteTable)

        // When
        val synced = replayer.syncTable(table = table)

        // Then: the unfiltered DELETE never fires; the row leaves the queue for the rejected list
        assertEquals(0, synced)
        assertEquals(0, remoteTable.recordedDeleteCount, "unfiltered DELETE must never execute")
        assertEquals(0, store.pendingMutationCount(), "the legacy null-filter row leaves the queue")
        assertEquals(1, store.mutationQueue.rejectedList.rejections().size)
    }

    @Test
    fun null_descriptor_update_is_refused_and_never_executed() = runTest {
        // Given a legacy UPDATE row with no captured filter descriptor
        val dao = FakeMutationDao()
        dao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.UPDATE.storedName,
                bodyJson = """{"n":"x"}""",
                filterJson = null,
                createdAtMs = createdAtCounter++,
                syncedAtMs = null,
            )
        )
        val store = buildStore(dao)
        val client = testClient()
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onUpdate = { postgrestResultOf("[]", client.postgrest) },
            )
        val replayer = replayer(store, client, remoteTable)

        // When
        val synced = replayer.syncTable(table = table)

        // Then: the unfiltered UPDATE never fires and the poison row is dropped
        assertEquals(0, synced)
        assertEquals(
            0,
            remoteTable.recordedUpdateBodies.size,
            "unfiltered UPDATE must never execute",
        )
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun partially_replayable_descriptor_update_is_refused_and_never_executed() = runTest {
        // Given an UPDATE whose stored filter decodes but loses its second condition on replay —
        // the request would carry eq(user_id) alone and rewrite every row of that user
        val dao = FakeMutationDao()
        dao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.UPDATE.storedName,
                bodyJson = """{"n":"x"}""",
                filterJson = LOSSY_FILTER_JSON,
                createdAtMs = createdAtCounter++,
                syncedAtMs = null,
            )
        )
        val store = buildStore(dao)
        val client = testClient()
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onUpdate = { postgrestResultOf("[]", client.postgrest) },
            )
        val replayer = replayer(store, client, remoteTable)

        // When
        val synced = replayer.syncTable(table = table)

        // Then: the broadened UPDATE never fires and the row is dropped rather than retried
        assertEquals(0, synced)
        assertEquals(
            0,
            remoteTable.recordedUpdateBodies.size,
            "an UPDATE missing a captured condition must never execute",
        )
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun partially_replayable_descriptor_delete_is_refused_and_never_executed() = runTest {
        // Given the same lossy filter on a DELETE, where the broadened form removes rows
        val dao = FakeMutationDao()
        dao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.DELETE.storedName,
                bodyJson = "{}",
                filterJson = LOSSY_FILTER_JSON,
                createdAtMs = createdAtCounter++,
                syncedAtMs = null,
            )
        )
        val store = buildStore(dao)
        val client = testClient()
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onDelete = { postgrestResultOf("[]", client.postgrest) },
            )
        val replayer = replayer(store, client, remoteTable)

        // When
        val synced = replayer.syncTable(table = table)

        // Then
        assertEquals(0, synced)
        assertEquals(
            0,
            remoteTable.recordedDeleteCount,
            "a DELETE missing a captured condition must never execute",
        )
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun empty_condition_descriptor_delete_is_refused_and_never_executed() = runTest {
        // Given a corrupted row whose descriptor decodes but carries no conditions at all —
        // it is non-null, so the legacy null guard lets it through
        val dao = FakeMutationDao()
        dao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.DELETE.storedName,
                bodyJson = "{}",
                filterJson = EMPTY_CONDITIONS_FILTER_JSON,
                createdAtMs = createdAtCounter++,
                syncedAtMs = null,
            )
        )
        val store = buildStore(dao)
        val client = testClient()
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onDelete = { postgrestResultOf("[]", client.postgrest) },
            )
        val replayer = replayer(store, client, remoteTable)

        // When
        val synced = replayer.syncTable(table = table)

        // Then: it would have replayed as a fully unfiltered DELETE
        assertEquals(0, synced)
        assertEquals(
            0,
            remoteTable.recordedDeleteCount,
            "a descriptor with no conditions must never execute",
        )
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun fully_replayable_descriptor_update_still_executes() = runTest {
        // Given the same two-condition filter with a value the replay path can parse back. This
        // is the counterweight to the two refusals above: the gate is fail-closed, so it must be
        // shown not to reject a healthy queued write.
        val dao = FakeMutationDao()
        dao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.UPDATE.storedName,
                bodyJson = """{"n":"x"}""",
                filterJson = REPLAYABLE_FILTER_JSON,
                createdAtMs = createdAtCounter++,
                syncedAtMs = null,
            )
        )
        val store = buildStore(dao)
        val client = testClient()
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onUpdate = { postgrestResultOf("[]", client.postgrest) },
            )
        val replayer = replayer(store, client, remoteTable)

        // When
        val synced = replayer.syncTable(table = table)

        // Then
        assertEquals(1, synced)
        assertEquals(
            1,
            remoteTable.recordedUpdateBodies.size,
            "a fully captured filter still replays",
        )
    }

    @Test
    fun fully_replayable_descriptor_delete_still_executes() = runTest {
        // Given a queued DELETE whose stored filter replays in full — the success path the
        // previous standalone engine pinned with an unfiltered row, which this engine refuses
        // (see null_descriptor_delete_is_refused_and_never_executed)
        val dao = FakeMutationDao()
        dao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.DELETE.storedName,
                bodyJson = "{}",
                filterJson = REPLAYABLE_FILTER_JSON,
                createdAtMs = createdAtCounter++,
                syncedAtMs = null,
            )
        )
        val store = buildStore(dao)
        val client = testClient()
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onDelete = { postgrestResultOf("[]", client.postgrest) },
            )
        val replayer = replayer(store, client, remoteTable)

        // When
        val synced = replayer.syncTable(table = table)

        // Then: the DELETE replays once, counts as synced and leaves nothing pending
        assertEquals(1, synced)
        assertEquals(1, remoteTable.recordedDeleteCount, "a fully captured filter still replays")
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun a_sync_that_meets_a_running_pass_waits_for_the_pass_and_runs_once_more_not_once_per_write() =
        runTest {
            // Given — three queued INSERTs, and a pass of the library's own sending them, each
            // held at the server until the test lets it through
            val dao = FakeMutationDao()
            repeat(3) { index -> dao.insert(insertRow("""[{"n":"$index"}]""")) }
            val store = buildStore(dao)
            val client = testClient()
            val answers = List(3) { CompletableDeferred<Unit>() }
            var sends = 0
            val remoteTable =
                FakeRemoteTable(
                    table = table,
                    postgrest = client.postgrest,
                    onInsert = {
                        answers[sends++].await()
                        postgrestResultOf("[]", client.postgrest)
                    },
                )
            var passes = 0
            val replayer =
                newTestMutationQueueReplayer(
                    store = store,
                    supabase = client,
                    remoteTableFor = { _, _ -> remoteTable },
                    passEnded = { _, _ -> passes++ },
                )
            val own = async { replayer.sync() }
            runCurrent()

            // When — the app syncs while that pass has the first write on the wire, and the
            // server then answers one write at a time
            val app = async { replayer.syncWaitingOutOtherSenders() }
            runCurrent()
            val passesWhileHeld = passes
            answers.forEach { answer ->
                answer.complete(Unit)
                runCurrent()
            }

            // Then — the library's pass sent all three; the app's call ran one pass that found
            // the head in flight, waited the whole pass out, and ran one more: three passes in
            // all, not one per write
            assertEquals(3, own.await().syncedCount)
            val report = app.await()
            assertEquals(0 to 0, report.syncedCount to report.remainingCount)
            assertEquals(null, report.stop)
            assertEquals(1, passesWhileHeld)
            assertEquals(3, passes)
            assertEquals(3, remoteTable.recordedInsertBodies.size)
            assertEquals(0, store.pendingMutationCount())
        }

    @Test
    fun a_sync_behind_a_pass_whose_caller_was_canceled_waits_for_its_write_then_runs_once_more() =
        runTest {
            // Given — three queued INSERTs; a pass of the library's own has the first on the wire,
            // held at the server, and the app's sync waits behind it
            val dao = FakeMutationDao()
            repeat(3) { index -> dao.insert(insertRow("""[{"n":"$index"}]""")) }
            val store = buildStore(dao)
            val client = testClient()
            val answers = List(3) { CompletableDeferred<Unit>() }
            var sends = 0
            val remoteTable =
                FakeRemoteTable(
                    table = table,
                    postgrest = client.postgrest,
                    onInsert = {
                        answers[sends++].await()
                        postgrestResultOf("[]", client.postgrest)
                    },
                )
            var passes = 0
            val replayer =
                newTestMutationQueueReplayer(
                    store = store,
                    supabase = client,
                    remoteTableFor = { _, _ -> remoteTable },
                    // A sync that ran pass after pass behind the held write would never suspend,
                    // and the scheduler would never come back to the test: fail it instead
                    passEnded = { _, _ ->
                        passes++
                        check(passes <= 4) { "the sync ran $passes passes behind one held write" }
                    },
                )
            val own = async { replayer.sync() }
            runCurrent()
            val app = async { replayer.syncWaitingOutOtherSenders() }
            runCurrent()

            // When — the pass's caller gives up while the write is on the wire; the pass finishes
            // that request, which the server then answers, and the two writes after it
            own.cancel()
            runCurrent()
            val passesAfterTheCancel = passes
            val waitingAfterTheCancel = !app.isCompleted
            answers.forEach { answer ->
                answer.complete(Unit)
                runCurrent()
            }

            // Then — the app's call waited for the held write to land, then ran one more pass
            // that sent the other two: one pass that found the head in flight and one after,
            // however long the write was on the wire
            assertTrue(own.isCancelled)
            assertEquals(1, passesAfterTheCancel)
            assertTrue(waitingAfterTheCancel)
            val report = app.await()
            assertEquals(2 to 0, report.syncedCount to report.remainingCount)
            assertEquals(null, report.stop)
            assertEquals(2, passes)
            assertEquals(3, remoteTable.recordedInsertBodies.size)
            assertEquals(0, store.pendingMutationCount())
        }

    @Test
    fun a_sync_behind_a_pass_that_stops_early_waits_it_out_then_sends_what_it_left() = runTest {
        // Given — three queued INSERTs; a pass of the library's own has the first on the wire,
        // held at the server, which will answer the second with a 503
        val dao = FakeMutationDao()
        repeat(3) { index -> dao.insert(insertRow("""[{"n":"$index"}]""")) }
        val store = buildStore(dao)
        val client = testClient()
        val unavailable = responseExceptionWithStatus(HttpStatusCode.ServiceUnavailable)
        val held = CompletableDeferred<Unit>()
        var sends = 0
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onInsert = {
                    when (++sends) {
                        1 -> held.await()
                        2 -> throw unavailable
                    }
                    postgrestResultOf("[]", client.postgrest)
                },
            )
        var passes = 0
        val replayer =
            newTestMutationQueueReplayer(
                store = store,
                supabase = client,
                remoteTableFor = { _, _ -> remoteTable },
                passEnded = { _, _ -> passes++ },
            )
        val own = async { replayer.sync() }
        runCurrent()
        val app = async { replayer.syncWaitingOutOtherSenders() }
        runCurrent()

        // When — the server answers the first write, refuses the second for the moment, and
        // accepts what comes after
        held.complete(Unit)
        runCurrent()

        // Then — the library's pass stopped at the 503 with two writes queued; the app's call
        // waited it out, then ran one pass that sent them both: three passes in all
        val ownReport = own.await()
        assertEquals(1, ownReport.syncedCount)
        assertIs<SyncStop.TransientFailure>(ownReport.stop)
        val report = app.await()
        assertEquals(2 to 0, report.syncedCount to report.remainingCount)
        assertEquals(null, report.stop)
        assertEquals(3, passes)
        assertEquals(
            listOf("""[{"n":"0"}]""", """[{"n":"1"}]""", """[{"n":"1"}]""", """[{"n":"2"}]"""),
            remoteTable.recordedInsertBodies.map { it.toString() },
        )
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun a_sync_that_meets_a_send_at_the_head_waits_for_that_send_then_sends_what_is_left() =
        runTest {
            // Given — two queued INSERTs; the first is being sent at the head of the queue, held
            // at the server
            val dao = FakeMutationDao()
            val first = dao.insert(insertRow("""[{"n":"a"}]"""))
            dao.insert(insertRow("""[{"n":"b"}]"""))
            val store = buildStore(dao)
            val client = testClient()
            val remoteTable =
                FakeRemoteTable(
                    table = table,
                    postgrest = client.postgrest,
                    onInsert = { postgrestResultOf("[]", client.postgrest) },
                )
            val replayer = replayer(store, client, remoteTable)
            val answered = CompletableDeferred<Unit>()
            val head = async {
                replayer.sendAtHead(
                    mutationId = first,
                    identity = QueueIdentity.Anonymous,
                    send = {
                        answered.await()
                        postgrestResultOf("[]", client.postgrest)
                    },
                    onAccepted = {},
                )
            }
            runCurrent()

            // When — the app syncs while that send is on the wire, which then lands
            val sync = async { replayer.syncWaitingOutOtherSenders() }
            runCurrent()
            val returnedWhileHeld = sync.isCompleted
            answered.complete(Unit)
            runCurrent()

            // Then — the send landed; the sync waited for it, then sent the second write, once
            assertIs<MutationQueueReplayer.HeadSendOutcome.Sent<*>>(head.await())
            assertFalse(returnedWhileHeld)
            val report = sync.await()
            assertEquals(1 to 0, report.syncedCount to report.remainingCount)
            assertEquals(
                listOf("""[{"n":"b"}]"""),
                remoteTable.recordedInsertBodies.map { it.toString() },
            )
            assertEquals(0, store.pendingMutationCount())
        }

    // ── Test helpers ──────────────────────────────────────────────────────────────

    private fun insertRow(body: String): MutationRow =
        MutationRow(
            table = table,
            operation = MutationOperation.INSERT.storedName,
            bodyJson = body,
            filterJson = null,
            createdAtMs = createdAtCounter++,
            syncedAtMs = null,
        )

    @Test
    fun replay_is_deferred_while_the_auth_session_is_still_restoring() = runTest {
        // Given a queued INSERT and a session that never leaves the cold-start restore window
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onInsert = { postgrestResultOf("[]", client.postgrest) },
            )
        val replayer = replayer(store, client, remoteTable, awaitSessionSettled = { false })

        // When both public entry points fire
        val syncedForTable = replayer.syncTable(table = table)
        val syncedForAll = replayer.syncAllTables()

        // Then nothing reaches the wire — the write is neither replayed on the anon key (where
        // RLS would 403 it into the permanent-failure drop) nor removed from the queue
        assertEquals(0, syncedForTable)
        assertEquals(0, syncedForAll)
        assertEquals(0, remoteTable.recordedInsertBodies.size)
        assertEquals(1, store.pendingMutationCount())
    }

    @Test
    fun replay_proceeds_once_the_auth_session_settles() = runTest {
        // Given a queued INSERT and a session that settles between two triggers
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val remoteTable =
            FakeRemoteTable(
                table = table,
                postgrest = client.postgrest,
                onInsert = { postgrestResultOf("[]", client.postgrest) },
            )
        var settled = false
        val replayer = replayer(store, client, remoteTable, awaitSessionSettled = { settled })

        // When the first trigger races restore and the second lands after it settles — the
        // second going through syncAllTables so the post-deferral proceed path is pinned on
        // both public entry points
        val deferredCount = replayer.syncTable(table = table)
        settled = true
        val replayedCount = replayer.syncAllTables()

        // Then the deferred write replays intact on the second trigger
        assertEquals(0, deferredCount)
        assertEquals(1, replayedCount)
        assertEquals(
            listOf("""[{"n":"a"}]"""),
            remoteTable.recordedInsertBodies.map { it.toString() },
        )
        assertEquals(0, store.pendingMutationCount())
    }

    /**
     * Builds the replayer under test. When [awaitSessionSettled] is omitted the gate is the
     * production default, [awaitSettledSession] — and because [testClient] installs Postgrest but
     * not Auth, every existing case also pins the fail-open path (no Auth plugin ⇒ replay proceeds,
     * no wait).
     */
    private fun replayer(
        store: TestStore,
        client: SupabaseClient,
        remoteTable: FakeRemoteTable,
        awaitSessionSettled: (suspend () -> Boolean)? = null,
    ): MutationQueueReplayer =
        newTestMutationQueueReplayer(
            store = store,
            supabase = client,
            remoteTableFor = { _, _ -> remoteTable },
            awaitSessionSettled = awaitSessionSettled ?: { client.awaitSettledSession() },
        )

    private fun testClient(): SupabaseClient = newTestSupabaseClient()

    private fun buildStore(mutationDao: FakeMutationDao): TestStore =
        newTestStore(mutationDao = mutationDao)

    /**
     * Builds a real [ResponseException] carrying [status] by letting a `MockEngine`-backed client
     * fail an `expectSuccess` request — the only reliable way to produce the HTTP-status-bearing
     * exception type that [io.github.nesfeder.supabase.offline.internal.httpStatusOrNull] inspects.
     */
    private suspend fun responseExceptionWithStatus(status: HttpStatusCode): ResponseException {
        val client =
            HttpClient(MockEngine) {
                engine { addHandler { respondError(status) } }
                expectSuccess = true
            }
        val thrown =
            assertFailsWith<ResponseException> {
                client.get("https://example.com/rest/v1/$table")
            }
        val response: HttpResponse = thrown.response
        check(response.status == status)
        return thrown
    }

    private companion object {
        /**
         * A stored descriptor whose `in` condition carries the bracket shape. It is not decodable
         * JSON, so replay drops it and the request would go out scoped by `eq(user_id)` alone — the
         * broadening this guard exists to refuse.
         */
        const val LOSSY_FILTER_JSON =
            """{"mode":"AND","conditions":[""" +
                """{"column":"user_id","operatorId":"eq","value":"u1"},""" +
                """{"column":"status","operatorId":"in","value":"[a,b]"}]}"""

        /**
         * A descriptor that decodes to a non-null value carrying nothing — non-null, so the legacy
         * null-filter guard does not catch it, yet it replays with no filter at all.
         */
        const val EMPTY_CONDITIONS_FILTER_JSON = """{"mode":"AND","conditions":[]}"""

        /** The same two columns with an `in` value replay can parse back in full. */
        const val REPLAYABLE_FILTER_JSON =
            """{"mode":"AND","conditions":[""" +
                """{"column":"user_id","operatorId":"eq","value":"u1"},""" +
                """{"column":"status","operatorId":"in","value":"(a,b)"}]}"""
    }
}
