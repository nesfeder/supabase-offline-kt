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
 * The backlog hands over every waiting change at once, in order, each under the generation it was
 * queued in; a change past its capacity discards the whole backlog, moves the generation on, wakes
 * the applier and counts the discard until asked; and once closed it hands over what waits, then
 * nothing.
 */
class ChangeBacklogTest {

    @Test
    fun every_waiting_change_is_taken_at_once_in_order() = runTest {
        // Given
        val backlog = ChangeBacklog(capacity = 10)
        val changes = List(3) { index -> change("t$index") }

        // When
        for (change in changes) backlog.add(change)

        // Then — all three under the first generation, then the next one on its own
        val taken = backlog.takeAll().orEmpty()
        assertEquals(changes, taken.map { entry -> entry.change })
        assertEquals(List(3) { 0 }, taken.map { entry -> entry.generation })
        backlog.add(change("t3"))
        assertEquals(listOf(change("t3")), backlog.takeAll()?.map { entry -> entry.change })
        assertEquals(0, backlog.takeDiscarded())
    }

    @Test
    fun a_change_past_the_capacity_discards_the_backlog_under_a_new_generation() = runTest {
        // Given — a backlog at its capacity
        val backlog = ChangeBacklog(capacity = 3)
        repeat(3) { index -> backlog.add(change("t$index")) }
        assertEquals(0, backlog.currentGeneration)

        // When — one more
        backlog.add(change("t3"))

        // Then — all four went, counted once; the applier is woken with nothing to apply; the
        // next change starts the new generation
        assertEquals(1, backlog.currentGeneration)
        assertEquals(emptyList(), backlog.takeAll())
        assertEquals(4, backlog.takeDiscarded())
        assertEquals(0, backlog.takeDiscarded())
        backlog.add(change("t4"))
        val taken = backlog.takeAll().orEmpty()
        assertEquals(listOf(change("t4")), taken.map { entry -> entry.change })
        assertEquals(listOf(1), taken.map { entry -> entry.generation })
    }

    @Test
    fun changes_the_applier_drops_count_as_discarded_too() = runTest {
        // Given — a discard counted
        val backlog = ChangeBacklog(capacity = 1)
        backlog.add(change("t0"))
        backlog.add(change("t1"))

        // When — the applier drops two it had taken before the discard
        backlog.countDropped(2)

        // Then — one count for the episode
        assertEquals(4, backlog.takeDiscarded())
    }

    @Test
    fun once_closed_what_waits_is_taken_and_then_nothing() = runTest {
        // Given
        val backlog = ChangeBacklog(capacity = 10)
        backlog.add(change("t0"))

        // When
        backlog.close()

        // Then — the waiting change, then the end; a late change is not kept
        assertEquals(listOf(change("t0")), backlog.takeAll()?.map { entry -> entry.change })
        backlog.add(change("t1"))
        assertNull(backlog.takeAll())
    }

    private fun change(id: String): ServerChangeEvent.Change =
        ServerChangeEvent.Saved("tasks", "public", buildJsonObject { put("id", id) })
}
