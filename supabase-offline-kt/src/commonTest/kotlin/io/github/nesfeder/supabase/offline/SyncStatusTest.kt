// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.db.CACHE_OWNER_CACHE_KEY
import io.github.nesfeder.supabase.offline.internal.db.LAST_SYNCED_AT_CACHE_KEY
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
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.renameTask
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
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
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
        val counted =
            seen
                .dropWhile { value -> !value.isQueueCounted }
                .map { value -> value.pendingMutationCount + value.rejectedMutationCount }
        assertEquals(listOf(), counted.filter { it != 1 }, "counts: $counted")
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
        // (the recorder started before the write was made, so a counted empty queue comes first)
        val counted =
            seen
                .dropWhile { value -> !value.isQueueCounted }
                .map { value -> value.pendingMutationCount + value.conflictedMutationCount }
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
    fun the_last_sync_time_is_kept_for_the_next_client_over_the_same_data() = runTest {
        // Given — a write queued offline, then sent by a pass; the client is closed, as a killed
        // app's is
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        val first =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                queryCacheDao = queryCacheDao,
                mutationDao = mutationDao,
            )
        first.from(TABLE).insert(row(1))
        server.answer = { okJson("[]") }
        first.syncPendingMutations()
        val synced =
            first.syncStatus.awaiting { it.lastSyncedAt != null && it.pendingMutationCount == 0 }
        first.close()

        // When — the next client opens over the same data, and nothing is sent
        val second =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                queryCacheDao = queryCacheDao,
                mutationDao = mutationDao,
                config = Offline.Config().apply { autoSync = false },
            )
        val counted = second.syncStatus.awaiting { it.isQueueCounted }

        // Then — its first counted value carries the time, to the millisecond, as the row kept it
        // with whose queue it was
        val syncedAt = assertNotNull(synced.lastSyncedAt)
        assertEquals(syncedAt, counted.lastSyncedAt)
        assertEquals(0, syncedAt.nanosecondsOfSecond % 1_000_000)
        assertEquals(
            """{"version":1,"owner":"anonymous","syncedAtMs":${syncedAt.toEpochMilliseconds()}}""",
            queryCacheDao.getRow(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY)?.payload,
        )
        second.close()
    }

    @Test
    fun the_last_sync_time_is_one_users_and_never_shows_for_the_next() = runTest {
        // Given — A signed in, with a write queued offline then sent
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val supabase = newTestSupabaseClientWithAuth(server.engine)
        supabase.signInForTest("A")
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        val client =
            newTestOfflineClient(
                supabase = supabase,
                queryCacheDao = queryCacheDao,
                mutationDao = mutationDao,
            )
        client.from(TABLE).insert(row(1))
        server.answer = { okJson("[]") }
        client.syncPendingMutations()
        val synced =
            client.syncStatus.awaiting { it.lastSyncedAt != null && it.pendingMutationCount == 0 }

        // When — B signs in, and the cache settles as B's
        supabase.signInForTest("B")
        val forB = client.syncStatus.awaiting { it.lastSyncedAt == null }
        client.close()

        // Then — A's time went with A's cache, in the status and on the device: a client opening
        // as B starts without it
        assertNotNull(synced.lastSyncedAt)
        assertTrue(forB.isQueueCounted)
        assertNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY))
        val relaunched =
            newTestOfflineClient(
                supabase = supabase,
                queryCacheDao = queryCacheDao,
                mutationDao = mutationDao,
                config = Offline.Config().apply { autoSync = false },
            )
        assertNull(relaunched.syncStatus.awaiting { it.isQueueCounted }.lastSyncedAt)
        relaunched.close()
    }

    @Test
    fun clearing_local_data_clears_the_last_sync_time_for_good() = runTest {
        // Given — a queue emptied by sending, its time kept
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                queryCacheDao = queryCacheDao,
                mutationDao = mutationDao,
            )
        client.from(TABLE).insert(row(1))
        server.answer = { okJson("[]") }
        client.syncPendingMutations()
        client.syncStatus.awaiting { it.lastSyncedAt != null && it.pendingMutationCount == 0 }
        assertNotNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY))

        // When
        client.clearLocalData()

        // Then — gone from the status as the clear returns, and from the device: the next client
        // starts without it
        assertNull(client.syncStatus.value.lastSyncedAt)
        assertNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY))
        client.close()
        val next =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                queryCacheDao = queryCacheDao,
                mutationDao = mutationDao,
                config = Offline.Config().apply { autoSync = false },
            )
        assertNull(next.syncStatus.awaiting { it.isQueueCounted }.lastSyncedAt)
        next.close()
    }

    @Test
    fun a_kept_time_is_restored_only_when_it_is_the_cache_owners() = runTest {
        // Given — three devices: one whose kept time is the recorded cache owner's, one whose kept
        // time is another identity's, as a drop that failed part-way leaves, and one whose record
        // this build cannot read
        val owners = FakeQueryCacheDao()
        owners.seedPayload(RESERVED_TABLE_NAME, CACHE_OWNER_CACHE_KEY, ownerRecord("user:A"))
        owners.seedPayload(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY, keptRecord("user:A"))
        val anothers = FakeQueryCacheDao()
        anothers.seedPayload(RESERVED_TABLE_NAME, CACHE_OWNER_CACHE_KEY, ownerRecord("user:B"))
        anothers.seedPayload(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY, keptRecord("user:A"))
        val unreadable = FakeQueryCacheDao()
        unreadable.seedPayload(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY, """{"version":2}""")

        // When — a client opens over each
        val restored = firstCountedStatusOver(owners)
        val notRestored = firstCountedStatusOver(anothers)
        val notRead = firstCountedStatusOver(unreadable)

        // Then — only the owner's time shows, and the other records are removed
        assertEquals(KEPT_AT, restored.lastSyncedAt)
        assertNull(notRestored.lastSyncedAt)
        assertNull(anothers.getRow(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY))
        assertNull(notRead.lastSyncedAt)
        assertNull(unreadable.getRow(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY))
    }

    @Test
    fun a_failed_read_or_write_of_the_kept_time_is_logged_and_leaves_the_status_right() = runTest {
        // Given — a device whose reads of the kept time fail, and whose write of it will fail
        val queryCacheDao =
            FakeQueryCacheDao().apply {
                seedPayload(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY, keptRecord("anonymous"))
                onGetSnapshot = { table, key ->
                    if (table == RESERVED_TABLE_NAME && key == LAST_SYNCED_AT_CACHE_KEY) {
                        error("Scripted failure reading the kept time")
                    }
                }
                failNextUpsertOf { row -> row.cacheKey == LAST_SYNCED_AT_CACHE_KEY }
            }
        val logs = RecordingLogSink()
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                queryCacheDao = queryCacheDao,
                engineLogger = logs.asEngineLogger(),
            )

        // When — the status counts, then a write is queued and sent
        val counted = client.syncStatus.awaiting { it.isQueueCounted }
        client.from(TABLE).insert(row(1))
        server.answer = { okJson("[]") }
        client.syncPendingMutations()
        val synced =
            client.syncStatus.awaiting { it.lastSyncedAt != null && it.pendingMutationCount == 0 }

        // Then — the status started without the time it could not read, has the time of the send
        // it could not keep, and said so each time
        assertNull(counted.lastSyncedAt)
        assertNotNull(synced.lastSyncedAt)
        assertEquals(
            keptRecord("anonymous"),
            queryCacheDao.getRow(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY)?.payload,
        )
        assertTrue(
            logs.entries.any { "Could not read when the queue was last emptied" in it.message },
            "${'$'}{logs.entries}",
        )
        assertTrue(
            logs.entries.any { "Could not keep when the queue was last emptied" in it.message },
            "${'$'}{logs.entries}",
        )
        client.close()
    }

    /** The first counted status of a client opened over [queryCacheDao], with nothing to send. */
    private suspend fun firstCountedStatusOver(queryCacheDao: FakeQueryCacheDao): SyncStatus {
        val client =
            newTestOfflineClient(
                queryCacheDao = queryCacheDao,
                config = Offline.Config().apply { autoSync = false },
            )
        return client.syncStatus.awaiting { it.isQueueCounted }.also { client.close() }
    }

    /** The cache owner record naming [owner]. */
    private fun ownerRecord(owner: String): String = """{"version":1,"owner":"$owner"}"""

    /** The last-synced record of [owner]'s queue emptied at [KEPT_AT]. */
    private fun keptRecord(owner: String): String =
        """{"version":1,"owner":"$owner","syncedAtMs":${KEPT_AT.toEpochMilliseconds()}}"""

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
    fun the_status_says_when_its_counts_have_been_read() = runTest {
        // Given — a write an earlier run left queued, and a queue whose first count is held, as a
        // slow disk would hold it
        val countReading = CompletableDeferred<Unit>()
        val countReleased = CompletableDeferred<Unit>()
        val mutationDao = FakeMutationDao()
        newTestStore(mutationDao = mutationDao).mutationQueue.enqueueInsert(TABLE, row(1))
        mutationDao.beforePendingCount = {
            if (countReading.complete(Unit)) countReleased.await()
        }

        // When — a client opens over it, and a collector records every status it is handed
        val client =
            newTestOfflineClient(
                mutationDao = mutationDao,
                config = Offline.Config().apply { autoSync = false },
            )
        val status = client.syncStatus
        val seen = mutableListOf<SyncStatus>()
        val recorder = launch(Dispatchers.Default) { status.collect { value -> seen += value } }
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { countReading.await() } }
        val beforeTheCount = status.value
        countReleased.complete(Unit)
        val counted = status.awaiting { it.isQueueCounted }
        client.close()
        recorder.cancelAndJoin()

        // Then — until the first count the status says so, with counts of zero for want of one;
        // from it on, the counts are the queue's and the status stays counted, at close included
        assertFalse(beforeTheCount.isQueueCounted)
        assertEquals(0, beforeTheCount.pendingMutationCount)
        assertEquals(1, counted.pendingMutationCount)
        assertTrue(status.value.isQueueCounted)
        assertEquals(
            listOf(),
            seen.zipWithNext().filter { (earlier, later) ->
                earlier.isQueueCounted && !later.isQueueCounted
            },
            "a counted status was followed by one not counted: $seen",
        )
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
    fun the_status_keeps_counting_after_the_rejected_list_fails_while_a_count_is_reading() =
        runTest {
            // Given — the status's first count is still reading the queue when its watch of the
            // rejected list fails, as a slow disk and a failing read together would
            var failing = true
            val countReading = CompletableDeferred<Unit>()
            val mutationDao =
                FakeMutationDao().apply {
                    beforePendingCount = { if (countReading.complete(Unit)) awaitCancellation() }
                }
            val cacheDao =
                FakeQueryCacheDao().apply {
                    onGetSnapshot = { table, key ->
                        if (
                            failing &&
                                table == RESERVED_TABLE_NAME &&
                                key == REJECTED_MUTATIONS_CACHE_KEY
                        ) {
                            countReading.await()
                            error("Scripted failure reading the rejected list")
                        }
                    }
                }
            val logs = RecordingLogSink()
            val server = RecordingPostgrestServer(answer = { connectivityFailure() })
            val client =
                newTestOfflineClient(
                    supabase = server.supabaseClient(),
                    queryCacheDao = cacheDao,
                    mutationDao = mutationDao,
                    engineLogger = logs.asEngineLogger(),
                )
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) {
                    while (logs.entries.none { "IllegalStateException" in it.message }) delay(
                        10.milliseconds
                    )
                }
            }
            failing = false

            // When — a write is queued offline
            client.from(TABLE).insert(row(1))
            val counted = runCatching {
                client.syncStatus.awaiting { it.pendingMutationCount == 1 }
            }

            // Then — the status said it lost track, and follows the queue again
            assertTrue(
                counted.isSuccess,
                "the status never counted the queued write (status=${client.syncStatus.value}); " +
                    "${logs.entries}",
            )
            assertTrue(
                logs.entries.any { "lost track of the queue" in it.message },
                "${logs.entries}",
            )
            assertTrue(logs.entries.none { "failed unexpectedly" in it.message }, "${logs.entries}")
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

        /** When a kept record says the queue was emptied. */
        val KEPT_AT: Instant = Instant.fromEpochMilliseconds(1_761_700_000_000)
    }
}
