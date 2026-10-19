// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.FIRST_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedClient
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedServer
import io.github.nesfeder.supabase.offline.testdoubles.queueWhileOffline
import io.github.nesfeder.supabase.offline.testdoubles.renameTask
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [PendingMutation.baseVersion] names the version a queued write replays against: the row's, as the
 * cache held it, for a guarded write, and none for a write that replays as it was made.
 */
class PendingMutationBaseVersionTest {

    private val server = newGuardedServer()

    @Test
    fun a_guarded_write_reports_the_version_it_replays_against() = runTest {
        // Given
        val client = newGuardedClient(server)
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        client.from(GUARDED_TABLE).select()
        server.failsRequest = { true }

        // When
        val outcome = client.renameTask(1, "mine")
        val listed = client.pendingMutations().first().single()

        // Then
        assertEquals(
            FIRST_VERSION,
            assertIs<MutationOutcome.Queued>(outcome).pendingMutation.baseVersion,
        )
        assertEquals(FIRST_VERSION, listed.baseVersion)
        client.close()
    }

    @Test
    fun a_write_to_a_row_the_cache_does_not_hold_reports_no_version() = runTest {
        // Given — the row was never read
        val client = newGuardedClient(server)
        server.failsRequest = { true }

        // When
        client.renameTask(1, "mine")

        // Then
        assertNull(client.pendingMutations().first().single().baseVersion)
        client.close()
    }

    @Test
    fun a_write_to_a_table_without_a_version_column_reports_no_version() = runTest {
        // Given
        val client = newGuardedClient(server)
        server.seed(OTHER_TABLE, buildJsonObject { put("id", 1) })

        // When
        client.queueWhileOffline(server) {
            from(OTHER_TABLE).update(buildJsonObject { put("title", "mine") }) {
                filter { eq("id", 1) }
            }
        }

        // Then
        assertNull(client.pendingMutations().first().single().baseVersion)
        client.close()
    }

    private companion object {
        const val OTHER_TABLE = "notes"
    }
}
