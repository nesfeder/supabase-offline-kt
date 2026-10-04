// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.network

import io.github.nesfeder.supabase.offline.NetworkStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The sync status publishes [NetworkAccess.status] each time [NetworkAccess.statusChanges] emits,
 * so that it is current as soon as the switch returns. A change and its reversal that land while
 * the reader handles one emission must still reach it, or the status is left at the change.
 */
class NetworkAccessTest {

    @Test
    fun a_signal_that_ends_while_offline_falls_back_to_unknown_as_a_reconnection() = runTest {
        // Given — an app's one-shot signal that says offline once and ends
        val access = NetworkAccess(flowOf(false))
        var reconnections = 0

        // When
        access.watchIn(backgroundScope) { reconnections++ }
        runCurrent()

        // Then — not offline for good: unknown, which lets the library try by itself
        assertEquals(NetworkStatus.UNKNOWN, access.status)
        assertEquals(1, reconnections)
    }

    @Test
    fun a_signal_that_fails_while_offline_falls_back_to_unknown_as_a_reconnection() = runTest {
        // Given — a monitor that says offline, then fails
        val access =
            NetworkAccess(
                flow {
                    emit(false)
                    error("Scripted failure of the monitor")
                }
            )
        var reconnections = 0
        val failures = mutableListOf<Throwable>()
        val scope =
            CoroutineScope(
                backgroundScope.coroutineContext +
                    SupervisorJob() +
                    CoroutineExceptionHandler { _, failure -> failures += failure }
            )

        // When
        access.watchIn(scope) { reconnections++ }
        runCurrent()

        // Then — unknown, and the failure still reaches the scope's handler
        assertEquals(NetworkStatus.UNKNOWN, access.status)
        assertEquals(1, reconnections)
        assertEquals(listOf("Scripted failure of the monitor"), failures.map { it.message })
    }

    @Test
    fun a_reader_of_the_status_on_each_change_ends_on_the_current_one() = runTest {
        // Given
        val access = NetworkAccess()
        val seen = mutableListOf<NetworkStatus>()

        // When — while the reader handles its first emission, the switch goes off and back on
        val reader = launch {
            access.statusChanges.collect {
                if (seen.isEmpty()) {
                    access.disable()
                    seen += access.status
                    access.enable()
                } else {
                    seen += access.status
                }
            }
        }
        runCurrent()
        reader.cancel()

        // Then — the reader read the current status last
        assertEquals(NetworkStatus.UNKNOWN, seen.last(), "the reader saw $seen")
    }
}
