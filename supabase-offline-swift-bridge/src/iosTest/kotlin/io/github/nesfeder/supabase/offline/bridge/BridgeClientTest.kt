// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import io.github.nesfeder.supabase.offline.bridge.internal.toNSData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/** The client's operations, end to end through the in-memory engine and a fake server. */
class BridgeClientTest {

    private val server = RecordingServer()

    @Test
    fun a_read_returns_the_rows_and_says_where_they_came_from() = bridgeTest {
        // Given — a client that has read the rows once
        val client = openTestClient(server)
        val (online, _) = client.selectAndWait(readOf())

        // When — the same read after the network drops
        server.online = false
        val (offline, error) = client.selectAndWait(readOf())

        // Then
        assertNull(error)
        assertEquals(SERVER_ROWS, online?.data)
        assertEquals(false, online?.isFromCache)
        assertNotNull(online?.fetchedAtEpochMillis)
        assertEquals(SERVER_ROWS, offline?.data)
        assertEquals(true, offline?.isFromCache)
        client.closeAndWait()
    }

    @Test
    fun a_live_read_follows_a_write_and_ends_without_error_when_the_client_closes() = bridgeTest {
        // Given — a live read that has the server's rows
        val client = openTestClient(server)
        val snapshots = SubscriptionProbe<BridgeQuerySnapshot>()
        client.observe(
            readOf(strategy = "cacheFirstThenNetwork"),
            snapshots.onValue,
            snapshots.onCompletion,
        )
        snapshots.awaitValue { it.data == SERVER_ROWS && it.refresh.kind == BridgeRefreshKind.IDLE }

        // When — a write made offline, then the client closes
        awaitErrorOnly { client.disableNetwork(it) }
        val (outcome, _) = client.mutateAndWait(INSERT_TODO, """{"id":2,"title":"Tea"}""")
        val withWrite = snapshots.awaitValue { it.hasPendingMutations }
        client.closeAndWait()

        // Then — the snapshot shows the queued row, and the read ends with no error
        assertEquals(true, outcome?.isQueued)
        assertEquals(2, Json.parseToJsonElement(withWrite.data.orEmpty()).jsonArray.size)
        assertNull(snapshots.ending.await().second)
        assertEquals(1, snapshots.ending.callCount)
    }

    @Test
    fun a_write_made_offline_is_queued_listed_counted_and_then_synced() = bridgeTest {
        // Given — a client with automatic replay off and its network switched off
        val client = openTestClient(server) { autoSync = false }
        assertNull(awaitErrorOnly { client.disableNetwork(it) })

        // When
        val (outcome, writeError) = client.mutateAndWait(INSERT_TODO, """{"id":2,"title":"Tea"}""")
        val listed = CompletionProbe<List<BridgePendingMutation>>()
        client.pendingMutations(null, listed.completion)
        val counted = CompletionProbe<Int>()
        client.pendingMutationCount("todos", counted.completion)
        assertNull(awaitErrorOnly { client.enableNetwork(it) })
        val synced = CompletionProbe<BridgeSyncReport>()
        client.syncPendingMutations(null, synced.completion)

        // Then
        assertNull(writeError)
        val queued = assertNotNull(outcome?.queued)
        assertEquals(BridgeMutationOperation.INSERT, queued.operation)
        assertEquals("todos", queued.table)
        assertEquals("""[{"id":2,"title":"Tea"}]""", queued.bodyJson)
        assertEquals("""[{"id":2}]""", queued.rowKeysJson)
        assertEquals(listOf(queued.id), listed.awaitValue()?.map { it.id })
        assertEquals(1, counted.awaitValue())
        val report = assertNotNull(synced.awaitValue())
        assertEquals(1, report.syncedCount)
        assertEquals(0, report.remainingCount)
        assertEquals(1, server.requests.count { it.method == "POST" })
        client.closeAndWait()
    }

    @Test
    fun a_one_table_sync_reports_a_write_held_behind_one_in_another_table() = bridgeTest {
        // Given — a parent queued while its table cannot be reached, then a child queued after
        // it; the library's own passes stop at the parent, so nothing can send the child first
        server.unreachable = { it.path.endsWith("/projects") }
        val client = openTestClient(server) { autoSync = false }
        val (parent, _) = client.mutateAndWait(INSERT_PROJECT, """{"id":1}""")
        val (child, _) = client.mutateAndWait(INSERT_TODO, """{"id":2,"title":"Tea"}""")

        // When — the app syncs the child's table only
        val held = CompletionProbe<BridgeSyncReport>()
        client.syncPendingMutations("todos", held.completion)
        val report = assertNotNull(held.awaitValue())

        // Then — the child was held, not sent ahead of its parent, and the report says so
        assertEquals(true, parent?.isQueued)
        assertEquals(true, child?.isQueued)
        assertEquals(0, report.syncedCount)
        assertEquals(1, report.remainingCount)
        assertEquals(1, report.heldBehindOtherTablesCount)
        assertEquals(emptyList(), server.requests.filter { it.path.endsWith("/todos") })

        // And — once the parent's table answers, a sync of every table holds nothing back
        server.unreachable = { false }
        val all = CompletionProbe<BridgeSyncReport>()
        client.syncPendingMutations(null, all.completion)
        val allReport = assertNotNull(all.awaitValue())
        assertEquals(0, allReport.heldBehindOtherTablesCount)
        assertEquals(0, allReport.remainingCount)
        val posts =
            server.requests.filter { it.method == "POST" }.map { it.path.substringAfterLast('/') }
        assertEquals(listOf("projects", "todos"), posts.distinct())
        assertEquals("todos", posts.last())
        client.closeAndWait()
    }

    @Test
    fun a_discarded_write_leaves_the_queue_unsent() = bridgeTest {
        // Given — one queued write
        val client = openTestClient(server) { autoSync = false }
        awaitErrorOnly { client.disableNetwork(it) }
        val (outcome, _) = client.mutateAndWait(INSERT_TODO, """{"id":3}""")
        val id = assertNotNull(outcome?.queued).id

        // When
        val discarded = awaitErrorOnly { client.discardPendingMutation(id, it) }
        val counted = CompletionProbe<Int>()
        client.pendingMutationCount(null, counted.completion)

        // Then
        assertNull(discarded)
        assertEquals(0, counted.awaitValue())
        client.closeAndWait()
    }

    @Test
    fun a_write_refused_on_replay_is_listed_retried_and_dismissed() = bridgeTest {
        // Given — a server that refuses every write, and one queued write replayed against it
        server.answer = { request ->
            if (request.method == "POST") {
                ScriptedAnswer.json(
                    409,
                    """{"code":"23505","message":"duplicate key","details":"Key (id)=(1)","hint":null}""",
                )
            } else {
                RecordingServer.defaultAnswer(request)
            }
        }
        val client = openTestClient(server) { autoSync = false }
        awaitErrorOnly { client.disableNetwork(it) }
        client.mutateAndWait(INSERT_TODO, """{"id":1}""")
        awaitErrorOnly { client.enableNetwork(it) }
        val synced = CompletionProbe<BridgeSyncReport>()
        client.syncPendingMutations(null, synced.completion)
        val report = assertNotNull(synced.awaitValue())

        // When — the refusal is listed and retried; the retry's own pass refuses it again, and
        // that entry is dismissed
        val listed = CompletionProbe<List<BridgeRejectedMutation>>()
        client.rejectedMutations(listed.completion)
        val rejected = assertNotNull(listed.awaitValue()).single()
        val rejections = SubscriptionProbe<List<BridgeRejectedMutation>>()
        client.observeRejectedMutations(rejections.onValue, rejections.onCompletion)
        rejections.awaitValue { it.isNotEmpty() }
        val retried = awaitErrorOnly { client.retryRejectedMutation(rejected.id, it) }
        val again = rejections.awaitValue { list ->
            list.isNotEmpty() && list.single().id != rejected.id
        }
        val dismissed = awaitErrorOnly { client.dismissRejectedMutation(again.single().id, it) }
        rejections.awaitValue { it.isEmpty() }
        val relisted = CompletionProbe<List<BridgeRejectedMutation>>()
        client.rejectedMutations(relisted.completion)

        // Then
        assertEquals(1, report.rejected.size)
        val serverError = assertNotNull(rejected.serverError)
        assertEquals(409, serverError.status)
        assertEquals("23505", serverError.code)
        assertTrue(rejected.isRetryable)
        assertNull(retried)
        assertEquals(2, server.requests.count { it.method == "POST" })
        assertNull(dismissed)
        assertEquals(emptyList(), relisted.awaitValue())
        client.closeAndWait()
    }

    @Test
    fun the_sync_status_follows_the_network_switch_and_ends_when_the_client_closes() = bridgeTest {
        // Given — a subscription to the status
        val client = openTestClient(server)
        val statuses = SubscriptionProbe<BridgeSyncStatus>()
        client.observeSyncStatus(statuses.onValue, statuses.onCompletion)
        val first = statuses.awaitValue()

        // When
        awaitErrorOnly { client.disableNetwork(it) }
        val disabled = statuses.awaitValue { it.network == BridgeNetworkStatus.DISABLED }
        val now = client.currentSyncStatus()
        client.closeAndWait()

        // Then
        assertEquals(BridgeNetworkStatus.UNKNOWN, first.network)
        assertEquals(0, disabled.pendingMutationCount)
        assertEquals(BridgeNetworkStatus.DISABLED, now.network)
        assertNull(now.lastSyncError)
        assertNull(statuses.ending.await().second)
    }

    @Test
    fun the_app_reports_the_network_when_the_options_say_so() = bridgeTest {
        // Given — a client whose app reports connectivity
        val client = openTestClient(server) { reportsNetworkAvailability = true }
        val statuses = SubscriptionProbe<BridgeSyncStatus>()
        client.observeSyncStatus(statuses.onValue, statuses.onCompletion)

        // When
        client.setNetworkAvailable(false)
        val offline = statuses.awaitValue { it.network == BridgeNetworkStatus.OFFLINE }
        client.setNetworkAvailable(true)
        val online = statuses.awaitValue { it.network == BridgeNetworkStatus.ONLINE }

        // Then
        assertEquals(BridgeNetworkStatus.OFFLINE, offline.network)
        assertEquals(BridgeNetworkStatus.ONLINE, online.network)
        client.closeAndWait()
    }

    @Test
    fun clearing_local_data_drops_the_cache_and_the_queue() = bridgeTest {
        // Given — cached rows and a queued write
        val client = openTestClient(server) { autoSync = false }
        client.selectAndWait(readOf())
        awaitErrorOnly { client.disableNetwork(it) }
        client.mutateAndWait(INSERT_TODO, """{"id":4}""")

        // When
        val cleared = awaitErrorOnly { client.clearLocalData(it) }
        val (read, readError) = client.selectAndWait(readOf(strategy = "cacheOnly"))
        val counted = CompletionProbe<Int>()
        client.pendingMutationCount(null, counted.completion)

        // Then
        assertNull(cleared)
        assertNull(read)
        assertEquals(BridgeErrorKind.CACHE_MISS, readError?.kind)
        assertEquals(0, counted.awaitValue())
        client.closeAndWait()
    }

    @Test
    fun a_server_record_and_a_server_delete_reach_a_cached_read() = bridgeTest {
        // Given — cached rows
        val client = openTestClient(server)
        client.selectAndWait(readOf())

        // When — a record arrives, then the first row is deleted
        val applied = awaitErrorOnly {
            client.applyServerRecord("todos", """{"id":5,"title":"Jam"}""", null, null, it)
        }
        val (withRecord, _) = client.selectAndWait(readOf(strategy = "cacheOnly"))
        val deleted = awaitErrorOnly {
            client.applyServerDelete("todos", """{"id":1}""", listOf("id"), null, it)
        }
        val (withoutFirst, _) = client.selectAndWait(readOf(strategy = "cacheOnly"))

        // Then
        assertNull(applied)
        assertNull(deleted)
        assertTrue(withRecord?.data.orEmpty().contains("Jam"))
        assertFalse(withoutFirst?.data.orEmpty().contains("Buy milk"))
        client.closeAndWait()
    }

    @Test
    fun a_function_call_answers_with_the_server_body() = bridgeTest {
        // Given
        server.answer = { ScriptedAnswer.json(200, "42") }
        val client = openTestClient(server)

        // When
        val probe = CompletionProbe<BridgeQueryResult>()
        client.rpc("""{"v":1,"function":"answer"}""", """{"question":"all"}""", probe.completion)

        // Then
        assertEquals("42", probe.awaitValue()?.data)
        assertEquals("/rest/v1/rpc/answer", server.requests.single().path)
        client.closeAndWait()
    }

    @Test
    fun a_cancelled_call_answers_once_with_cancelled_and_the_late_answer_is_dropped() = bridgeTest {
        // Given — a read the server holds
        server.answer = { null }
        val client = openTestClient(server)
        val probe = CompletionProbe<BridgeQueryResult>()
        val handle = client.select(readOf(strategy = "networkOnly"), probe.completion)
        while (server.heldResponders.isEmpty()) delay(10)

        // When — the caller cancels, then the server answers anyway
        handle.cancel()
        val error = probe.awaitError()
        server.heldResponders.single().respond(200, emptyMap(), "[]".encodeToByteArray().toNSData())
        handle.cancel()
        delay(100)

        // Then
        assertEquals(BridgeErrorKind.CANCELLED, error.kind)
        assertEquals(1, probe.callCount)
        client.closeAndWait()
    }

    @Test
    fun a_cancelled_subscription_ends_once_with_cancelled_and_delivers_nothing_more() = bridgeTest {
        // Given — a subscription to the queue count, which has delivered its first value
        val client = openTestClient(server) { autoSync = false }
        val counts = SubscriptionProbe<Int>()
        val handle = client.observePendingMutationCount(null, counts.onValue, counts.onCompletion)
        counts.awaitValue { it == 0 }

        // When — it is cancelled, then the queue changes
        handle.cancel()
        val ending = counts.ending.await()
        awaitErrorOnly { client.disableNetwork(it) }
        client.mutateAndWait(INSERT_TODO, """{"id":6}""")
        handle.cancel()
        delay(100)

        // Then
        assertEquals(BridgeErrorKind.CANCELLED, ending.second?.kind)
        assertEquals(1, counts.ending.callCount)
        assertNull(withTimeoutOrNull(200) { counts.awaitValue { it == 1 } })
        client.closeAndWait()
    }

    @Test
    fun a_closed_client_answers_every_call_with_the_closed_error() = bridgeTest {
        // Given
        val client = openTestClient(server)
        client.closeAndWait()

        // When
        val (read, error) = client.selectAndWait(readOf())
        val statuses = SubscriptionProbe<BridgeSyncStatus>()
        client.observeSyncStatus(statuses.onValue, statuses.onCompletion)
        val closedAgain = awaitErrorOnly { client.close(it) }

        // Then
        assertNull(read)
        assertEquals(BridgeErrorKind.CLIENT_CLOSED, error?.kind)
        assertEquals(BridgeErrorKind.CLIENT_CLOSED, statuses.ending.await().second?.kind)
        assertNull(closedAgain)
    }

    @Test
    fun the_apps_token_reaches_every_request() = bridgeTest {
        // Given
        val client =
            openTestClient(server) { accessTokenProvider = FakeAccessTokenProvider("user-token") }

        // When
        val (read, error) = client.selectAndWait(readOf())

        // Then
        assertNull(error)
        assertNotNull(read)
        assertEquals("Bearer user-token", server.requests.single().header("Authorization"))
        client.closeAndWait()
    }

    @Test
    fun a_token_the_app_cannot_supply_fails_the_call() = bridgeTest {
        // Given
        val client =
            openTestClient(server) {
                accessTokenProvider = FakeAccessTokenProvider(token = null, failure = "signed out")
            }

        // When
        val (_, error) = client.selectAndWait(readOf(strategy = "networkOnly"))

        // Then
        assertEquals(BridgeErrorKind.ACCESS_TOKEN, error?.kind)
        assertEquals("signed out", error?.message)
        assertTrue(server.requests.isEmpty())
        client.closeAndWait()
    }

    @Test
    fun the_log_sink_receives_the_librarys_messages_at_its_level() = bridgeTest {
        // Given — one client logging at DEBUG and one at NONE
        val verbose = RecordingLogSink()
        val silent = RecordingLogSink()
        val talking =
            openTestClient(server) {
                logSink = verbose
                logLevel = BridgeLogLevel.DEBUG
            }
        val quiet =
            openTestClient(RecordingServer()) {
                logSink = silent
                logLevel = BridgeLogLevel.NONE
            }

        // When
        talking.selectAndWait(readOf())
        quiet.selectAndWait(readOf())
        talking.closeAndWait()
        quiet.closeAndWait()

        // Then
        assertTrue(verbose.messages.isNotEmpty())
        assertTrue(verbose.messages.any { it.startsWith("DEBUG") })
        assertEquals(emptyList(), silent.messages)
    }
}
