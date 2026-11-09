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
import io.github.jan.supabase.realtime.RealtimeSystemPayload
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
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

/** The extension a `system` message names when it is about the channel's table bindings. */
private const val POSTGRES_CHANGES_EXTENSION: String = "postgres_changes"

/** The status of a `system` message that reports a failure. */
private const val SYSTEM_STATUS_ERROR: String = "error"

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
 * out of time could have left behind. The feed ends when the channel does: a binding the server
 * refused, a join supabase-kt gave up on, a close from the server, or a socket that dropped. Every
 * join after an end is the follower's, on a fresh channel with a fresh token.
 *
 * The server refuses a binding (a filter on a column a migration renamed, a table missing from the
 * publication) after it took the join, with a `system` message whose status is `error`. supabase-kt
 * logs it and keeps the channel joined, so the feed watches the channel's system messages and ends
 * on such a message from the `postgres_changes` extension: otherwise it would report the table
 * joined and deliver nothing. supabase-kt's own rejoins do not end the feed. A join the server
 * answers with an error is joined again every `rejoinDelay`, the channel still joining, until
 * `maxAttempts` refusals, when supabase-kt unsubscribes it, which ends the feed. A channel error
 * (`phx_error`) while joined counts the same way: below `maxAttempts` the channel is joined again,
 * and the feed reports joined once more, which reads the table again. With supabase-kt's default
 * `requireValidSession = true`, a token provider that throws makes supabase-kt join again every
 * `rejoinDelay` until it stops throwing, without counting those attempts, and the channel stays
 * joining, so the table stays subscribing.
 *
 * The follower follows each table on its own, so each table has a channel of its own, and the
 * channels share supabase-kt's one socket. supabase-kt sends no leave for a channel that is not
 * joined, so the leave sends one for a channel still joining, which the server may hold once the
 * join reaches it; it also cancels a rejoin supabase-kt has scheduled for it, which would put the
 * removed channel back on the socket. A channel whose binding the server refused is still joined,
 * and supabase-kt's removal leaves it. Once the last channel is gone, supabase-kt closes the socket
 * after `disconnectOnEmptyChannelsAfter`.
 *
 * Nothing a burst sends is lost: each table's `postgresChangeFlow` is buffered without limit, which
 * supabase-kt's `callbackFlow` takes as its own buffer, so the `trySend` behind it cannot drop a
 * change once 64 wait; so is the feed's own flow, between those and its collector. The follower
 * bounds what waits, in its backlog.
 *
 * Nor is a change sent as soon as the join is taken: supabase-kt registers a table's callback only
 * when that `callbackFlow`'s producer first runs, and drops a change no callback takes. The
 * producers run unconfined, so each starts inside the collection that asks for it, and every
 * table's callback, and the one for the channel's system messages, is in place before the join goes
 * out.
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
            val systemMessages =
                channel
                    .systemFlow()
                    // Fused, as each table's changes are: a refusal waits in supabase-kt's
                    // callbackFlow, and its callback is in place before the join goes out
                    .buffer(Channel.UNLIMITED)
                    .flowOn(Dispatchers.Unconfined)
            launch(start = CoroutineStart.UNDISPATCHED) { reportRefusals(systemMessages) }
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
     * queue of such work, behind this coroutine. A yield in that queue lets them run first. Where
     * the queue is empty, as on any dispatcher that dispatches, it returns at once, without a
     * dispatch.
     */
    private suspend fun awaitQueuedRegistrations() {
        withContext(Dispatchers.Unconfined) { yield() }
    }

    /**
     * Fails the feed once the server refuses one of the channel's table bindings: a system message
     * from the `postgres_changes` extension with an error status, which supabase-kt only logs,
     * keeping the channel joined. An error from another extension is logged and changes nothing:
     * the bindings still deliver. A system message that is not an error says the channel joined,
     * which [reportStatus] reports.
     */
    private suspend fun reportRefusals(messages: Flow<RealtimeSystemPayload>) {
        messages.collect { message ->
            if (message.status != SYSTEM_STATUS_ERROR) return@collect
            if (message.extension == POSTGRES_CHANGES_EXTENSION) {
                throw ServerChangeFeedEndedException(
                    "The server refused the Realtime channel's postgres_changes binding"
                )
            }
            engineLogger.debug {
                "Realtime: the server reported an error from the channel's " +
                    "${message.extension} extension; the channel is still followed"
            }
        }
    }

    /**
     * Sends [ServerChangeEvent.Joined] each time the channel subscribes, and fails the feed once a
     * channel that was joining or joined is being left or is unsubscribed. Only supabase-kt does
     * that to a channel the feed still follows: once it gives up on a join the server refused
     * `maxAttempts` times, on a close from the server, and on a socket that disconnected. Its
     * rejoins before that keep the channel joining, and a rejoin after a channel error while joined
     * subscribes it again, which sends Joined again.
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
                            "The Realtime channel ended: the join was refused, the server " +
                                "closed the channel, or the socket disconnected"
                        )
                    }
            }
        }
    }

    /**
     * Leaves [channel] within [leaveBound]: removes it, which leaves a joined channel, and sends
     * the leave supabase-kt does not for one still joining. A failure to leave is logged, never
     * thrown.
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
                        finishLeaving(channel, status)
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
     * socket still connected: sends the leave of a channel still joining, which the server may hold
     * once the join reaches it. The leave also cancels a rejoin supabase-kt scheduled for it, which
     * would add the channel back to the socket. A channel supabase-kt unsubscribed is left too, at
     * the cost of an error reply. The leave goes out after the removal, so its reply, an error for
     * a channel the server no longer holds, reaches no channel.
     */
    private suspend fun finishLeaving(channel: RealtimeChannel, status: RealtimeChannel.Status) {
        when (status) {
            RealtimeChannel.Status.SUBSCRIBING,
            RealtimeChannel.Status.UNSUBSCRIBED -> channel.unsubscribe()
            RealtimeChannel.Status.UNSUBSCRIBING,
            RealtimeChannel.Status.SUBSCRIBED -> Unit
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
