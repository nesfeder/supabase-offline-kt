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
 * The default tick source of [CacheFreshness]: nanoseconds since it was made, on the monotonic
 * clock, so a tick never steps back the way a wall clock can.
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
 * A server change patched into a table, kept so an answer whose request left before it can have it
 * laid over it; see [CacheFreshness.logServerPatch].
 *
 * @property tick the tick the patch started at, under the table's write lock.
 * @property writes the patch's writes, in order: whole-row upserts and deletes by key, which give
 *   the same rows however often they are laid over an answer.
 */
internal class LoggedServerPatch(val tick: Long, val writes: List<MutationRow>) {
    /** The length of [writes]' JSON, bodies and filters: what keeping them costs. */
    val length: Long = writes.sumOf { write ->
        write.bodyJson.length.toLong() + write.filterJson.orEmpty().length
    }
}

/**
 * The watermarks that say whether a response or a cache write is still current: each table's last
 * local write, and when local data was last cleared or the cache dropped. Beside them, each table's
 * recent server changes, which an answer that left before them is written with instead of being
 * refused. In memory only: they order events inside one process. Owns the clear lock, under which a
 * clear moves them and a write that must not land after a clear checks them; nothing else is taken
 * under it but the records'.
 *
 * @param monotonicTick the one tick source every watermark and request start is read from.
 */
internal class CacheFreshness(private val monotonicTick: () -> Long) {

    /**
     * Table → tick of its most recent local write: a patch of the device's own write, a replay, a
     * stale mark, an emptied table, or a server change dropped from [serverPatches].
     */
    private val lastLocalMutationTick = AtomicReference<Map<String, Long>>(emptyMap())

    /**
     * Table → the server changes patched into it since its watermark, oldest first, at most
     * [MAX_LOGGED_SERVER_WRITES] writes of [MAX_LOGGED_SERVER_LENGTH] characters in all; see
     * [logServerPatch]. Changed under the table's write lock and the clear lock, read under the
     * table's write lock.
     */
    private val serverPatches = AtomicReference<Map<String, List<LoggedServerPatch>>>(emptyMap())

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
     * Advances [table]'s watermark to [tick], never backward: a request already in flight for it
     * started before the write and cannot describe it. Taken at the write's start, so a request
     * that started just before is dropped too, which costs a fetch and never loses the write.
     */
    fun advanceLocalWriteWatermark(table: String, tick: Long = monotonicTick()) {
        lastLocalMutationTick.update { current ->
            val existing = current[table]
            // Never move a watermark backward: two writes racing here must leave the later tick.
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

    /**
     * Logs [writes], server changes patched into [table] from [tick] on, in place of moving its
     * watermark: a response whose request started before [tick] is written with them laid over it
     * ([serverWritesSince]) rather than refused. The log keeps the table's newest patches, at most
     * [MAX_LOGGED_SERVER_WRITES] writes of [MAX_LOGGED_SERVER_LENGTH] characters in all, however
     * long the table then stays quiet; a patch that leaves it moves the watermark to its tick, so a
     * response that left before that patch is refused, as a local write's would be. Caller holds
     * the table's write lock and the clear lock.
     */
    fun logServerPatch(table: String, tick: Long, writes: List<MutationRow>) {
        if (writes.isEmpty()) return
        val watermark = lastLocalMutationTick.load()[table]
        // A patch at or before the watermark is never laid over anything: a response that left
        // before the watermark is refused, and one that left after it holds the patch
        val logged =
            serverPatches.load()[table].orEmpty().filter { patch ->
                watermark == null || patch.tick > watermark
            } + LoggedServerPatch(tick, writes)
        var dropped = 0
        var keptWrites = logged.sumOf { patch -> patch.writes.size }
        var keptLength = logged.sumOf { patch -> patch.length }
        while (keptWrites > MAX_LOGGED_SERVER_WRITES || keptLength > MAX_LOGGED_SERVER_LENGTH) {
            keptWrites -= logged[dropped].writes.size
            keptLength -= logged[dropped].length
            dropped++
        }
        if (dropped > 0) advanceLocalWriteWatermark(table, logged[dropped - 1].tick)
        val remaining = logged.drop(dropped)
        serverPatches.update { current ->
            if (remaining.isEmpty()) current - table else current + (table to remaining)
        }
    }

    /**
     * The server changes patched into [table] after [requestStartedAtTick], oldest first, which a
     * response whose request started then may not hold. Caller holds the table's write lock. `null`
     * is a writer with no request behind it, which has none laid over it.
     */
    fun serverWritesSince(table: String, requestStartedAtTick: Long?): List<MutationRow> {
        if (requestStartedAtTick == null) return emptyList()
        val logged = serverPatches.load()[table] ?: return emptyList()
        return logged
            .filter { patch -> requestStartedAtTick < patch.tick }
            .flatMap { patch -> patch.writes }
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
     * backward. Caller holds the clear lock.
     */
    fun markEmptied(tick: Long, queueCleared: Boolean) {
        // Never move a watermark backward: the earlier of two racing clears would re-admit
        // responses the later one disqualified.
        if (queueCleared) cacheClearedTick.update { existing -> maxOf(existing, tick) }
        cacheDroppedTick.update { existing -> maxOf(existing, tick) }
        // A response that left before the drop is refused, and one that left after it holds what
        // the server changed before it: no server change logged until now is laid over anything
        serverPatches.update { current ->
            current
                .mapValues { (_, logged) -> logged.filter { patch -> patch.tick > tick } }
                .filterValues { logged -> logged.isNotEmpty() }
        }
    }

    /**
     * Runs [write] unless the cache was cleared or dropped since [guardTick], as one step with the
     * clear. [write] runs under the clear lock, so it takes no lock but the records'.
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

    internal companion object {
        /**
         * The most server-change writes [CacheFreshness.logServerPatch] keeps for one table: a
         * Realtime change is one write, and a burst applied as one patch is as many. Beyond it the
         * oldest patches go and a response that left before them is refused.
         */
        const val MAX_LOGGED_SERVER_WRITES = 256

        /**
         * The most characters of JSON, bodies and filters, [CacheFreshness.logServerPatch] keeps
         * for one table: what bounds the log of a table with long rows, which keeps it while the
         * process lives. Beyond it the oldest patches go, as beyond [MAX_LOGGED_SERVER_WRITES].
         */
        const val MAX_LOGGED_SERVER_LENGTH = 64L * 1024
    }
}
