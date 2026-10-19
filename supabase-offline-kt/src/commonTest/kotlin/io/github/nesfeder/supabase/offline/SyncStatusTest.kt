// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.db.REJECTED_MUTATIONS_CACHE_KEY
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.MOVED_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.VERSION_COLUMN
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.renameTask
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `syncStatus` is one value an app draws a sync banner from: the network, the current user's queued
 * and refused writes, whether a pass runs, when the queue was last emptied and why the last pass
 * stopped. Its counts follow the queue shortly after each change, so these tests wait for the value
 * they expect, on real threads, as an app's status does.
 */
class SyncStatusTest {

    /** Waits, at most ten seconds, for a status [accepted] takes. */
    private suspend fun StateFlow<SyncStatus>.awaiting(
        accepted: (SyncStatus) -> Boolean
    ): SyncStatus = withContext(Dispatchers.Default) { withTimeout(10.seconds) { first(accepted) } }

    @Test
    fun the_status_follows_the_queue_and_says_when_it_was_emptied() = runTest {
        // Given — two writes queued offline
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        val status = client.syncStatus
        client.from(TABLE).insert(row(1))
        client.from(TABLE).insert(row(2))
        val queued = status.awaiting { it.pendingMutationCount == 2 }

        // When — the network is back and the app syncs
        server.answer = { okJson("[]") }
        client.syncPendingMutations()
        val drained = status.awaiting { it.pendingMutationCount == 0 && it.lastSyncedAt != null }

        // Then
        assertNull(queued.lastSyncedAt)
        assertNull(drained.lastSyncError)
        client.close()
    }

    @Test
    fun the_status_counts_the_current_users_refused_writes() = runTest {
        // Given — a write queued offline that the server refuses for good
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        val status = client.syncStatus
        client.from(TABLE).insert(row(1))
        server.answer = { postgrestError(HttpStatusCode.UnprocessableEntity) }
        val id = client.syncPendingMutations().rejected.single().id

        // When
        val refused = status.awaiting { it.rejectedMutationCount == 1 }
        client.dismissRejectedMutation(id)
        val dismissed = status.awaiting { it.rejectedMutationCount == 0 }

        // Then
        assertEquals(0, refused.pendingMutationCount)
        assertEquals(0, dismissed.rejectedMutationCount)
        client.close()
    }

    @Test
    fun a_write_moving_between_the_queue_and_the_rejected_list_is_counted_once() = runTest {
        // Given — a write the server refuses for good every time it is sent, on the rejected list,
        // and a collector recording every status it is handed
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        val status = client.syncStatus
        client.from(TABLE).insert(row(1))
        server.answer = { postgrestError(HttpStatusCode.UnprocessableEntity) }
        var id = client.syncPendingMutations().rejected.single().id
        val seen = mutableListOf<SyncStatus>()
        val recorder = launch(Dispatchers.Default) { status.collect { value -> seen += value } }

        // When — it is retried, and refused again on the pass the retry asks for, many times
        repeat(RETRIES) {
            val retried = id
            client.retryRejectedMutation(retried)
            id =
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) {
                        client
                            .rejectedMutations()
                            .first { entries ->
                                entries.singleOrNull()?.id.let { it != null && it != retried }
                            }
                            .single()
                            .id
                    }
                }
        }
        status.awaiting { it.rejectedMutationCount == 1 && it.pendingMutationCount == 0 }
        recorder.cancelAndJoin()

        // Then — every status counted the write in exactly one place, once it was counted at all
        val counted = seen.map { value -> value.pendingMutationCount + value.rejectedMutationCount }
        assertEquals(listOf(), counted.dropWhile { it == 0 }.filter { it != 1 }, "counts: $counted")
        client.close()
    }

    @Test
    fun a_write_moving_between_the_queue_and_the_conflicted_list_is_counted_once() = runTest {
        // Given — a task renamed here while the server could not be reached, renamed elsewhere
        // meanwhile, a resolver that holds the conflict, and a collector recording every status
        val server = newGuardedServer()
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                config =
                    Offline.createConfig {
                        versionColumn(GUARDED_TABLE, VERSION_COLUMN)
                        conflictResolver = ConflictResolver { ConflictResolution.Hold }
                    },
            )
        val status = client.syncStatus
        val seen = mutableListOf<SyncStatus>()
        val recorder = launch(Dispatchers.Default) { status.collect { value -> seen += value } }
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        client.from(GUARDED_TABLE).select()
        server.failsRequest = { true }
        client.renameTask(1, "mine")
        server.replaceRow(GUARDED_TABLE, taskRow(1, "elsewhere", version = MOVED_VERSION))
        server.failsRequest = null

        // When — the replay parks it, and the app keeps its change while the network is off, so
        // the pass the resolution asks for cannot send it until the recorder has stopped
        client.syncPendingMutations()
        val parked = status.awaiting { it.conflictedMutationCount == 1 }
        client.disableNetwork()
        client.resolveConflictedMutation(
            client.conflictedMutations().first().single().id,
            ConflictResolution.KeepLocal,
        )
        val requeued = status.awaiting {
            it.conflictedMutationCount == 0 && it.pendingMutationCount == 1
        }
        recorder.cancelAndJoin()
        client.enableNetwork()
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) { client.pendingMutationCountFlow().first { it == 0 } }
        }

        // Then — once counted, every status counted the write in exactly one place, and it was
        // sent once the network was back
        assertEquals(0, parked.pendingMutationCount)
        assertEquals(0, requeued.rejectedMutationCount)
        val counted = seen.map { value ->
            value.pendingMutationCount + value.conflictedMutationCount
        }
        assertEquals(listOf(), counted.dropWhile { it == 0 }.filter { it != 1 }, "counts: $counted")
        assertTrue(seen.any { it.conflictedMutationCount == 1 }, "counts: $counted")
        assertEquals(JsonPrimitive("mine"), server.rows(GUARDED_TABLE).single()["title"])
        client.close()
    }

    @Test
    fun clearing_local_data_clears_the_last_sync_error() = runTest {
        // Given — a pass that stopped offline, with a write still queued
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        client.from(TABLE).insert(row(1))
        client.syncPendingMutations()
        val stopped = client.syncStatus.awaiting { it.lastSyncError != null }

        // When
        client.clearLocalData()

        // Then — the writes it held back are gone, and so is the error
        assertEquals(true, stopped.lastSyncError?.isNetworkOrConnectivityFailure())
        assertNull(client.syncStatus.awaiting { it.pendingMutationCount == 0 }.lastSyncError)
        client.close()
    }

    @Test
    fun the_status_says_a_pass_is_running_while_one_is() = runTest {
        // Given — a write queued offline, and a server that holds its replay
        val held = CompletableDeferred<Unit>()
        var online = false
        val server =
            RecordingPostgrestServer(
                answer = {
                    if (!online) connectivityFailure()
                    held.await()
                    okJson("[]")
                }
            )
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        val status = client.syncStatus
        client.from(TABLE).insert(row(1))
        online = true

        // When
        val sync = async(Dispatchers.Default) { client.syncPendingMutations() }
        val running = status.awaiting { it.isSyncing }
        held.complete(Unit)
        sync.await()
        val done = status.awaiting { !it.isSyncing && it.pendingMutationCount == 0 }

        // Then
        assertEquals(true, running.isSyncing)
        assertNotNull(done.lastSyncedAt)
        client.close()
    }

    @Test
    fun a_pass_running_at_close_leaves_the_status_with_no_pass_running_and_it_changes_no_more() =
        runTest {
            // Given — a write queued offline, and a pass sending it, held at the server
            val arrived = CompletableDeferred<Unit>()
            val held = CompletableDeferred<Unit>()
            var online = false
            val server =
                RecordingPostgrestServer(
                    answer = {
                        if (!online) connectivityFailure()
                        arrived.complete(Unit)
                        held.await()
                        okJson("[]")
                    }
                )
            val client = newTestOfflineClient(supabase = server.supabaseClient())
            val status = client.syncStatus
            client.from(TABLE).insert(row(1))
            online = true
            val sync = async(Dispatchers.Default) { runCatching { client.syncPendingMutations() } }
            withContext(Dispatchers.Default) { withTimeout(10.seconds) { arrived.await() } }
            status.awaiting { it.isSyncing }

            // When — the client closes with the pass on the wire, then the server answers
            client.close()
            val atClose = status.value
            held.complete(Unit)
            sync.await()

            // Then — the last value says no pass runs, it is still the value after the pass ended,
            // and the status reads the same flow, without throwing, once closed
            assertFalse(atClose.isSyncing)
            assertSame(status, client.syncStatus)
            assertEquals(atClose, client.syncStatus.value)
        }

    @Test
    fun the_status_counts_what_an_earlier_run_left_queued_before_it_is_first_read() = runTest {
        // Given — a write an earlier run left queued
        val mutationDao = FakeMutationDao()
        newTestStore(mutationDao = mutationDao).mutationQueue.enqueueInsert(TABLE, row(1))

        // When — a client opens over it, and nobody reads its status yet
        val client =
            newTestOfflineClient(
                mutationDao = mutationDao,
                config = Offline.Config().apply { autoSync = false },
            )
        val countedBeforeTheFirstRead =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(10.seconds) {
                    while (mutationDao.pendingCounts == 0) delay(5.milliseconds)
                    true
                }
            }

        // Then — the queue was counted from the moment the client opened
        assertEquals(true, countedBeforeTheFirstRead, "nothing counted the queue until it was read")
        assertEquals(
            1,
            client.syncStatus.awaiting { it.pendingMutationCount == 1 }.pendingMutationCount,
        )
        client.close()
    }

    @Test
    fun the_status_counts_follow_the_queue_after_a_failed_read_of_the_rejected_list() = runTest {
        // Given — reads of the rejected list fail for a moment, as a busy or failing disk would
        var failing = true
        val cacheDao =
            FakeQueryCacheDao().apply {
                onGetSnapshot = { table, key ->
                    if (
                        failing &&
                            table == RESERVED_TABLE_NAME &&
                            key == REJECTED_MUTATIONS_CACHE_KEY
                    )
                        error("Scripted failure reading the rejected list")
                }
            }
        val logs = RecordingLogSink()
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                queryCacheDao = cacheDao,
                engineLogger = logs.asEngineLogger(),
            )
        val status = client.syncStatus
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) {
                while (logs.entries.none { "IllegalStateException" in it.message }) delay(
                    10.milliseconds
                )
            }
            delay(300.milliseconds)
        }
        failing = false

        // When — a write is queued offline
        client.from(TABLE).insert(row(1))
        val counted = runCatching { status.awaiting { it.pendingMutationCount == 1 } }

        // Then
        assertTrue(
            counted.isSuccess,
            "the status never counted the queued write (status=${status.value}); ${logs.entries}",
        )
        client.close()
    }

    @Test
    fun the_status_can_be_read_after_close_and_holds_its_last_value() = runTest {
        // Given — a client whose status nobody read while it was open
        val client = newTestOfflineClient()

        // When
        client.close()
        val status = client.syncStatus

        // Then
        assertEquals(false, status.value.isSyncing)
        assertSame(status, client.syncStatus)
    }

    private fun row(id: Int): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("id", id)
                put("title", "note $id")
            }
        )
    }

    private companion object {
        const val TABLE = "notes"
        const val RETRIES = 20
    }
}
