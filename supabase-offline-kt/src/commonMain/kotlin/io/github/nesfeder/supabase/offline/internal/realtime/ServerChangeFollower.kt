// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
 * @param scope where the following runs; canceling it leaves the channel.
 * @param tables the tables to follow; none follows nothing.
 * @param feed where the changes come from.
 * @param defaultSchema the schema of a table that names none, read at each join.
 * @param sessionReady `true` while there is a user to subscribe for; `true` once without Auth.
 * @param networkStatuses the network as the client sees it, as it changes.
 * @param wakeUps the edges that cut a wait short: a reconnection, a settled session.
 * @param apply applies one change to the cache.
 * @param markStale marks a table's cached queries stale and has its live readers fetch again.
 * @param initialBackoff the wait after the first channel that ended.
 * @param maxBackoff the longest wait.
 * @param steadyFollow how long a follow must last for the next wait to start over.
 */
internal class ServerChangeFollower(
    private val scope: CoroutineScope,
    private val tables: List<WatchedTable>,
    private val feed: ServerChangeFeed,
    private val defaultSchema: () -> String,
    private val sessionReady: Flow<Boolean>,
    private val networkStatuses: Flow<NetworkStatus>,
    private val wakeUps: Flow<Unit>,
    private val apply: suspend (ServerChangeEvent) -> Unit,
    private val markStale: suspend (table: String, schema: String) -> Unit,
    private val engineLogger: EngineLogger,
    private val initialBackoff: Duration = 1.seconds,
    private val maxBackoff: Duration = 60.seconds,
    private val steadyFollow: Duration = 1.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    /** Whether the app wants the subscription; on until it says otherwise. */
    private val enabled = MutableStateFlow(true)

    private val followState =
        MutableStateFlow<ServerChangeFollowState>(
            if (tables.isEmpty()) ServerChangeFollowState.Off else ServerChangeFollowState.Paused
        )

    /** Where the subscription stands, as it changes. */
    val state: StateFlow<ServerChangeFollowState> = followState.asStateFlow()

    /** The following, once started. */
    private var following: Job? = null

    /** Starts following in [scope]; nothing happens without tables. */
    fun start() {
        if (tables.isEmpty() || following != null) return
        following = scope.launch { keepFollowing() }
    }

    /** Switches the subscription on or off; off leaves the channel until on. */
    fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    /** Records that the client closed: the channel is left with [scope], and the state is final. */
    fun stop() {
        followState.value = ServerChangeFollowState.Off
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
                    Wanted.OFF -> followState.value = ServerChangeFollowState.Off
                    Wanted.PAUSED -> followState.value = ServerChangeFollowState.Paused
                    Wanted.FOLLOW -> followUntilCanceled()
                }
            }
    }

    /** Follows, and joins again after each channel that ends, until canceled. */
    private suspend fun followUntilCanceled() {
        var backoff = Duration.ZERO
        while (true) {
            followState.value = ServerChangeFollowState.Subscribing
            val started = timeSource.markNow()
            val failure = followOnce()
            followState.value = ServerChangeFollowState.Stalled(failure)
            engineLogger.warning {
                "Realtime: the channel ended (${failure::class.simpleName}); joining again"
            }
            backoff =
                if (backoff == Duration.ZERO || started.elapsedNow() >= steadyFollow) initialBackoff
                else minOf(backoff * 2, maxBackoff)
            withTimeoutOrNull(backoff) { wakeUps.first() }
        }
    }

    /** Follows once, applying what arrives; returns the failure that ended the channel. */
    private suspend fun followOnce(): Exception {
        try {
            feed.follow(tables, defaultSchema()).collect { event -> handle(event) }
            return ServerChangeFeedEndedException("The Realtime channel ended")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return failure
        }
    }

    private suspend fun handle(event: ServerChangeEvent) {
        when (event) {
            ServerChangeEvent.Joined -> {
                followState.value = ServerChangeFollowState.Subscribed
                engineLogger.info {
                    "Realtime: subscribed to ${tables.size} table(s); their cached queries are " +
                        "read again"
                }
                val defaultSchema = defaultSchema()
                for (watched in tables) {
                    markStaleQuietly(watched.table, watched.schema ?: defaultSchema)
                }
            }
            is ServerChangeEvent.Saved -> applyQuietly(event, event.table, event.schema)
            is ServerChangeEvent.Deleted -> applyQuietly(event, event.table, event.schema)
        }
    }

    /**
     * Applies [event]; a failure is logged, and the table's cached queries are marked stale so a
     * live reader fetches what the change would have shown.
     */
    private suspend fun applyQuietly(event: ServerChangeEvent, table: String, schema: String) {
        try {
            apply(event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Realtime: applying a change to $table failed (${failure::class.simpleName}); " +
                    "its cached queries are marked stale"
            }
            markStaleQuietly(table, schema)
        }
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
}
