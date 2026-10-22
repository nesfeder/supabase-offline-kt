// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The backlog hands over every waiting change at once, in order; a change past its capacity
 * discards the whole backlog and says how many went; and once closed it hands over what waits, then
 * nothing.
 */
class ChangeBacklogTest {

    @Test
    fun every_waiting_change_is_taken_at_once_in_order() = runTest {
        // Given
        val backlog = ChangeBacklog(capacity = 10)
        val changes = List(3) { index -> change("t$index") }

        // When
        for (change in changes) assertEquals(0, backlog.add(change))

        // Then — all three, then the next one on its own
        assertEquals(changes, backlog.takeAll())
        assertEquals(0, backlog.add(change("t3")))
        assertEquals(listOf(change("t3")), backlog.takeAll())
    }

    @Test
    fun a_change_past_the_capacity_discards_the_backlog_and_says_how_many() = runTest {
        // Given — a backlog at its capacity
        val backlog = ChangeBacklog(capacity = 3)
        repeat(3) { index -> assertEquals(0, backlog.add(change("t$index"))) }

        // When — one more
        val discarded = backlog.add(change("t3"))

        // Then — all four went, and the backlog starts over
        assertEquals(4, discarded)
        assertEquals(0, backlog.add(change("t4")))
        assertEquals(listOf(change("t4")), backlog.takeAll())
    }

    @Test
    fun once_closed_what_waits_is_taken_and_then_nothing() = runTest {
        // Given
        val backlog = ChangeBacklog(capacity = 10)
        backlog.add(change("t0"))

        // When
        backlog.close()

        // Then — the waiting change, then the end; a late change is not kept
        assertEquals(listOf(change("t0")), backlog.takeAll())
        assertEquals(0, backlog.add(change("t1")))
        assertNull(backlog.takeAll())
    }

    private fun change(id: String): ServerChangeEvent.Change =
        ServerChangeEvent.Saved("tasks", "public", buildJsonObject { put("id", id) })
}
