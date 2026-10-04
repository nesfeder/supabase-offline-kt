// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.network

import io.github.nesfeder.supabase.offline.NetworkStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
