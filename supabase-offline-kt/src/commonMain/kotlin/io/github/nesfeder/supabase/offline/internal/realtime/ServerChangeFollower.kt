// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.RealtimeTable
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Follows the watched tables' changes on the server while they are wanted, and applies them to the
 * cache: joined while the subscription is on, there is an identity to subscribe for (a signed-in
 * user, or the one identity of a client without Auth) and the network is not known to be down; left
 * otherwise, and when [scope] ends. A change of identity, a sign-in as another user included,
 * leaves the channels and joins them again for the new one; a new session of the same identity
 * changes nothing.
 *
 * Each table is followed on a channel of its own, so a binding the server refuses (a filter on a
 * column a migration renamed, a table missing from the publication) ends that table's channel
 * alone: the others keep receiving. Each join reads its table again, since Realtime replays
 * nothing. A channel that ends is joined again after a bounded wait, cut short by an edge; joins of
 * a table that end within [steadyFollow] of one another back off, up to [maxBackoff], each table on
 * its own. [tableState] says where each table stands, and [state] sums them up.
 *
 * The changes a follow receives wait in a [ChangeBacklog], taken off the feed as they arrive, and
 * are applied beside it, the waiting ones as one patch: a burst from the server costs one pass over
 * the table's cached queries, not one per change, and a slow patch never holds the feed. A backlog
 * past [backlogCapacity] is discarded, with every change the applier had taken but not applied yet,
 * and once the applier is free the table is read again instead, as on a join, with one warning for
 * the episode. A batch is applied only while the inputs still want the follow it came from and the
 * session is still its identity's, read fresh: what an earlier user's channel still delivers, once
 * another user has signed in, is dropped instead of reaching the new user's cache, and [apply] is
 * told the identity so the cache can refuse it under its own lock.
 *
 * A channel that ends is logged at DEBUG; the warning comes when it is joined again, so an end that
 * a sign-out, a network known down, the switch or [stop] brought about, which pauses the follow
 * before the wait is over, leaves no warning. After [stop] the state is final: nothing a channel
 * still ending reports changes it.
 *
 * @param scope where the following runs; canceling it leaves the channel.
 * @param tables the tables to follow; none follows nothing.
 * @param feed where the changes come from.
 * @param defaultSchema the schema of a table that names none, read at each join.
 * @param sessionIdentity the identity to subscribe for, as it changes: `null` while there is none;
 *   one value, once, without Auth.
 * @param currentIdentity what [sessionIdentity] says now, read fresh: the session may have changed
 *   before the follower hears of it.
 * @param networkStatuses the network as the client sees it, as it changes.
 * @param wakeUps the edges that cut a wait short: a reconnection, a settled session.
 * @param apply applies one table's changes, in the order they arrived, to the cache, for the
 *   identity the follow was joined for.
 * @param markStale marks a table's cached queries stale and has its live readers fetch again.
 * @param initialBackoff the wait after the first channel that ended.
 * @param maxBackoff the longest wait.
 * @param steadyFollow how long a follow must last for the next wait to start over.
 * @param backlogCapacity how many received changes of one table may wait to be applied.
 */
internal class ServerChangeFollower(
    private val scope: CoroutineScope,
    private val tables: List<WatchedTable>,
    private val feed: ServerChangeFeed,
    private val defaultSchema: () -> String,
    private val sessionIdentity: Flow<String?>,
    private val currentIdentity: () -> String?,
    private val networkStatuses: Flow<NetworkStatus>,
    private val wakeUps: Flow<Unit>,
    private val apply: suspend (identity: String, changes: List<ServerChangeEvent.Change>) -> Unit,
    private val markStale: suspend (table: String, schema: String) -> Unit,
    private val engineLogger: EngineLogger,
    private val initialBackoff: Duration = 1.seconds,
    private val maxBackoff: Duration = 60.seconds,
    private val steadyFollow: Duration = 1.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val backlogCapacity: Int = BACKLOG_CAPACITY,
) {

    /** Whether the app wants the subscription; on until it says otherwise. */
    private val enabled = MutableStateFlow(true)

    /** Each table as the configuration names it, in [tables]' order: the keys of [tableState]. */
    private val named: List<RealtimeTable> = tables.map { watched -> watched.toRealtimeTable() }

    /** Where each table stands: the one record both [tableState] and [state] are read from. */
    private val tableStates =
        MutableStateFlow<Map<RealtimeTable, RealtimeStatus>>(everyTable(RealtimeStatus.Paused))

    private val followState =
        MutableStateFlow<RealtimeStatus>(
            if (tables.isEmpty()) RealtimeStatus.Off else RealtimeStatus.Paused
        )

    /** Where each table stands, as it changes, in the order the tables were named. */
    val tableState: StateFlow<Map<RealtimeTable, RealtimeStatus>> = tableStates.asStateFlow()

    /** Where the subscription stands, as it changes: [tableState] summed up ([aggregateOf]). */
    val state: StateFlow<RealtimeStatus> = followState.asStateFlow()

    /** Whether [stop] has made the state final. */
    private val stopped = AtomicBoolean(false)

    /** What the inputs ask for, as last combined; what a follow whose channel ended checks. */
    private val wantedNow = MutableStateFlow<Wanted>(Wanted.Paused)

    /** The following, once started. */
    private var following: Job? = null

    /** Starts following in [scope]; nothing happens without tables. */
    fun start() {
        if (tables.isEmpty() || following != null) return
        following = scope.launch { keepFollowing() }
    }

    /**
     * Switches the subscription on or off; off leaves the channel until on. Off is reported at
     * once, so a caller sees it before the channel is left.
     */
    fun setEnabled(enabled: Boolean) {
        if (!enabled && tables.isNotEmpty()) reportEveryTable(RealtimeStatus.Off)
        this.enabled.value = enabled
    }

    /**
     * Records that the client closed: the channels are left with [scope], and the state is final,
     * whatever a channel still ending reports after this.
     */
    fun stop() {
        stopped.store(true)
        tableStates.value = everyTable(RealtimeStatus.Off)
        followState.value = RealtimeStatus.Off
    }

    /** Every table at [status], in the order they were named. */
    private fun everyTable(status: RealtimeStatus): Map<RealtimeTable, RealtimeStatus> =
        named.associateWith {
            status
        }

    /** Reports every table at [status], unless [stop] has made the state final. */
    private fun reportEveryTable(status: RealtimeStatus) {
        publish { everyTable(status) }
    }

    /** Reports [table] at [status], unless [stop] has made the state final. */
    private fun report(table: RealtimeTable, status: RealtimeStatus) {
        publish { current -> current + (table to status) }
    }

    /**
     * Writes [change] into the tables' statuses, then the sum of them into [state], again until
     * what it summed is still the latest: a table that changed meanwhile has its own writer, which
     * sums again after it, so the sum read last is always that of the latest statuses. After [stop]
     * nothing changes but to [RealtimeStatus.Off].
     */
    private fun publish(
        change: (Map<RealtimeTable, RealtimeStatus>) -> Map<RealtimeTable, RealtimeStatus>
    ) {
        tableStates.update { current -> if (stopped.load()) current else change(current) }
        while (true) {
            // A stop between the check and the write: its Off stands, whatever landed after it
            if (stopped.load()) {
                tableStates.value = everyTable(RealtimeStatus.Off)
                followState.value = RealtimeStatus.Off
                return
            }
            val statuses = tableStates.value
            followState.value = aggregateOf(statuses)
            if (tableStates.value === statuses) return
        }
    }

    /** What the inputs ask for. */
    private sealed interface Wanted {
        data object Off : Wanted

        data object Paused : Wanted

        /** Follow for [identity]; another identity is another follow. */
        data class Follow(val identity: String) : Wanted
    }

    /**
     * Follows while the inputs say so. One distinct answer per change of inputs, so a change that
     * leaves the answer alone never restarts the channel, and a change of identity does.
     */
    private suspend fun keepFollowing() {
        combine(enabled, sessionIdentity, networkStatuses) { on, identity, network ->
                when {
                    !on -> Wanted.Off
                    identity == null ||
                        network == NetworkStatus.OFFLINE ||
                        network == NetworkStatus.DISABLED -> Wanted.Paused
                    else -> Wanted.Follow(identity)
                }
            }
            .onEach { wanted -> wantedNow.value = wanted }
            .distinctUntilChanged()
            .collectLatest { wanted ->
                when (wanted) {
                    Wanted.Off -> reportEveryTable(RealtimeStatus.Off)
                    Wanted.Paused -> reportEveryTable(RealtimeStatus.Paused)
                    is Wanted.Follow -> followUntilCanceled(wanted)
                }
            }
    }

    /**
     * Follows every table for [wanted]'s identity, each on a channel of its own, until canceled.
     * Every table reads as joining at once, so none reads as paused while another's join is in
     * flight.
     */
    private suspend fun followUntilCanceled(wanted: Wanted.Follow) {
        reportEveryTable(RealtimeStatus.Subscribing)
        coroutineScope {
            tables.zip(named) { watched, table ->
                launch { followTableUntilCanceled(wanted, watched, table) }
            }
        }
    }

    /**
     * Follows [watched], named [table], for [wanted]'s identity, and joins it again after each
     * channel that ends, until canceled; its waits are its own.
     */
    private suspend fun followTableUntilCanceled(
        wanted: Wanted.Follow,
        watched: WatchedTable,
        table: RealtimeTable,
    ) {
        var backoff = Duration.ZERO
        while (true) {
            report(table, RealtimeStatus.Subscribing)
            val started = timeSource.markNow()
            val failure = followOnce(wanted, watched, table)
            // Canceled as the channel ended: the inputs' next state is the one to report, not
            // this end
            currentCoroutineContext().ensureActive()
            // Inputs that moved on as the channel ended pause the follow in a moment: the end is
            // theirs, and no stall is reported for it
            if (wantedNow.value == wanted) report(table, RealtimeStatus.Stalled(failure))
            engineLogger.debug {
                "Realtime: the channel ended for ${watched.qualifiedName()} " +
                    "(${failure::class.simpleName})"
            }
            backoff =
                if (backoff == Duration.ZERO || started.elapsedNow() >= steadyFollow) initialBackoff
                else minOf(backoff * 2, maxBackoff)
            withTimeoutOrNull(backoff) { wakeUps.first() }
            engineLogger.warning {
                "Realtime: the channel ended for ${watched.qualifiedName()} " +
                    "(${failure::class.simpleName}); joining again"
            }
        }
    }

    /**
     * Follows [watched], named [table], once for [wanted], applying what arrives; returns the
     * failure that ended its channel. A join is handled as it arrives; the changes wait in a
     * backlog that [applyBacklog] drains beside the collection, and what arrived before the channel
     * ended is still applied.
     */
    private suspend fun followOnce(
        wanted: Wanted.Follow,
        watched: WatchedTable,
        table: RealtimeTable,
    ): Exception = coroutineScope {
        val backlog = ChangeBacklog(backlogCapacity)
        launch { applyBacklog(wanted, watched, backlog) }
        try {
            feed.follow(listOf(watched), defaultSchema()).collect { event ->
                receive(event, watched, table, backlog)
            }
            ServerChangeFeedEndedException("The Realtime channel ended")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failure
        } finally {
            backlog.close()
        }
    }

    /** Handles [event] of [watched]'s channel: a join at once, a change by way of [backlog]. */
    private suspend fun receive(
        event: ServerChangeEvent,
        watched: WatchedTable,
        table: RealtimeTable,
        backlog: ChangeBacklog,
    ) {
        when (event) {
            ServerChangeEvent.Joined -> joined(watched, table)
            is ServerChangeEvent.Change -> backlog.add(event)
        }
    }

    /** Reports [table] subscribed, and reads it again: nothing is replayed. */
    private suspend fun joined(watched: WatchedTable, table: RealtimeTable) {
        report(table, RealtimeStatus.Subscribed)
        engineLogger.info {
            "Realtime: subscribed to ${watched.qualifiedName()}; its cached queries are read again"
        }
        markStaleQuietly(watched)
    }

    /**
     * Applies [backlog]'s changes, those [watched]'s channel delivered, until it is closed and
     * empty: every change waiting, each table's as one patch in the order they arrived, tables in
     * the order of their first change. A batch taken before a discard is dropped instead, and a
     * discard is reported once the take is done. So is a batch taken once the inputs no longer ask
     * for [wanted], the follow it came from, or the session is no longer its identity's: an earlier
     * user's changes never reach the next user's cache.
     */
    private suspend fun applyBacklog(
        wanted: Wanted.Follow,
        watched: WatchedTable,
        backlog: ChangeBacklog,
    ) {
        while (true) {
            val entries = backlog.takeAll()
            if (entries == null) {
                reportDiscard(watched, backlog)
                return
            }
            val byTable = LinkedHashMap<Pair<String, String>, MutableList<ChangeBacklog.Entry>>()
            for (entry in entries) {
                val key = entry.change.table to entry.change.schema
                byTable.getOrPut(key) { mutableListOf() } += entry
            }
            for ((key, batch) in byTable) {
                // Read per batch: a discard during the last patch voids the batches after it
                val generation = backlog.currentGeneration
                val current = batch.filter { entry -> entry.generation == generation }
                if (current.size < batch.size) backlog.countDropped(batch.size - current.size)
                if (current.isEmpty()) continue
                val changes = current.map { entry -> entry.change }
                if (wantedNow.value != wanted || currentIdentity() != wanted.identity) {
                    engineLogger.debug {
                        "Realtime: ${changes.size} change(s) to ${key.first} arrived on a " +
                            "channel that is no longer the session's; they are dropped"
                    }
                    continue
                }
                applyQuietly(wanted.identity, changes, table = key.first, schema = key.second)
            }
            reportDiscard(watched, backlog)
        }
    }

    /**
     * Once changes of [watched] were discarded, reads it again, which brings what they would have,
     * as a join does, and logs it once: a discard during that mark is folded in, and the mark is
     * made again for it. Never a change itself.
     */
    private suspend fun reportDiscard(watched: WatchedTable, backlog: ChangeBacklog) {
        var total = 0
        while (true) {
            val discarded = backlog.takeDiscarded()
            if (discarded == 0) break
            total += discarded
            markStaleQuietly(watched)
        }
        if (total == 0) return
        engineLogger.warning {
            "Realtime: $total change(s) to ${watched.qualifiedName()} waited to be applied, more " +
                "than the $backlogCapacity the client keeps; they are discarded, and its cached " +
                "queries are read again"
        }
    }

    /** `schema.table` when the table names a schema, the table alone otherwise. */
    private fun WatchedTable.qualifiedName(): String =
        schema?.let { named -> "$named.$table" } ?: table

    /**
     * Applies [changes], all of [table], for [identity]; a failure is logged, and the table's
     * cached queries are marked stale so a live reader fetches what the changes would have shown.
     */
    private suspend fun applyQuietly(
        identity: String,
        changes: List<ServerChangeEvent.Change>,
        table: String,
        schema: String,
    ) {
        try {
            apply(identity, changes)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Realtime: applying ${changes.size} change(s) to $table failed " +
                    "(${failure::class.simpleName}); its cached queries are marked stale"
            }
            markStaleQuietly(table, schema)
        }
    }

    /** Marks [watched]'s cached queries stale, in its own schema. */
    private suspend fun markStaleQuietly(watched: WatchedTable) {
        markStaleQuietly(watched.table, watched.schema ?: defaultSchema())
    }

    /** Marks [table]'s cached queries stale; a failure is logged, never thrown. */
    private suspend fun markStaleQuietly(table: String, schema: String) {
        try {
            markStale(table, schema)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Realtime: marking $table's cached queries stale failed " +
                    "(${failure::class.simpleName})"
            }
        }
    }

    companion object {
        /**
         * How many received changes of one table may wait to be applied before its backlog is
         * discarded and the table read again: past this, a fetch costs less than the patch would.
         */
        const val BACKLOG_CAPACITY: Int = 5_000

        /**
         * The tables' statuses summed up, as one value: a stall of any table, the first in the
         * order they were named, before a join in flight, before a pause, before off; subscribed
         * only once every table is. No table is [RealtimeStatus.Off].
         */
        fun aggregateOf(statuses: Map<RealtimeTable, RealtimeStatus>): RealtimeStatus {
            val all = statuses.values
            return all.firstOrNull { status -> status is RealtimeStatus.Stalled }
                ?: when {
                    RealtimeStatus.Subscribing in all -> RealtimeStatus.Subscribing
                    RealtimeStatus.Paused in all -> RealtimeStatus.Paused
                    all.isEmpty() || RealtimeStatus.Off in all -> RealtimeStatus.Off
                    else -> RealtimeStatus.Subscribed
                }
        }
    }
}
