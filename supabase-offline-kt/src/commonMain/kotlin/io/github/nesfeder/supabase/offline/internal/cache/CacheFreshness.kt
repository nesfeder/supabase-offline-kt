// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The default tick source of [CacheFreshness]: nanoseconds since the store was built, on the
 * monotonic clock, so a tick never steps back the way a wall clock can.
 */
internal fun monotonicTickSource(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeNanoseconds }
}

/**
 * Whether [mutation] belongs on a payload that holds the synced mutations through
 * [payloadSyncedThrough] (see [CachedQueryRow.syncedThrough]): a pending one always does, a synced
 * one only when it replayed after them. Without a sequence it is taken as held by none.
 */
internal fun belongsOnPayload(mutation: MutationRow, payloadSyncedThrough: Long): Boolean {
    if (mutation.syncedAtMs == null) return true
    val sequence = mutation.syncSequence ?: return true
    return sequence > payloadSyncedThrough
}

/**
 * The watermarks that say whether a response or a cache write is still current: each table's last
 * local write, and when local data was last cleared or the cache dropped. In memory only: they
 * order events inside one process. Owns the clear lock, under which a clear moves them and a write
 * that must not land after a clear checks them; nothing else is taken under it but the records'.
 *
 * @param monotonicTick the one tick source every watermark and request start is read from.
 */
internal class CacheFreshness(private val monotonicTick: () -> Long) {

    /** Table → tick of its most recent local write: a patch, a replay, an emptied table. */
    private val lastLocalMutationTick = AtomicReference<Map<String, Long>>(emptyMap())

    /** Tick of the most recent clear of local data, `0` until one ran. */
    private val cacheClearedTick = AtomicLong(0L)

    /** Tick of the most recent time the cache was emptied: a clear, or a drop for a new owner. */
    private val cacheDroppedTick = AtomicLong(0L)

    /**
     * Makes a clear and every write that must not follow it one step each; see
     * [writeUnlessCleared].
     */
    private val clearMutex = Mutex()

    /** The current tick; read it before a request goes out, and hand it back with the response. */
    fun tickNow(): Long = monotonicTick()

    /**
     * Advances [table]'s watermark to [tick], never backwards: a request already in flight for it
     * started before the write and cannot describe it. Taken at the write's start, so a request
     * that started just before is dropped too, which costs a fetch and never loses the write.
     */
    fun advanceLocalWriteWatermark(table: String, tick: Long = monotonicTick()) {
        lastLocalMutationTick.update { current ->
            val existing = current[table]
            // Never move a watermark backwards: two writes racing here must leave the later tick.
            if (existing != null && existing >= tick) current else current + (table to tick)
        }
    }

    /**
     * Whether a response whose request started at [requestStartedAtTick] predates [table]'s last
     * local write. `null` is a writer with no request behind it, never stale.
     */
    fun isStaleRelativeToLocalWrite(table: String, requestStartedAtTick: Long?): Boolean {
        if (requestStartedAtTick == null) return false
        val watermark = lastLocalMutationTick.load()[table] ?: return false
        return requestStartedAtTick < watermark
    }

    /** Whether the cache was cleared or dropped after [requestStartedAtTick]; `null` never is. */
    fun predatesCacheClear(requestStartedAtTick: Long?): Boolean {
        if (requestStartedAtTick == null) return false
        return requestStartedAtTick < cacheDroppedTick.load()
    }

    /** Whether local data, the mutation queue with it, was cleared after [requestStartedAtTick]. */
    fun predatesQueueClear(requestStartedAtTick: Long?): Boolean {
        if (requestStartedAtTick == null) return false
        return requestStartedAtTick < cacheClearedTick.load()
    }

    /**
     * Whether the cache was cleared, or dropped for another identity, since [tick] was read from
     * [tickNow]: a write whose request was in flight then must not be overlaid onto it.
     */
    fun wasClearedSince(tick: Long): Boolean = predatesCacheClear(tick)

    /** Runs [block] under the clear lock. */
    suspend fun <T> holdingClearLock(block: suspend () -> T): T = clearMutex.withLock { block() }

    /**
     * Moves the drop watermark, and with [queueCleared] the clear watermark, to [tick], never
     * backwards. Caller holds the clear lock.
     */
    fun markEmptied(tick: Long, queueCleared: Boolean) {
        // Never move a watermark backwards: the earlier of two racing clears would re-admit
        // responses the later one disqualified.
        if (queueCleared) cacheClearedTick.update { existing -> maxOf(existing, tick) }
        cacheDroppedTick.update { existing -> maxOf(existing, tick) }
    }

    /**
     * Runs [write] unless the cache was cleared or dropped since [guardTick], as one step with the
     * clear.
     *
     * @return `false` when a clear voided the write.
     */
    suspend fun writeUnlessCleared(guardTick: Long, write: suspend () -> Unit): Boolean =
        clearMutex.withLock {
            if (predatesCacheClear(guardTick)) {
                false
            } else {
                write()
                true
            }
        }
}
