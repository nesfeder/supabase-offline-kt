// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `LogPrivacyTest`'s rule for a supabase-kt 3.8 `dryRun()`: refused offline, it leaves no row value
 * in a log at INFO or above.
 */
class DryRunLogPrivacyTest {

    @Test
    fun an_offline_dry_run_never_logs_its_body() = runTest {
        // Given / When — a dry run is never queued; offline it fails
        val visible = visibleLogsOfARefusal { builder ->
            builder.insert(buildJsonArray { add(buildJsonObject { put("email", EMAIL) }) }) {
                dryRun()
            }
        }

        // Then
        assertTrue(visible.none { EMAIL in it }, "a body value reached a visible log: $visible")
    }

    private companion object {
        const val EMAIL = "jane.doe@example.com"
    }
}
