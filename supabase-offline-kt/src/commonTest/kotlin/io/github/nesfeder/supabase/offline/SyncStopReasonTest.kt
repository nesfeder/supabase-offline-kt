// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeHeldSessionManager
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
 * Why `syncPendingMutations` returned when it did: one case per [SyncStopReason], each driven
 * through a real call. A failure is recorded as the status's last error too; a stop that is not a
 * failure records none, clears it when the writes it held back are another user's, and otherwise
 * leaves it as it was. The library's own passes stay idle, so each call is the only sender.
 */
class SyncStopReasonTest {

    private val server = RecordingPostgrestServer(answer = { connectivityFailure() })

    private fun newClient(mutationDao: FakeMutationDao = FakeMutationDao()) =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            mutationDao = mutationDao,
            coordinatorScope = idleCoroutineScope(),
            closeWait = 100.milliseconds,
        )

    @Test
    fun a_sync_that_goes_through_every_write_completed_even_with_a_refusal() = runTest {
        // Given — two writes queued offline; the server will refuse the first for good
        val client = newClient()
        (1..2).forEach { id -> client.from(TABLE).insert(row(id)) }
        server.answer = { request ->
            if (""""id":1""" in request.body)
                postgrestError(HttpStatusCode.UnprocessableEntity, code = "23514")
            else okJson("[]")
        }

        // When
        val report = client.syncPendingMutations()

        // Then — a refusal for good is not a stop
        assertEquals(SyncStopReason.Completed, report.stopReason)
        assertEquals(1 to 0, report.syncedCount to report.remainingCount)
        assertEquals(1, report.rejected.size)
        assertNull(client.syncStatus.value.lastSyncError)
        client.close()
    }

    @Test
    fun a_sync_that_cannot_reach_the_network_stops_for_it() = runTest {
        // Given
        val client = newClient()
        client.from(TABLE).insert(row(1))

        // When
        val report = client.syncPendingMutations()

        // Then
        assertEquals(SyncStopReason.NetworkUnavailable, report.stopReason)
        assertEquals(0 to 1, report.syncedCount to report.remainingCount)
        val recorded = assertNotNull(client.syncStatus.value.lastSyncError)
        assertTrue(recorded.isNetworkOrConnectivityFailure())
        client.close()
    }

    @Test
    fun a_sync_the_server_fails_stops_on_a_server_failure_carrying_its_status() = runTest {
        // Given — two writes queued offline; the server fails the first with a 503
        val client = newClient()
        (1..2).forEach { id -> client.from(TABLE).insert(row(id)) }
        server.answer = {
            postgrestError(HttpStatusCode.ServiceUnavailable, code = "PGRST000", message = SECRET)
        }

        // When
        val report = client.syncPendingMutations()

        // Then — its status and code, nothing of its text, as the status's last error says it
        val reason = assertIs<SyncStopReason.ServerFailure>(report.stopReason)
        assertEquals(503 to "PGRST000", reason.error.httpStatus to reason.error.code)
        assertFalse(SECRET in reason.error.message.orEmpty(), reason.error.message)
        assertFalse(reason.error.isNetworkOrConnectivityFailure())
        assertEquals(0 to 2, report.syncedCount to report.remainingCount)
        val recorded = assertNotNull(client.syncStatus.value.lastSyncError)
        assertEquals(
            Triple(503, "PGRST000", reason.error.message),
            Triple(recorded.httpStatus, recorded.code, recorded.message),
        )
        client.close()
    }

    @Test
    fun a_sync_a_failure_on_the_device_stops_on_a_local_failure() = runTest {
        // Given — a keyed insert queued offline, and a disk that fails recording its send, once
        val mutationDao = FakeMutationDao()
        val client = newClient(mutationDao)
        client.from(TABLE).insert(row(1))
        server.answer = { okJson("[]") }
        mutationDao.failNextMarkSendUnconfirmed = true

        // When
        val report = client.syncPendingMutations()

        // Then — neither the network's nor the server's, and the write stays for a later pass
        val reason = assertIs<SyncStopReason.LocalFailure>(report.stopReason)
        assertNull(reason.error.httpStatus)
        assertFalse(reason.error.isNetworkOrConnectivityFailure())
        assertEquals(0 to 1, report.syncedCount to report.remainingCount)
        assertEquals(reason.error.message, client.syncStatus.value.lastSyncError?.message)
        assertEquals(1, client.syncPendingMutations().syncedCount)
        client.close()
    }

    @Test
    fun a_sync_while_the_session_restores_stops_for_the_session() = runTest {
        // Given — a stored session whose load the plugin is still waiting for, and a write queued
        // meanwhile for its user
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restoring =
            RecordingPostgrestServer(answer = { okJson("[]") }, engineDispatcher = dispatcher)
        val supabase =
            newTestSupabaseClient(engine = restoring.engine) {
                coroutineDispatcher = dispatcher
                install(Postgrest) { maxRetries = 0 }
                install(Auth) {
                    sessionManager = FakeHeldSessionManager(storedSession("u-1"))
                    codeVerifierCache = MemoryCodeVerifierCache()
                    autoLoadFromStorage = true
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                }
            }
        val client =
            newTestOfflineClient(
                supabase = supabase,
                coordinatorScope = idleCoroutineScope(),
                closeWait = 100.milliseconds,
            )
        runCurrent()
        assertIs<MutationOutcome.Queued>(client.from(TABLE).insert(row(1)))

        // When — the session does not settle within the call's wait
        val report = client.syncPendingMutations()

        // Then — nothing was sent, and the write is still the user's to send
        assertEquals(SyncStopReason.SessionNotReady, report.stopReason)
        assertEquals(0 to 1, report.syncedCount to report.remainingCount)
        assertEquals(0, restoring.requests.count { request -> request.method == "POST" })
        assertNull(client.syncStatus.value.lastSyncError)
        client.close()
        supabase.close()
    }

    @Test
    fun a_sync_the_user_changes_under_stops_for_it_and_clears_the_last_error() = runTest {
        // Given — user A's two writes queued offline, and a pass that could not reach the network
        val supabase = newTestSupabaseClientWithAuth(server.engine)
        supabase.signInForTest(USER_A)
        val client =
            newTestOfflineClient(
                supabase = supabase,
                coordinatorScope = idleCoroutineScope(),
                closeWait = 100.milliseconds,
            )
        (1..2).forEach { id -> client.from(TABLE).insert(row(id)) }
        assertEquals(SyncStopReason.NetworkUnavailable, client.syncPendingMutations().stopReason)
        assertNotNull(client.syncStatus.value.lastSyncError)

        // When — user B signs in while A's first write is on the wire
        var switched = false
        server.answer = {
            if (!switched) {
                switched = true
                supabase.signInForTest(USER_B)
            }
            okJson("[]")
        }
        val report = client.syncPendingMutations()

        // Then — the first went out as A, the pass stopped before the second, and the failure
        // that held A's writes back no longer stands for the status
        assertEquals(SyncStopReason.SessionChanged, report.stopReason)
        assertEquals(1, report.syncedCount)
        val posts = server.requests.filter { request -> request.method == "POST" }
        assertEquals("Bearer ${testJwt(USER_A)}", posts.last().authorization)
        assertTrue(posts.none { post -> post.authorization == "Bearer ${testJwt(USER_B)}" })
        assertNull(client.syncStatus.value.lastSyncError)
        client.close()
        supabase.close()
    }

    @Test
    fun a_sync_while_network_access_is_off_stops_for_it_and_keeps_the_last_error() = runTest {
        // Given — a write a pass could not send for want of the network, then access turned off
        val client = newClient()
        client.from(TABLE).insert(row(1))
        client.syncPendingMutations()
        val earlier = assertNotNull(client.syncStatus.value.lastSyncError)
        client.disableNetwork()
        server.requests.clear()

        // When
        val report = client.syncPendingMutations()

        // Then — nothing was sent, and no newer answer came for the write
        assertEquals(SyncStopReason.NetworkDisabled, report.stopReason)
        assertEquals(0 to 1, report.syncedCount to report.remainingCount)
        assertEquals(emptyList(), server.requests)
        assertSame(earlier, client.syncStatus.value.lastSyncError)
        client.close()
    }

    @Test
    fun a_sync_of_one_table_held_behind_another_stops_for_it_and_keeps_the_last_error() = runTest {
        // Given — a parent queued while its table cannot be reached, a child queued after it, and
        // a pass over every table that stopped at the parent
        server.answer = { request ->
            if (request.table == PARENTS) connectivityFailure() else okJson("[]")
        }
        val client = newClient()
        assertIs<MutationOutcome.Queued>(client.from(PARENTS).insert(row(1)))
        assertIs<MutationOutcome.Queued>(client.from(CHILDREN).insert(row(2)))
        assertEquals(SyncStopReason.NetworkUnavailable, client.syncPendingMutations().stopReason)
        val earlier = assertNotNull(client.syncStatus.value.lastSyncError)

        // When — the app syncs the children's table only
        val report = client.syncPendingMutations(CHILDREN)

        // Then — the child waits for its parent, whose failure still stands
        assertEquals(SyncStopReason.HeldBehindOtherTables, report.stopReason)
        assertEquals(1 to 1, report.remainingCount to report.heldBehindOtherTablesCount)
        assertEquals(emptyList(), server.requests.filter { it.table == CHILDREN })
        assertSame(earlier, client.syncStatus.value.lastSyncError)
        client.close()
    }

    @Test
    fun a_sync_a_clear_cuts_short_stops_for_the_clear() = runTest {
        // Given — two writes queued offline; the server accepts the first and holds the second
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = newClient()
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

        // Then
        assertEquals(SyncStopReason.LocalDataCleared, report.stopReason)
        assertEquals(1 to 0, report.syncedCount to report.remainingCount)
        assertNull(client.syncStatus.value.lastSyncError)
        client.close()
    }

    private fun storedSession(subject: String): UserSession =
        UserSession(
            accessToken = testJwt(subject),
            refreshToken = "refresh-token",
            expiresIn = 3600,
            tokenType = "bearer",
        )

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
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val SECRET = "s3cret-row-value"
    }
}
