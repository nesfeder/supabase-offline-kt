// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.realtime

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.channels.Channel

/**
 * The changes a follow received and has not applied yet, in the order they arrived: one coroutine
 * adds them as the feed delivers them, another takes every waiting one at once and applies them by
 * table. At most [capacity] wait: past it the whole backlog is discarded, since reading the tables
 * again costs less than applying it, and [add] says how many went.
 */
internal class ChangeBacklog(private val capacity: Int) {

    private val waiting = Channel<ServerChangeEvent.Change>(Channel.UNLIMITED)

    /** How many changes wait, as closely as two coroutines can count. */
    private val count = AtomicInt(0)

    /**
     * Adds [change] behind the waiting ones. Returns how many changes were discarded, the new one
     * among them, when it took the backlog past its capacity; `0` otherwise, and once closed.
     */
    fun add(change: ServerChangeEvent.Change): Int {
        if (waiting.trySend(change).isFailure) return 0
        if (count.incrementAndFetch() <= capacity) return 0
        var discarded = 0
        while (waiting.tryReceive().isSuccess) {
            count.decrementAndFetch()
            discarded++
        }
        return discarded
    }

    /**
     * Every change waiting, in order, once there is at least one; `null` once the backlog is closed
     * and empty.
     */
    suspend fun takeAll(): List<ServerChangeEvent.Change>? {
        val first = waiting.receiveCatching().getOrNull() ?: return null
        count.decrementAndFetch()
        val taken = mutableListOf(first)
        while (true) {
            val next = waiting.tryReceive().getOrNull() ?: break
            count.decrementAndFetch()
            taken += next
        }
        return taken
    }

    /** Ends the backlog: [takeAll] returns what still waits, then `null`. */
    fun close() {
        waiting.close()
    }
}
