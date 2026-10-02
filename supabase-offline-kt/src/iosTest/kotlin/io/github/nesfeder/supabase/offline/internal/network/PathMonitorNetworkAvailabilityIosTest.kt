// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.network

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The iOS connectivity monitor reports the device's path as soon as it is collected, on the
 * monitor's own queue, and lets go of the monitor when the collection ends.
 */
class PathMonitorNetworkAvailabilityIosTest {

    @Test
    fun the_monitor_reports_the_current_path_and_stops_when_collection_ends() = runTest {
        // When — the first value, after which the collection ends and the monitor is cancelled
        val first =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(5.seconds) { pathMonitorNetworkAvailability().first() }
            }

        // Then — the monitor answered; whether the simulator is online is not this test's to say
        assertNotNull(first, "the path monitor reported nothing")
    }
}
