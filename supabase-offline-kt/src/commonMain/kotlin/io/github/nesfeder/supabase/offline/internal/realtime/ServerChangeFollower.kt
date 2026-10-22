// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Follows the watched tables' changes on the server while they are wanted, and applies them to the
 * cache: joined while the subscription is on, a user is signed in (or there is no Auth plugin) and
 * the network is not known to be down; left otherwise, and when [scope] ends. Each join reads the
 * watched tables again, since Realtime replays nothing. A channel that ends is joined again after a
 * bounded wait, cut short by an edge; joins that end within [steadyFollow] of one another back off,
 * up to [maxBackoff].
 *
 * The changes a follow receives wait in a [ChangeBacklog], taken off the feed as they arrive, and
 * are applied beside it, each table's waiting changes as one patch: a burst from the server costs
 * one pass over the table's cached queries, not one per change, and a slow patch never holds the
 * feed. A backlog past [backlogCapacity] is discarded, and every watched table is read again
 * instead, as on a join.
 *
 * @param scope where the following runs; canceling it leaves the channel.
 * @param tables the tables to follow; none follows nothing.
 * @param feed where the changes come from.
 * @param defaultSchema the schema of a table that names none, read at each join.
 * @param sessionReady `true` while there is a user to subscribe for; `true` once without Auth.
 * @param networkStatuses the network as the client sees it, as it changes.
 * @param wakeUps the edges that cut a wait short: a reconnection, a settled session.
 * @param apply applies one table's changes, in the order they arrived, to the cache.
 * @param markStale marks a table's cached queries stale and has its live readers fetch again.
 * @param initialBackoff the wait after the first channel that ended.
 * @param maxBackoff the longest wait.
 * @param steadyFollow how long a follow must last for the next wait to start over.
 * @param backlogCapacity how many received changes may wait to be applied.
 */
internal class ServerChangeFollower(
    private val scope: CoroutineScope,
    private val tables: List<WatchedTable>,
    private val feed: ServerChangeFeed,
    private val defaultSchema: () -> String,
    private val sessionReady: Flow<Boolean>,
    private val networkStatuses: Flow<NetworkStatus>,
    private val wakeUps: Flow<Unit>,
    private val apply: suspend (List<ServerChangeEvent.Change>) -> Unit,
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

    private val followState =
        MutableStateFlow<RealtimeStatus>(
            if (tables.isEmpty()) RealtimeStatus.Off else RealtimeStatus.Paused
        )

    /** Where the subscription stands, as it changes. */
    val state: StateFlow<RealtimeStatus> = followState.asStateFlow()

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
        if (!enabled && tables.isNotEmpty()) followState.value = RealtimeStatus.Off
        this.enabled.value = enabled
    }

    /** Records that the client closed: the channel is left with [scope], and the state is final. */
    fun stop() {
        followState.value = RealtimeStatus.Off
    }

    /** What the inputs ask for. */
    private enum class Wanted {
        OFF,
        PAUSED,
        FOLLOW,
    }

    /**
     * Follows while the inputs say so. One distinct answer per change of inputs, so a change that
     * leaves the answer alone never restarts the channel.
     */
    private suspend fun keepFollowing() {
        combine(enabled, sessionReady, networkStatuses) { on, ready, network ->
                when {
                    !on -> Wanted.OFF
                    !ready ||
                        network == NetworkStatus.OFFLINE ||
                        network == NetworkStatus.DISABLED -> Wanted.PAUSED
                    else -> Wanted.FOLLOW
                }
            }
            .distinctUntilChanged()
            .collectLatest { wanted ->
                when (wanted) {
                    Wanted.OFF -> followState.value = RealtimeStatus.Off
                    Wanted.PAUSED -> followState.value = RealtimeStatus.Paused
                    Wanted.FOLLOW -> followUntilCanceled()
                }
            }
    }

    /** Follows, and joins again after each channel that ends, until canceled. */
    private suspend fun followUntilCanceled() {
        var backoff = Duration.ZERO
        while (true) {
            followState.value = RealtimeStatus.Subscribing
            val started = timeSource.markNow()
            val failure = followOnce()
            followState.value = RealtimeStatus.Stalled(failure)
            engineLogger.warning {
                "Realtime: the channel ended (${failure::class.simpleName}); joining again"
            }
            backoff =
                if (backoff == Duration.ZERO || started.elapsedNow() >= steadyFollow) initialBackoff
                else minOf(backoff * 2, maxBackoff)
            withTimeoutOrNull(backoff) { wakeUps.first() }
        }
    }

    /**
     * Follows once, applying what arrives; returns the failure that ended the channel. A join is
     * handled as it arrives; the changes wait in a backlog that [applyBacklog] drains beside the
     * collection, and what arrived before the channel ended is still applied.
     */
    private suspend fun followOnce(): Exception = coroutineScope {
        val backlog = ChangeBacklog(backlogCapacity)
        launch { applyBacklog(backlog) }
        try {
            feed.follow(tables, defaultSchema()).collect { event -> receive(event, backlog) }
            ServerChangeFeedEndedException("The Realtime channel ended")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failure
        } finally {
            backlog.close()
        }
    }

    /** Handles [event]: a join at once, a change by way of [backlog]. */
    private suspend fun receive(event: ServerChangeEvent, backlog: ChangeBacklog) {
        when (event) {
            ServerChangeEvent.Joined -> joined()
            is ServerChangeEvent.Change -> {
                val discarded = backlog.add(event)
                if (discarded > 0) backlogDiscarded(discarded)
            }
        }
    }

    /** Reports the channel subscribed, and reads every watched table again: nothing is replayed. */
    private suspend fun joined() {
        followState.value = RealtimeStatus.Subscribed
        engineLogger.info {
            "Realtime: subscribed to ${tables.size} table(s); their cached queries are read again"
        }
        markEveryTableStale()
    }

    /**
     * Logs that [count] waiting changes were discarded, and reads every watched table again, which
     * brings what they would have, as a join does. Never a change itself.
     */
    private suspend fun backlogDiscarded(count: Int) {
        engineLogger.warning {
            "Realtime: $count change(s) waited to be applied, more than the $backlogCapacity the " +
                "client keeps; they are discarded, and the cached queries of " +
                "${tables.joinToString { watched -> watched.table }} are read again"
        }
        markEveryTableStale()
    }

    /**
     * Applies [backlog]'s changes until it is closed and empty: every change waiting, each table's
     * as one patch in the order they arrived, tables in the order of their first change.
     */
    private suspend fun applyBacklog(backlog: ChangeBacklog) {
        while (true) {
            val changes = backlog.takeAll() ?: return
            val byTable =
                LinkedHashMap<Pair<String, String>, MutableList<ServerChangeEvent.Change>>()
            for (change in changes) {
                byTable.getOrPut(change.table to change.schema) { mutableListOf() } += change
            }
            for ((key, batch) in byTable) {
                applyQuietly(batch, table = key.first, schema = key.second)
            }
        }
    }

    /**
     * Applies [changes], all of [table]; a failure is logged, and the table's cached queries are
     * marked stale so a live reader fetches what the changes would have shown.
     */
    private suspend fun applyQuietly(
        changes: List<ServerChangeEvent.Change>,
        table: String,
        schema: String,
    ) {
        try {
            apply(changes)
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

    /** Marks every watched table's cached queries stale, each in its own schema. */
    private suspend fun markEveryTableStale() {
        val defaultSchema = defaultSchema()
        for (watched in tables) markStaleQuietly(watched.table, watched.schema ?: defaultSchema)
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
         * How many received changes may wait to be applied before the backlog is discarded and the
         * watched tables read again: past this, a fetch costs less than the patch would.
         */
        const val BACKLOG_CAPACITY: Int = 5_000
    }
}
