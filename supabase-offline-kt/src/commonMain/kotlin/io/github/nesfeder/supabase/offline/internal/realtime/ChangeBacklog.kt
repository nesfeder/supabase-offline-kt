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
 * again costs less than applying it.
 *
 * A discard starts a new [generation]: an entry queued under an older one must not be applied, not
 * even one the applier had already taken, since the re-read that follows the discard covers it, and
 * applying it afterward would put an older value over what the re-read brought. The applier reports
 * a discard once it is free, through [takeDiscarded], woken for it when nothing else would;
 * discards that happen meanwhile are folded into that count.
 */
internal class ChangeBacklog(private val capacity: Int) {

    /** A queued change, with the [generation] it was queued under. */
    class Entry(val change: ServerChangeEvent.Change, val generation: Int)

    /** The entries waiting; `null` marks a discard, and only wakes the applier. */
    private val waiting = Channel<Entry?>(Channel.UNLIMITED)

    /** How many changes wait, as closely as two coroutines can count. */
    private val count = AtomicInt(0)

    /** Moved on by each discard. */
    private val generation = AtomicInt(0)

    /** The changes discarded and not yet reported. */
    private val discarded = AtomicInt(0)

    /** The generation now: an entry queued under an older one was discarded. */
    val currentGeneration: Int
        get() = generation.load()

    /**
     * Adds [change] behind the waiting ones; past the capacity, discards the whole backlog, the new
     * one included, under a new generation, and counts the discard. Nothing once closed.
     */
    fun add(change: ServerChangeEvent.Change) {
        if (waiting.trySend(Entry(change, generation.load())).isFailure) return
        if (count.incrementAndFetch() <= capacity) return
        generation.incrementAndFetch()
        var drained = 0
        while (true) {
            val next = waiting.tryReceive()
            if (!next.isSuccess) break
            if (next.getOrNull() != null) {
                count.decrementAndFetch()
                drained++
            }
        }
        discarded.addAndFetch(drained)
        // Wakes an applier that waits for a change, so the discard is reported now, not when the
        // next change happens to arrive
        waiting.trySend(null)
    }

    /**
     * Every entry waiting, in order, once something waits: empty when only a discard's mark did;
     * `null` once the backlog is closed and empty. An entry of an older generation than
     * [currentGeneration] is among them only when a discard raced the take: the applier drops it.
     */
    suspend fun takeAll(): List<Entry>? {
        var result = waiting.receiveCatching()
        if (result.isClosed) return null
        val taken = mutableListOf<Entry>()
        while (result.isSuccess) {
            val entry = result.getOrNull()
            if (entry != null) {
                count.decrementAndFetch()
                taken += entry
            }
            result = waiting.tryReceive()
        }
        return taken
    }

    /** Counts [number] changes the applier dropped as discarded, for the report. */
    fun countDropped(number: Int) {
        discarded.addAndFetch(number)
    }

    /** How many changes were discarded since last asked; asking resets the count. */
    fun takeDiscarded(): Int = discarded.exchange(0)

    /** Ends the backlog: [takeAll] returns what still waits, then `null`. */
    fun close() {
        waiting.close()
    }
}
