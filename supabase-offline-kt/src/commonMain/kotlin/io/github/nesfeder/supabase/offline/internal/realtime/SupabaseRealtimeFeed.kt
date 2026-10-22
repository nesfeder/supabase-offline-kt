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
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the client's channels are named, with a number that makes each join's topic its own. */
internal const val REALTIME_TOPIC_PREFIX: String = "supabase-offline"

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
 * Nothing a burst sends is lost: each table's `postgresChangeFlow` is buffered without limit, which
 * supabase-kt's `callbackFlow` takes as its own buffer, so the `trySend` behind it cannot drop a
 * change once 64 wait; so is the feed's own flow, between those and its collector. The follower
 * bounds what waits, in its backlog.
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
                launch(start = CoroutineStart.UNDISPATCHED) {
                    changes.collect { action ->
                        action.toEvent(watched.table, schema)?.let { event -> send(event) }
                    }
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) { reportStatus(channel) }
            channel.subscribe()
            tokenRefresher?.let { refresher -> launch { refresher.keepFresh() } }
            awaitClose()
        } finally {
            withContext(NonCancellable) { leave(realtime, channel) }
        }
    }
        .buffer(Channel.UNLIMITED)

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

    /** Leaves [channel] within [leaveBound]; a failure to leave is logged, never thrown. */
    private suspend fun leave(realtime: Realtime, channel: RealtimeChannel) {
        val left =
            withTimeoutOrNull(leaveBound) {
                try {
                    realtime.removeChannel(channel)
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
