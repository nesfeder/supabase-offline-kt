// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(OfflineInternal::class)

package io.github.nesfeder.supabase.offline.bridge

import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.SyncStopReason
import io.github.nesfeder.supabase.offline.bridge.internal.toBridge
import io.github.nesfeder.supabase.offline.internal.testing.ValueConstructors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Why a sync call returned, as it crosses to Swift: each of the library's reasons as its own kind,
 * and a failure with its error, as the sync status's last error carries one.
 */
class BridgeSyncStopReasonTest {

    private val server = RecordingServer()

    @Test
    fun each_reason_crosses_as_its_kind_and_only_a_failure_carries_an_error() {
        // Given — a report for each reason the library gives, in the order the kinds are declared
        val refused = ValueConstructors.syncFailedException(SERVER_MESSAGE, 503, "PGRST000", false)
        val onDevice = ValueConstructors.syncFailedException(LOCAL_MESSAGE, null, null, false)
        val reasons =
            listOf(
                SyncStopReason.Completed,
                SyncStopReason.NetworkUnavailable,
                ValueConstructors.serverFailureStop(refused),
                ValueConstructors.localFailureStop(onDevice),
                SyncStopReason.SessionNotReady,
                SyncStopReason.SessionChanged,
                SyncStopReason.NetworkDisabled,
                SyncStopReason.HeldBehindOtherTables,
                SyncStopReason.LocalDataCleared,
            )

        // When
        val crossed = reasons.map { reason ->
            ValueConstructors.syncReport(0, emptyList(), 1, 0, 0, 0, 0, emptyList(), reason)
                .toBridge()
                .stopReason
        }

        // Then — every kind but the fallback, once each, in order
        assertEquals(
            BridgeSyncStopKind.entries.filter { it != BridgeSyncStopKind.UNKNOWN },
            crossed.map { it.kind },
        )
        assertEquals(listOf(2, 3), crossed.indices.filter { crossed[it].error != null })
        val serverError = assertNotNull(crossed[2].error)
        assertEquals(BridgeErrorKind.SYNC_FAILED, serverError.kind)
        assertEquals(SERVER_MESSAGE, serverError.message)
        assertEquals(503, serverError.httpStatus)
        assertEquals("PGRST000", serverError.code)
        assertFalse(serverError.isConnectivityFailure)
        val localError = assertNotNull(crossed[3].error)
        assertEquals(BridgeErrorKind.SYNC_FAILED, localError.kind)
        assertEquals(LOCAL_MESSAGE, localError.message)
        assertNull(localError.httpStatus)
        assertNull(localError.code)
    }

    @Test
    fun a_sync_says_why_it_returned() = bridgeTest {
        // Given — a client with automatic replay off, and a write queued while the server cannot
        // be reached
        val client = openTestClient(server) { autoSync = false }
        server.online = false
        val (outcome, _) = client.mutateAndWait(INSERT_TODO, """{"id":2,"title":"Tea"}""")

        // When — synced still offline, then against a server that fails writes, then with network
        // access off, then with the server answering
        val offline = client.syncAndWait()
        server.online = true
        server.answer = { request ->
            if (request.method == "POST") ScriptedAnswer.json(503, BUSY)
            else RecordingServer.defaultAnswer(request)
        }
        val failing = client.syncAndWait()
        assertNull(awaitErrorOnly { client.disableNetwork(it) })
        val disabled = client.syncAndWait()
        assertNull(awaitErrorOnly { client.enableNetwork(it) })
        server.answer = { request -> RecordingServer.defaultAnswer(request) }
        val completed = client.syncAndWait()

        // Then
        assertEquals(true, outcome?.isQueued)
        assertEquals(BridgeSyncStopKind.NETWORK_UNAVAILABLE, offline.stopReason.kind)
        assertNull(offline.stopReason.error)
        assertEquals(BridgeSyncStopKind.SERVER_FAILURE, failing.stopReason.kind)
        val failure = assertNotNull(failing.stopReason.error)
        assertEquals(BridgeErrorKind.SYNC_FAILED, failure.kind)
        assertEquals(503, failure.httpStatus)
        assertEquals(BridgeSyncStopKind.NETWORK_DISABLED, disabled.stopReason.kind)
        assertEquals(1, disabled.remainingCount)
        assertEquals(BridgeSyncStopKind.COMPLETED, completed.stopReason.kind)
        assertNull(completed.stopReason.error)
        assertEquals(1 to 0, completed.syncedCount to completed.remainingCount)
        client.closeAndWait()
    }

    /** Syncs every table and waits for the report, failing the test on an error. */
    private suspend fun BridgeClient.syncAndWait(): BridgeSyncReport {
        val synced = CompletionProbe<BridgeSyncReport>()
        syncPendingMutations(null, synced.completion)
        return assertNotNull(synced.awaitValue())
    }

    private companion object {
        const val SERVER_MESSAGE = "The server answered 503 (PGRST000); stopped"
        const val LOCAL_MESSAGE = "A failure on this device stopped the sync pass; stopped"
        const val BUSY = """{"code":"PGRST000","message":"busy","details":null,"hint":null}"""
    }
}
