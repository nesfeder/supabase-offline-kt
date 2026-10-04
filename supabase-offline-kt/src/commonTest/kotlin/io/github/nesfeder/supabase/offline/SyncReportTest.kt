// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What `syncPendingMutations` reports: what it sent, what the server refused and what is still
 * queued, counting an insert whose earlier send had in fact arrived. It returns promptly when
 * offline, and when the library is sending the same writes already it waits for that and reports
 * what remains after both.
 */
class SyncReportTest {

    @Test
    fun a_sync_reports_what_it_sent_what_was_refused_and_what_remains() = runTest {
        // Given — three writes queued offline; the server will refuse the second for good. The
        // library's own passes stay idle, so this call is the only sender
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                coordinatorScope = idleCoroutineScope(),
                closeWait = 100.milliseconds,
            )
        (1..3).forEach { id -> client.from(TABLE).insert(row(id)) }
        server.answer = { request ->
            if (""""id":2""" in request.body)
                postgrestError(HttpStatusCode.UnprocessableEntity, code = "23514")
            else okJson("[]")
        }

        // When
        val report = client.syncPendingMutations()

        // Then
        assertEquals(2, report.syncedCount)
        assertEquals(0, report.remainingCount)
        assertEquals(0, report.alreadyOnServerCount)
        val refused = report.rejected.single()
        assertEquals(422 to "23514", refused.serverError?.status to refused.serverError?.code)
        assertEquals(TABLE, refused.table)
        assertEquals(MutationOperation.INSERT, refused.operation)
        assertTrue(refused.isRetryable)
        client.close()
    }

    @Test
    fun a_sync_of_one_table_holds_its_writes_behind_an_earlier_one_in_another_table() = runTest {
        // Given — a parent queued while its table cannot be reached, then a child queued after it.
        // The library's own passes stay idle, so these calls are the only senders
        val server =
            RecordingPostgrestServer(
                answer = { request ->
                    if (request.table == PARENTS) connectivityFailure() else okJson("[]")
                }
            )
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                coordinatorScope = idleCoroutineScope(),
                closeWait = 100.milliseconds,
            )
        assertIs<MutationOutcome.Queued>(client.from(PARENTS).insert(row(1)))
        assertIs<MutationOutcome.Queued>(client.from(CHILDREN).insert(row(2)))

        // When — the app syncs the children's table only
        val held = client.syncPendingMutations(CHILDREN)

        // Then — the child was not sent ahead of its parent, and the report says why it remains
        assertEquals(emptyList(), server.requests.filter { it.table == CHILDREN })
        assertEquals(0, held.syncedCount)
        assertEquals(1, held.remainingCount)
        assertEquals(1, held.heldBehindOtherTablesCount)

        // And — once the parent's table answers, syncing every table sends both, parent first
        server.answer = { okJson("[]") }
        val all = client.syncPendingMutations()
        assertEquals(2, all.syncedCount)
        assertEquals(0, all.heldBehindOtherTablesCount)
        assertEquals(
            listOf(PARENTS, CHILDREN),
            server.requests.filter { it.method == "POST" }.map { it.table }.distinct(),
        )
        assertEquals(CHILDREN, server.requests.last().table)
        client.close()
    }

    @Test
    fun a_sync_while_offline_returns_with_the_writes_still_queued_and_says_why() = runTest {
        // Given
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        client.from(TABLE).insert(row(1))
        client.from(TABLE).insert(row(2))

        // When
        val report = client.syncPendingMutations()

        // Then
        assertEquals(0 to 2, report.syncedCount to report.remainingCount)
        val why = assertNotNull(client.syncStatus.value.lastSyncError)
        assertTrue(why.isNetworkOrConnectivityFailure())
        client.close()
    }

    @Test
    fun the_failure_a_sync_reports_carries_no_filter_value_or_server_text() = runTest {
        // Given — a queued update filtered by a value, which the server fails with a message that
        // repeats it
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        client.from(TABLE).update(buildJsonObject { put("title", "renamed") }) {
            filter { eq("title", SECRET) }
        }
        server.answer = {
            postgrestError(
                HttpStatusCode.InternalServerError,
                code = "XX000",
                message = "failed on $SECRET",
                details = SECRET,
            )
        }

        // When
        client.syncPendingMutations()

        // Then — the status and the code, in the library's own words
        val why = assertNotNull(client.syncStatus.value.lastSyncError)
        assertEquals(500 to "XX000", why.httpStatus to why.code)
        assertEquals(500, why.httpStatusOrNull())
        assertFalse(SECRET in why.message.orEmpty(), why.message)
        assertEquals(null, why.cause)
        assertFalse(why.isNetworkOrConnectivityFailure())
        client.close()
    }

    @Test
    fun a_sync_a_clear_cuts_short_still_reports_the_writes_it_sent_before() = runTest {
        // Given — two writes queued offline; the server accepts the first and holds the second.
        // The library's own passes stay idle, so this call is the only sender
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                coordinatorScope = idleCoroutineScope(),
                closeWait = 100.milliseconds,
            )
        (1..2).forEach { id -> client.from(TABLE).insert(row(id)) }
        server.answer = { request ->
            if (""""id":2""" in request.body) {
                arrived.complete(Unit)
                release.await()
            }
            okJson("[]")
        }

        // When — local data is cleared while the second is on the wire
        val sync = async(Dispatchers.Default) { client.syncPendingMutations() }
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { arrived.await() } }
        client.clearLocalData()
        release.complete(Unit)
        val report = withContext(Dispatchers.Default) { withTimeout(10.seconds) { sync.await() } }

        // Then — the first was sent and accepted before the clear, and nothing is left queued
        assertEquals(1, report.syncedCount)
        assertEquals(0, report.remainingCount)
        client.close()
    }

    @Test
    fun an_insert_whose_earlier_send_had_arrived_is_counted_as_already_on_the_server() = runTest {
        // Given — a keyed insert sent at once, whose answer was a 503 after the server may have
        // committed it: it stays queued, as possibly delivered
        val server =
            RecordingPostgrestServer(
                answer = { postgrestError(HttpStatusCode.ServiceUnavailable, code = "XX503") }
            )
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        val first = client.from(TABLE).insert(row(1))

        // When — it replays, and the server already holds the row: it writes none
        server.answer = { okJson("[]", extraHeaders = mapOf("Content-Range" to "*/0")) }
        val report = client.syncPendingMutations()

        // Then — sent once more as an upsert that ignores duplicates, and counted as such
        assertIs<MutationOutcome.Queued>(first)
        assertEquals(1 to 1, report.syncedCount to report.alreadyOnServerCount)
        assertTrue("ignore-duplicates" in server.requests.last().prefer.orEmpty())
        client.close()
    }

    @Test
    fun a_sync_that_meets_the_library_sending_the_same_writes_waits_and_reports_what_remains() =
        runTest {
            // Given — two writes queued offline; a pass of the library's own is sending the first,
            // held at the server. Everything runs on this test's scheduler
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val held = CompletableDeferred<Unit>()
            var online = false
            val server =
                RecordingPostgrestServer(
                    answer = {
                        if (!online) connectivityFailure()
                        held.await()
                        okJson("[]")
                    },
                    engineDispatcher = dispatcher,
                )
            val client =
                newTestOfflineClient(supabase = server.supabaseClient(), coordinatorScope = scope)
            client.from(TABLE).insert(row(1))
            client.from(TABLE).insert(row(2))
            server.requests.clear()
            online = true
            scope.launch { client.syncPendingMutationsReport() }
            runCurrent()

            // When — the app syncs while that pass has the first write on the wire
            val appSync = scope.async { client.syncPendingMutations() }
            runCurrent()
            val returnedWhileHeld = appSync.isCompleted
            held.complete(Unit)
            runCurrent()

            // Then — it waited, and returned once nothing was left; each write was sent once
            assertFalse(returnedWhileHeld)
            val report = appSync.await()
            assertEquals(0, report.remainingCount)
            assertEquals(2, server.requests.count { it.method == "POST" })
            scope.cancel()
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
        const val PARENTS = "parents"
        const val CHILDREN = "children"
        const val SECRET = "s3cret-filter-value"
    }
}
