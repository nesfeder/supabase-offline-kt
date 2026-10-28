// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** What the client's channels are named, with a number that makes each join's topic its own. */
internal const val REALTIME_TOPIC_PREFIX: String = "supabase-offline"

/** What supabase-kt puts before a channel's name to make its topic. */
private const val REALTIME_TOPIC_NAMESPACE: String = "realtime:"

/** What a client whose configuration names tables but has no Realtime plugin fails with. */
internal const val REALTIME_NOT_INSTALLED_MESSAGE: String =
    "The offline configuration names tables to follow through Realtime, but the Realtime plugin " +
        "is not installed: add install(Realtime) beside install(Offline)"

/**
 * The server's row changes through supabase-kt's Realtime plugin: one channel per follow, with one
 * `postgres_changes` binding per table, joined with the token the client resolves.
 *
 * Every follow makes a channel with a topic of its own: supabase-kt refuses a binding once a
 * channel has joined and hands back an existing channel of the same topic, which a leave that ran
 * out of time could have left behind. The feed ends when the channel does: a refused join, a close
 * from the server, or a socket that dropped. supabase-kt's own rejoin leaves the channel before it
 * joins again, which ends the feed too, so every join is the follower's, on a fresh channel with a
 * fresh token.
 *
 * The follower follows each table on its own, so each table has a channel of its own, and the
 * channels share supabase-kt's one socket. A leave does two things supabase-kt 3.6.0 leaves undone
 * once another channel keeps that socket open. supabase-kt sends no leave for a channel that is not
 * joined: one still joining, or one the server refused once supabase-kt stopped rejoining it, which
 * the server may still hold, trying its binding again and again; so the leave sends one. And
 * supabase-kt's own rejoin, after a refusal, runs inside the loop that reads the socket and waits
 * there for a reply only that loop could read, so no channel on the socket receives anything until
 * the heartbeat gives up on it; with nothing but this library's channels left on the socket, the
 * leave closes it, which ends the loop, as removing the last channel does. The other tables'
 * channels end with it and are joined again by the follower. An app's own channel is never closed
 * this way: with one on the socket the loop waits for the heartbeat, which `install(Realtime) {
 * maxAttempts = 1 }` avoids.
 *
 * Nothing a burst sends is lost: each table's `postgresChangeFlow` is buffered without limit, which
 * supabase-kt's `callbackFlow` takes as its own buffer, so the `trySend` behind it cannot drop a
 * change once 64 wait; so is the feed's own flow, between those and its collector. The follower
 * bounds what waits, in its backlog.
 *
 * Nor is a change sent as soon as the join is taken: supabase-kt registers a table's callback only
 * when that `callbackFlow`'s producer first runs, and drops a change no callback takes. The
 * producers run unconfined, so each starts inside the collection that asks for it, and every
 * table's callback is in place before the join goes out.
 *
 * @param tokenRefresher keeps the channel's token current for a client without Auth, or `null` when
 *   Auth, or supabase-kt itself, does.
 * @param leaveBound how long a leave may take before the channel is abandoned to supabase-kt.
 */
internal class SupabaseRealtimeFeed(
    private val supabase: SupabaseClient,
    private val engineLogger: EngineLogger,
    private val tokenRefresher: RealtimeTokenRefresher? = null,
    private val leaveBound: Duration = LEAVE_BOUND,
) : ServerChangeFeed {

    /** Numbers the joins, for the topics. */
    private val joins = AtomicLong(0L)

    override fun follow(
        tables: List<WatchedTable>,
        defaultSchema: String,
    ): Flow<ServerChangeEvent> = channelFlow {
        val realtime = supabase.realtime
        val channel = supabase.channel("$REALTIME_TOPIC_PREFIX-${joins.incrementAndFetch()}")
        // Whether a join went out: a channel that never asked has nothing to leave on the server
        var joinSent = false
        try {
            for (watched in tables) {
                val schema = watched.schema ?: defaultSchema
                val changes =
                    channel
                        .postgresChangeFlow<PostgresAction>(schema) {
                            table = watched.table
                            watched.filter?.let { filter(it.column, it.operator, it.value) }
                        }
                        // Fused into supabase-kt's callbackFlow: its channel, not one after
                        // it, so its trySend never finds the buffer full
                        .buffer(Channel.UNLIMITED)
                        // Fused too: the producer, which registers the table's callback, starts
                        // inside the collection below rather than when a dispatcher gets to it
                        .flowOn(Dispatchers.Unconfined)
                launch(start = CoroutineStart.UNDISPATCHED) {
                    changes.collect { action ->
                        action.toEvent(watched.table, schema)?.let { event -> send(event) }
                    }
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) { reportStatus(channel) }
            awaitQueuedRegistrations()
            joinSent = true
            channel.subscribe()
            tokenRefresher?.let { refresher -> launch { refresher.keepFresh() } }
            awaitClose()
        } finally {
            withContext(NonCancellable) { leave(realtime, channel, joinSent) }
        }
    }
        .buffer(Channel.UNLIMITED)

    /**
     * Lets the producers the loop above started register their callbacks before the join. They ran
     * at once unless this feed itself runs on a dispatcher that runs its work in place (unconfined,
     * or `Dispatchers.Main.immediate` on the main thread): there they may wait in the thread's
     * queue behind this coroutine, and a yield lets them run first.
     */
    private suspend fun awaitQueuedRegistrations() {
        val context = currentCoroutineContext()
        val dispatcher = context[ContinuationInterceptor] as? CoroutineDispatcher ?: return
        if (!dispatcher.isDispatchNeeded(context)) yield()
    }

    /**
     * Sends [ServerChangeEvent.Joined] each time the channel subscribes, and fails the feed once a
     * channel that was joining or joined is being left or is unsubscribed. Only supabase-kt leaves
     * a channel the feed still follows: for a rejoin after a refused join, or for a close from the
     * server. Its rejoin waits, inside the loop that reads the socket, for the reply to its own
     * leave, which that loop can then never read, so the leave itself is the end.
     */
    private suspend fun ProducerScope<ServerChangeEvent>.reportStatus(channel: RealtimeChannel) {
        var started = false
        channel.status.collect { status ->
            when (status) {
                RealtimeChannel.Status.SUBSCRIBING -> started = true
                RealtimeChannel.Status.SUBSCRIBED -> {
                    started = true
                    send(ServerChangeEvent.Joined)
                }
                RealtimeChannel.Status.UNSUBSCRIBING,
                RealtimeChannel.Status.UNSUBSCRIBED ->
                    if (started) {
                        throw ServerChangeFeedEndedException(
                            "The Realtime channel ended: the join was refused, or the server " +
                                "closed the channel"
                        )
                    }
            }
        }
    }

    /**
     * Leaves [channel] within [leaveBound]: removes it, sends the leave supabase-kt does not, and
     * frees the socket's read loop that supabase-kt's own rejoin of it stopped, when only this
     * library's channels are left on the socket. A failure to leave is logged, never thrown.
     *
     * @param joinSent whether a join of [channel] went out.
     */
    private suspend fun leave(realtime: Realtime, channel: RealtimeChannel, joinSent: Boolean) {
        val left =
            withTimeoutOrNull(leaveBound) {
                try {
                    // Read before the removal, which leaves a joined channel itself
                    val status = channel.status.value
                    realtime.removeChannel(channel)
                    if (joinSent && realtime.status.value == Realtime.Status.CONNECTED) {
                        finishLeaving(realtime, channel, status)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    engineLogger.debug {
                        "Realtime: leaving the channel failed (${failure::class.simpleName})"
                    }
                }
            }
        if (left == null) {
            engineLogger.debug { "Realtime: leaving the channel took longer than $leaveBound" }
        }
    }

    /**
     * Does what [Realtime.removeChannel] left undone for [channel], removed at [status] from a
     * socket still connected: sends the leave of a channel joining, or refused, which the server
     * may still hold; or, for one supabase-kt's own rejoin was leaving, closes the socket when
     * nothing but this library's channels is left on it, which ends the read loop the rejoin
     * stopped. The leave goes out after the removal, so its reply, an error for a channel the
     * server no longer holds, reaches no channel.
     */
    private suspend fun finishLeaving(
        realtime: Realtime,
        channel: RealtimeChannel,
        status: RealtimeChannel.Status,
    ) {
        when (status) {
            RealtimeChannel.Status.SUBSCRIBING,
            RealtimeChannel.Status.UNSUBSCRIBED -> channel.unsubscribe()
            RealtimeChannel.Status.UNSUBSCRIBING ->
                if (realtime.subscriptions.keys.all(::isOwnTopic)) {
                    engineLogger.debug {
                        "Realtime: a refused channel stopped the socket's read loop; the socket " +
                            "is closed, and the tables' channels are joined again"
                    }
                    realtime.disconnect()
                }
            RealtimeChannel.Status.SUBSCRIBED -> Unit
        }
    }

    /** Whether [topic] is one of this library's channels. */
    private fun isOwnTopic(topic: String): Boolean =
        topic.startsWith("$REALTIME_TOPIC_NAMESPACE$REALTIME_TOPIC_PREFIX-")

    /** This change as the feed reports it; `null` for a kind the feed never asked for. */
    private fun PostgresAction.toEvent(table: String, schema: String): ServerChangeEvent? =
        when (this) {
            is PostgresAction.Insert -> ServerChangeEvent.Saved(table, schema, record)
            is PostgresAction.Update -> ServerChangeEvent.Saved(table, schema, record)
            is PostgresAction.Delete -> ServerChangeEvent.Deleted(table, schema, oldRecord)
            is PostgresAction.Select -> null
        }

    companion object {
        /** How long a leave may take before the channel is abandoned to supabase-kt. */
        val LEAVE_BOUND: Duration = 2.seconds

        /**
         * The feed over the Realtime plugin installed in [supabase]. Without the Auth plugin, and
         * with the client's own token provider, it keeps each channel's token current itself,
         * handing Realtime the token its own provider resolves: the client's, unless the app gave
         * the Realtime plugin a token or a provider of its own, which the join uses too.
         *
         * @throws IllegalStateException when the Realtime plugin is not installed.
         */
        @OptIn(SupabaseInternal::class)
        fun forInstalledPlugin(
            supabase: SupabaseClient,
            engineLogger: EngineLogger,
            clock: Clock,
        ): SupabaseRealtimeFeed {
            val realtime =
                checkNotNull(supabase.pluginManager.getPluginOrNull(Realtime)) {
                    REALTIME_NOT_INSTALLED_MESSAGE
                }
            val refresher =
                if (
                    supabase.pluginManager.getPluginOrNull(Auth) != null ||
                        supabase.accessToken == null
                )
                    null
                else
                    RealtimeTokenRefresher(
                        accessToken = { realtime.config.accessToken(supabase) },
                        refresh = { token -> realtime.setAuth(token) },
                        clock = clock,
                        engineLogger = engineLogger,
                    )
            return SupabaseRealtimeFeed(supabase, engineLogger, refresher)
        }
    }
}
