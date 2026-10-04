// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.exception.TokenExpiredException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.httpStatusOrNull
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.internal.isPermanentMutationFailure
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.RemoteTable
import io.github.nesfeder.supabase.offline.internal.postgrest.SendOutcome
import io.github.nesfeder.supabase.offline.internal.postgrest.SupabaseRemoteTable
import io.github.nesfeder.supabase.offline.internal.postgrest.decodeFilterOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.replayOnto
import io.github.nesfeder.supabase.offline.internal.postgrest.replaysFully
import io.github.nesfeder.supabase.offline.internal.postgrest.sendCapturingEngineFailure
import io.ktor.client.plugins.ResponseException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sends the mutation queue to Supabase: replay passes, and the immediate send of a mutation that is
 * at the head of its identity's queue when it is made. Exactly one sender at a time per mutation.
 */
internal class MutationQueueReplayer(
    /** The queue it sends, and records each send's outcome in. */
    private val mutationQueue: MutationQueue,
    /** Where a schema-cache miss's tick is read from. */
    private val freshness: CacheFreshness,
    private val supabase: SupabaseClient,
    private val engineLogger: EngineLogger = EngineLogger(null, EngineLogLevel.INFO),
    /** Whether the client may reach the network; nothing is sent while it is off. */
    private val networkAccess: NetworkAccess = NetworkAccess(),
    private val remoteTableFor: (schema: String, table: String) -> RemoteTable = { schema, table ->
        SupabaseRemoteTable(supabase.postgrest.from(schema, table), networkAccess = networkAccess)
    },
    /**
     * Waits, bounded, for a settled session; `false` means it did not settle and nothing is sent.
     */
    private val awaitSessionSettled: suspend () -> Boolean = { supabase.awaitSettledSession() },
    /** The identity a request sent now would go out as, or `null` while that cannot be told. */
    private val currentIdentity: suspend () -> QueueIdentity? = {
        supabase.currentReplayIdentity()
    },
    /** Whether the session is settled now, without waiting; see [awaitSessionSettled]. */
    private val isSessionSettledNow: () -> Boolean = { supabase.currentReplayIdentity() != null },
    private val primaryKeys: PrimaryKeys = DefaultPrimaryKeys,
    /** Told of each pass's report as it ends, with the table it covered (`null` for all). */
    private val passEnded: suspend (table: String?, report: SyncReport) -> Unit = { _, _ -> },
) {

    /**
     * Guards choosing a mutation to send and recording its outcome. Never held across a request.
     */
    private val sendLock = Mutex()

    /**
     * Ids of the mutations being sent right now, by a pass or at the head. Changed under
     * [sendLock]; observed by a caller waiting for a sender to let go.
     */
    private val inFlight = MutableStateFlow<Set<Long>>(emptySet())

    /** Bumped by [clearLocalData]; an outcome recorded after it moved is discarded. */
    private val clearGeneration = MutableStateFlow(0L)

    /** Passes in flight, so [clearLocalData] can cancel them. */
    private val activePasses = MutableStateFlow<Set<Job>>(emptySet())

    /** Whether a pass is running, as it changes. */
    val passRunning: Flow<Boolean> =
        activePasses.map { passes -> passes.isNotEmpty() }.distinctUntilChanged()

    /**
     * When this process last counted a schema-cache miss of each mutation, as a
     * [CacheFreshness.tickNow] tick; see [schemaCacheMissReachesBound]. Guarded by [sendLock].
     */
    private val schemaCacheMissesCountedAt = mutableMapOf<Long, Long>()

    /** Whether this replayer has warned of a session whose token does not name its user. */
    private val warnedOfTokenNotNamingUser = MutableStateFlow(false)

    init {
        // Before anything is sent: a mutation goes out with its owner's token, or not at all
        supabase.installReplayOwnerCheck()
    }

    /**
     * Replays the current identity's mutations across every table.
     *
     * @return The number the server accepted.
     */
    suspend fun syncAllTables(schema: String = supabase.postgrest.config.defaultSchema): Int =
        sync(table = null, schema = schema).syncedCount

    /**
     * Replays the current identity's mutations to [table]. A pass that stops on a failure other
     * than connectivity rethrows it; the mutation stays queued.
     *
     * @return The number the server accepted.
     */
    suspend fun syncTable(
        schema: String = supabase.postgrest.config.defaultSchema,
        table: String,
    ): Int {
        val report = sync(table = table, schema = schema)
        val stop = report.stop
        if (stop is SyncStop.TransientFailure && !stop.cause.isNetworkOrConnectivityFailure())
            throw stop.cause
        return report.syncedCount
    }

    /**
     * Runs one replay pass for the current identity: its mutations in insertion order, stopping at
     * the first failure that may pass, one attempt at the head. Returns promptly when offline.
     *
     * @param table The table to replay, or `null` for every table.
     * @return What the pass synced, rejected and left pending, and why it stopped early if it did.
     */
    suspend fun sync(
        table: String? = null,
        schema: String = supabase.postgrest.config.defaultSchema,
    ): SyncReport = runSync(table, schema).also { report -> passEnded(table, report) }

    /**
     * Runs passes as [sync] does until one ends for a reason other than another sender holding the
     * head of the queue, waiting between them for that sender to let go. So it returns once the
     * queue is empty or a pass stopped on something else, and its counts are its own passes'.
     *
     * @param table The table to replay, or `null` for every table.
     * @return What its passes synced and rejected, and what the last one left pending.
     */
    suspend fun syncWaitingOutOtherSenders(table: String? = null): SyncReport {
        var total: SyncReport? = null
        while (true) {
            val report = sync(table = table)
            total = total?.followedBy(report) ?: report
            if (report.stop != SyncStop.HeadInFlight) return total
            val holding = inFlight.value
            if (holding.isNotEmpty()) inFlight.first { current -> holding.none { it in current } }
        }
    }

    /** One pass, as [sync] describes it, without telling [passEnded]. */
    private suspend fun runSync(table: String?, schema: String): SyncReport {
        if (networkAccess.isDisabled) {
            val remaining = remainingFor(currentIdentity(), table)
            return SyncReport(0, emptyList(), remaining, SyncStop.NetworkDisabled)
        }
        // Offline, a session that is not settled now will not settle until the network is back
        if (networkAccess.isKnownUnavailable && !isSessionSettledNow()) {
            return SyncReport(0, emptyList(), remainingFor(null, table), SyncStop.SessionNotReady)
        }
        if (!awaitSessionSettled()) {
            engineLogger.info {
                if (table == null) "Session not settled; deferring mutation replay"
                else "Session not settled; deferring mutation replay for $table"
            }
            return SyncReport(0, emptyList(), remainingFor(null, table), SyncStop.SessionNotReady)
        }
        val generation = clearGeneration.value
        return coroutineScope {
            val pass = async { runPass(table, schema, generation) }
            activePasses.update { passes -> passes + pass }
            try {
                pass.await()
            } catch (cancellation: CancellationException) {
                // Rethrows when the caller itself was cancelled; otherwise only the pass was, by
                // clearLocalData, and there is nothing left to report on.
                currentCoroutineContext().ensureActive()
                SyncReport(0, emptyList(), 0, SyncStop.LocalDataCleared)
            } finally {
                activePasses.update { passes -> passes - pass }
            }
        }
    }

    /**
     * Wipes local data with [clear] once no pass can send anything more: passes in flight are
     * cancelled and [clear] runs under the send lock. An outcome arriving afterwards is discarded.
     */
    suspend fun clearLocalData(clear: suspend () -> Unit) {
        clearGeneration.update { generation -> generation + 1 }
        activePasses.value.forEach { pass ->
            pass.cancel(CancellationException("Local data cleared"))
        }
        sendLock.withLock {
            clear()
            schemaCacheMissesCountedAt.clear()
        }
    }

    /**
     * Runs [block] under the send lock, as [clearLocalData] runs its clear, but lets every pass and
     * queued write go on: for a drop of the cache that keeps the mutation queue.
     */
    suspend fun <T> holdingSends(block: suspend () -> T): T = sendLock.withLock { block() }

    /** What happened to a mutation offered for sending at the head of its queue. */
    sealed interface HeadSendOutcome {

        /**
         * It stays queued unsent: something is ahead of it, it is being sent, or the session moved
         * on.
         */
        data object NotAtHead : HeadSendOutcome

        /** The server accepted it and it left the queue. */
        data class Sent(val result: PostgrestResult) : HeadSendOutcome

        /**
         * It may not have run on the server — no connection or no answer, a `503`/`520`, a table or
         * column PostgREST's schema cache does not know yet, or a session token that could not be
         * refreshed — and stays queued.
         */
        data class StaysQueued(val cause: Exception) : HeadSendOutcome

        /** The server refused it, or answered with another failure; it left the queue. */
        data class Refused(val cause: Exception) : HeadSendOutcome

        /** Local data was cleared while it was on the wire; nothing about it was recorded. */
        data class Cleared(val result: PostgrestResult?, val cause: Exception?) : HeadSendOutcome
    }

    /**
     * Sends queued mutation [mutationId] now if it is at the head of [identity]'s queue, nobody
     * else is sending it, and the session is still [identity]. On success [onAccepted] runs before
     * the mutation leaves the queue; a failure of [onAccepted] is logged, since the server has the
     * write. A caller cancelled while the request is on the wire leaves the mutation queued.
     */
    suspend fun sendAtHead(
        mutationId: Long,
        identity: QueueIdentity,
        send: suspend () -> PostgrestResult,
        onAccepted: suspend (PostgrestResult) -> Unit,
    ): HeadSendOutcome {
        // Nothing leaves the device while network access is off: it waits, queued
        if (networkAccess.isDisabled)
            return HeadSendOutcome.StaysQueued(
                NetworkDisabledException(NetworkAccess.NETWORK_DISABLED_MESSAGE)
            )
        val generation = clearGeneration.value
        val claimed = sendLock.withLock {
            val available =
                clearGeneration.value == generation &&
                    mutationId !in inFlight.value &&
                    currentIdentity() == identity &&
                    mutationQueue.isAtHead(mutationId, identity.storedValue)
            if (available) inFlight.update { ids -> ids + mutationId }
            available
        }
        if (!claimed) return HeadSendOutcome.NotAtHead
        try {
            val newlyUnconfirmed = mutationQueue.markSendUnconfirmed(mutationId)
            val owner = ReplayOwner(identity)
            val result =
                when (val sent = sendCapturingEngineFailure { withContext(owner) { send() } }) {
                    is SendOutcome.Succeeded -> sent.value
                    is SendOutcome.Failed ->
                        return sendLock.withLock {
                            if (owner.stoppedForAnotherSession) {
                                stoppedForAnotherSession(mutationId, newlyUnconfirmed, identity)
                                HeadSendOutcome.NotAtHead
                            } else {
                                headFailureOutcome(mutationId, sent, newlyUnconfirmed, generation)
                            }
                        }
                }
            if (clearGeneration.value != generation)
                return HeadSendOutcome.Cleared(result = result, cause = null)
            try {
                onAccepted(result)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                engineLogger.warning {
                    "Patching the cache after an accepted write failed: ${failure::class.simpleName}"
                }
            } finally {
                // The server has the write: it leaves the queue whatever happened here, or a pass
                // would send it again.
                withContext(NonCancellable) { completeAcceptedMutation(mutationId) }
            }
            return HeadSendOutcome.Sent(result)
        } finally {
            withContext(NonCancellable) {
                sendLock.withLock { inFlight.update { ids -> ids - mutationId } }
            }
        }
    }

    /**
     * Keeps a mutation whose request the owner check stopped queued for its owner's next session.
     * It never left the device, so a send recordOutcome this attempt made is cleared. When the
     * session is still [owner]'s, its token names another user or none, which holds the queue: said
     * once.
     */
    private suspend fun stoppedForAnotherSession(
        mutationId: Long,
        newlyUnconfirmed: Boolean,
        owner: QueueIdentity,
    ) {
        if (newlyUnconfirmed) mutationQueue.clearUnconfirmedSend(mutationId)
        if (currentIdentity() == owner) {
            if (warnedOfTokenNotNamingUser.compareAndSet(expect = false, update = true)) {
                engineLogger.warning {
                    "The session's access token does not name the session's user; its queued " +
                        "mutations are held until the two agree"
                }
            }
            return
        }
        engineLogger.info {
            "The session changed before a queued mutation was sent; it stays queued for its owner"
        }
    }

    /** Where a failed head send leaves its mutation. Caller holds [sendLock]. */
    private suspend fun headFailureOutcome(
        mutationId: Long,
        failure: SendOutcome.Failed,
        newlyUnconfirmed: Boolean,
        generation: Long,
    ): HeadSendOutcome {
        val cause = failure.thrown
        return when {
            clearGeneration.value != generation ->
                HeadSendOutcome.Cleared(result = null, cause = cause)
            // The session's token expired and could not be refreshed: nothing left the device
            cause is TokenExpiredException -> {
                if (newlyUnconfirmed) mutationQueue.clearUnconfirmedSend(mutationId)
                HeadSendOutcome.StaysQueued(cause)
            }
            postgrestCodeOf(cause) in SCHEMA_CACHE_MISS_CODES &&
                !schemaCacheMissReachesBound(mutationId) -> {
                if (newlyUnconfirmed) mutationQueue.clearUnconfirmedSend(mutationId)
                HeadSendOutcome.StaysQueued(cause)
            }
            cause.isNetworkOrConnectivityFailure() ||
                cause.httpStatusOrNull() in STAYS_QUEUED_STATUSES -> {
                if (newlyUnconfirmed && failure.provesWriteNotApplied())
                    mutationQueue.clearUnconfirmedSend(mutationId)
                HeadSendOutcome.StaysQueued(cause)
            }
            else -> {
                mutationQueue.abandonSentMutation(mutationId)
                HeadSendOutcome.Refused(cause)
            }
        }
    }

    /**
     * Whether this failed send provably did not apply its write. It did not when no attempt reached
     * the server, when the session's token could not be refreshed (thrown before the request is
     * built), or when PostgREST itself answered that it could not reach the database.
     */
    private fun SendOutcome.Failed.provesWriteNotApplied(): Boolean =
        neverReachedServer ||
            thrown is TokenExpiredException ||
            postgrestCodeOf(thrown).let { code ->
                code in POSTGREST_DID_NOT_RUN_CODES || code in SCHEMA_CACHE_MISS_CODES
            }

    /**
     * Counts a schema-cache miss of mutation [mutationId], unless this process counted one less
     * than [SCHEMA_CACHE_MISS_SPACING] ago, and says whether its misses reached
     * [SCHEMA_CACHE_MISS_BOUND]. Caller holds [sendLock].
     */
    private suspend fun schemaCacheMissReachesBound(mutationId: Long): Boolean {
        val now = freshness.tickNow()
        val last = schemaCacheMissesCountedAt[mutationId]
        if (last != null && now - last < SCHEMA_CACHE_MISS_SPACING.inWholeNanoseconds) return false
        schemaCacheMissesCountedAt[mutationId] = now
        val misses = mutationQueue.recordSchemaCacheMiss(mutationId)
        engineLogger.warning {
            "PostgREST's schema cache does not know a table or column yet; " +
                "miss $misses of $SCHEMA_CACHE_MISS_BOUND before the write is rejected"
        }
        return misses >= SCHEMA_CACHE_MISS_BOUND
    }

    /** The PostgREST error code [failure] or a cause carries, or `null`. */
    private fun postgrestCodeOf(failure: Throwable): String? {
        var current: Throwable? = failure
        while (current != null) {
            if (current is PostgrestRestException) return current.code
            current = current.cause
        }
        return null
    }

    /** Removes an accepted mutation, logging rather than failing a write the server applied. */
    private suspend fun completeAcceptedMutation(mutationId: Long) {
        try {
            mutationQueue.completeSentMutation(mutationId)
        } catch (failure: Exception) {
            engineLogger.error {
                "Could not remove an accepted mutation from the queue; it may be sent again (${failure::class.simpleName})"
            }
        }
    }

    /**
     * Discards the pending mutation [id] of [identity] unsent. A mutation being sent cannot be.
     *
     * @param identity The asking identity, or `null` to skip the ownership check.
     * @return `true` when it was pending, not in flight, and [identity]'s.
     */
    suspend fun discardPendingMutation(id: Long, identity: QueueIdentity?): Boolean =
        sendLock.withLock {
            if (id in inFlight.value) false
            else mutationQueue.discardPendingMutation(id, identity?.storedValue)
        }

    /**
     * [discardPendingMutation], waiting first for a sender that has [id] on the wire to let go:
     * once it returns, the mutation is not pending, unless it is another identity's.
     *
     * @return `true` when it was discarded; `false` when it was not pending by then, or not
     *   [identity]'s.
     */
    suspend fun discardPendingMutationOnceUnsent(id: Long, identity: QueueIdentity?): Boolean {
        while (true) {
            val discarded: Boolean? = sendLock.withLock {
                if (id in inFlight.value) null
                else mutationQueue.discardPendingMutation(id, identity?.storedValue)
            }
            if (discarded != null) return discarded
            inFlight.first { current -> id !in current }
        }
    }

    private suspend fun remainingFor(identity: QueueIdentity?, table: String?): Int {
        if (identity == null)
            return mutationQueue.allPendingMutations().count { row ->
                table == null || row.table == table
            }
        return mutationQueue.pendingMutationsOf(identity.storedValue, table).size
    }

    /** The next step of a pass, chosen under [sendLock]. */
    private sealed interface Claim {
        data class Send(val mutation: MutationRow) : Claim

        data object Skip : Claim

        data class Stop(val stop: SyncStop?) : Claim
    }

    /**
     * Claims [candidate], the next of [identity]'s mutations as the pass loaded them, or says why
     * the pass ends. Nothing earlier may still be in flight, so order holds across concurrent
     * senders. Caller holds [sendLock].
     */
    private suspend fun claim(
        candidate: MutationRow,
        identity: QueueIdentity,
        generation: Long,
    ): Claim {
        if (clearGeneration.value != generation) return Claim.Stop(SyncStop.LocalDataCleared)
        if (networkAccess.isDisabled) return Claim.Stop(SyncStop.NetworkDisabled)
        val now = currentIdentity() ?: return Claim.Stop(SyncStop.IdentityUnknown)
        if (now != identity) return Claim.Stop(SyncStop.SessionChanged)
        if (inFlight.value.any { id -> id <= candidate.id })
            return Claim.Stop(SyncStop.HeadInFlight)
        if (!mutationQueue.isPending(candidate.id)) return Claim.Skip
        inFlight.update { ids -> ids + candidate.id }
        return Claim.Send(candidate)
    }

    /**
     * One pass. Reads the identity's queue once, then re-checks each mutation as it claims it.
     * Cancelled, it stops between mutations: a request already sent finishes and its answer is
     * recorded, since a write the server applied but the queue kept would be sent again.
     */
    private suspend fun runPass(table: String?, schema: String, generation: Long): SyncReport {
        val pass = currentCoroutineContext().job
        val identity = currentIdentity()
        if (identity == null) {
            engineLogger.warning { "The session's identity cannot be told; not replaying" }
            return SyncReport(0, emptyList(), remainingFor(null, table), SyncStop.IdentityUnknown)
        }
        sendLock.withLock {
            if (clearGeneration.value != generation)
                return SyncReport(0, emptyList(), 0, SyncStop.LocalDataCleared)
            mutationQueue.retireUnneededSyncedMutations()
            mutationQueue.adoptOwnerlessMutations(identity.storedValue)
        }
        val queue = mutationQueue.pendingMutationsOf(identity.storedValue, table)
        if (queue.isEmpty()) return SyncReport(0, emptyList(), 0, null)
        engineLogger.info {
            if (table == null) "Syncing ${queue.size} pending mutations"
            else "Syncing ${queue.size} pending mutations for $table"
        }

        val tableApis = mutableMapOf<String, RemoteTable>()
        val syncedTables = mutableSetOf<String>()
        val rejected = mutableListOf<StoredRejection>()
        var syncedCount = 0
        var alreadyOnServerCount = 0
        var stop: SyncStop? = null

        for (candidate in queue) {
            pass.ensureActive()
            val mutation =
                when (val claim = sendLock.withLock { claim(candidate, identity, generation) }) {
                    is Claim.Stop -> {
                        stop = claim.stop
                        break
                    }
                    Claim.Skip -> continue
                    is Claim.Send -> claim.mutation
                }
            // The in-flight mark is released with the outcome recorded, so no other sender can
            // claim
            // the mutation in between; on any other way out, in the finally.
            var released = false
            val (outcome, recorded) =
                try {
                    // Runs to its outcome once sent; the owner check stops the request if the pass
                    // is cancelled first
                    withContext(NonCancellable) {
                        val outcome =
                            try {
                                val tableApi =
                                    tableApis.getOrPut(mutation.table) {
                                        remoteTableFor(schema, mutation.table)
                                    }
                                replay(mutation, tableApi, identity, pass)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (localFailure: Exception) {
                                // Request failures are outcomes already; this is the store failing
                                // around one.
                                engineLogger.error {
                                    "Replay of ${mutation.operation} on ${mutation.table} stopped by a local failure " +
                                        "(${localFailure::class.simpleName}); keeping it for a later sync"
                                }
                                ReplayOutcome.Stopped(localFailure)
                            }
                        sendLock.withLock {
                            inFlight.update { ids -> ids - mutation.id }
                            released = true
                            outcome to
                                if (clearGeneration.value != generation) SyncStop.LocalDataCleared
                                else recordOutcome(mutation, outcome, rejected, syncedTables)
                        }
                    }
                } finally {
                    if (!released)
                        withContext(NonCancellable) {
                            sendLock.withLock { inFlight.update { ids -> ids - mutation.id } }
                        }
                }
            if (outcome is ReplayOutcome.Synced && recorded == null) {
                syncedCount++
                if (outcome.rowsWereOnServer) alreadyOnServerCount++
            }
            if (recorded != null) {
                stop = recorded
                break
            }
        }

        // A table nothing caches or observes has no use for its synced mutations; others keep them
        // until every cached query has refreshed past them.
        syncedTables.forEach { syncedTable ->
            mutationQueue.retireCoveredSyncedMutations(syncedTable)
        }

        engineLogger.info {
            if (table == null) "Sync complete: $syncedCount mutations synced"
            else "Sync complete: $syncedCount mutations synced for $table"
        }
        return SyncReport(
            syncedCount = syncedCount,
            rejected = rejected.map { entry -> entry.toRejectedMutation(EngineJson, primaryKeys) },
            remainingCount = remainingFor(identity, table),
            stop = stop,
            alreadyOnServerCount = alreadyOnServerCount,
        )
    }

    /**
     * Records the [outcome] of replaying [mutation]. Caller holds [sendLock].
     *
     * @return Why the pass stops here, or `null` to go on.
     */
    private suspend fun recordOutcome(
        mutation: MutationRow,
        outcome: ReplayOutcome,
        rejected: MutableList<StoredRejection>,
        syncedTables: MutableSet<String>,
    ): SyncStop? =
        when (outcome) {
            is ReplayOutcome.Synced -> {
                schemaCacheMissesCountedAt -= mutation.id
                mutationQueue.recordReplayed(id = mutation.id, table = mutation.table)
                syncedTables += mutation.table
                engineLogger.debug { "Synced ${mutation.operation} mutation for ${mutation.table}" }
                null
            }

            is ReplayOutcome.Rejected ->
                try {
                    rejected +=
                        mutationQueue.rejectMutation(mutation, outcome.message, outcome.serverError)
                    null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (unrecorded: Exception) {
                    // The mutation stays queued rather than vanish, and the pass stops here.
                    engineLogger.error {
                        "Could not record the rejection of ${mutation.operation} on ${mutation.table}; " +
                            "keeping it queued (${unrecorded::class.simpleName})"
                    }
                    SyncStop.TransientFailure(unrecorded)
                }

            is ReplayOutcome.SchemaCacheMiss ->
                if (schemaCacheMissReachesBound(mutation.id)) {
                    schemaCacheMissesCountedAt -= mutation.id
                    val serverError = serverErrorOf(outcome.cause)
                    recordOutcome(
                        mutation,
                        ReplayOutcome.Rejected(
                            message = serverError?.message ?: SCHEMA_CACHE_MISS_REASON,
                            serverError = serverError,
                        ),
                        rejected,
                        syncedTables,
                    )
                } else {
                    SyncStop.TransientFailure(outcome.cause)
                }

            is ReplayOutcome.Held -> SyncStop.HeldBehindEarlierMutations(mutation.id, outcome.cause)
            is ReplayOutcome.Stopped -> SyncStop.TransientFailure(outcome.cause)
            ReplayOutcome.SessionChanged -> SyncStop.SessionChanged
        }

    /** How one mutation's replay ended. */
    private sealed interface ReplayOutcome {
        /**
         * The server accepted it.
         *
         * @property rowsWereOnServer whether it found rows of it already on the server: an earlier
         *   send that seemed lost had arrived.
         */
        data class Synced(val rowsWereOnServer: Boolean) : ReplayOutcome

        /** The session stopped being the owner's before the request left; nothing was sent. */
        data object SessionChanged : ReplayOutcome

        data class Rejected(val message: String, val serverError: StoredServerError?) :
            ReplayOutcome

        data class Held(val cause: Throwable) : ReplayOutcome

        /** PostgREST's schema cache did not know the table or a column; the write did not run. */
        data class SchemaCacheMiss(val cause: Exception) : ReplayOutcome

        data class Stopped(val cause: Throwable) : ReplayOutcome
    }

    /**
     * Sends [mutation] as [identity], the pass [pass] running it: stopped before it leaves the
     * device when the session is no longer [identity]'s or [pass] was cancelled.
     */
    private suspend fun replay(
        mutation: MutationRow,
        tableApi: RemoteTable,
        identity: QueueIdentity,
        pass: Job,
    ): ReplayOutcome {
        // Decided before this attempt is recorded: only an earlier attempt can have reached the
        // server.
        val earlierSendUnconfirmed = mutationQueue.hasUnconfirmedSend(mutation.id)
        return when (val prepared = requestFor(mutation, earlierSendUnconfirmed)) {
            is Prepared.Refused -> prepared.outcome
            is Prepared.Ready -> {
                val newlyUnconfirmed = mutationQueue.markSendUnconfirmed(mutation.id)
                val owner = ReplayOwner(identity, sender = pass)
                val sent = sendCapturingEngineFailure {
                    withContext(owner) { prepared.request(tableApi) }
                }
                when (sent) {
                    is SendOutcome.Succeeded -> ReplayOutcome.Synced(rowsWereOnServer = sent.value)
                    is SendOutcome.Failed ->
                        if (owner.stoppedWithItsSender) {
                            // It never left the device: the pass ends here, cancelled
                            if (newlyUnconfirmed) mutationQueue.clearUnconfirmedSend(mutation.id)
                            throw CancellationException("The replay pass was stopped")
                        } else if (owner.stoppedForAnotherSession) {
                            stoppedForAnotherSession(mutation.id, newlyUnconfirmed, identity)
                            ReplayOutcome.SessionChanged
                        } else {
                            if (newlyUnconfirmed && sent.provesWriteNotApplied())
                                mutationQueue.clearUnconfirmedSend(mutation.id)
                            classify(mutation, sent.thrown, identity)
                        }
                }
            }
        }
    }

    private suspend fun classify(
        mutation: MutationRow,
        failure: Exception,
        identity: QueueIdentity,
    ): ReplayOutcome =
        when {
            failure.isNetworkOrConnectivityFailure() -> {
                engineLogger.warning { "Network unavailable, pausing sync for ${mutation.table}" }
                ReplayOutcome.Stopped(failure)
            }

            postgrestCodeOf(failure) in SCHEMA_CACHE_MISS_CODES ->
                ReplayOutcome.SchemaCacheMiss(failure)

            failure.isPermanentMutationFailure() -> {
                val status = failure.httpStatusOrNull()
                val mayDependOnEarlierWrite = status == HTTP_FORBIDDEN || status == HTTP_CONFLICT
                if (
                    mayDependOnEarlierWrite &&
                        mutationQueue.hasEarlierPendingMutation(mutation.id, identity.storedValue)
                ) {
                    engineLogger.warning {
                        "Holding ${mutation.operation} on ${mutation.table} ($status) behind an earlier pending mutation"
                    }
                    ReplayOutcome.Held(failure)
                } else {
                    val serverError = serverErrorOf(failure)
                    engineLogger.warning {
                        "Permanent failure for ${mutation.operation} on ${mutation.table} ($status); moved to the rejected list"
                    }
                    ReplayOutcome.Rejected(
                        message = serverError?.message ?: "The server refused the write",
                        serverError = serverError,
                    )
                }
            }

            else -> {
                val status = failure.httpStatusOrNull()
                engineLogger.warning {
                    "Replay of ${mutation.operation} on ${mutation.table} failed " +
                        "(${status ?: failure::class.simpleName}); keeping it for a later sync"
                }
                ReplayOutcome.Stopped(failure)
            }
        }

    /** A mutation ready to send, or refused before any request. */
    private sealed interface Prepared {
        /** Ready; [request] sends it and says whether rows of it were already on the server. */
        class Ready(val request: suspend (RemoteTable) -> Boolean) : Prepared

        class Refused(val outcome: ReplayOutcome.Rejected) : Prepared
    }

    private fun refuse(mutation: MutationRow, reason: String, message: String): Prepared.Refused {
        engineLogger.warning {
            "Queued ${mutation.operation} on ${mutation.table} $reason; moving it to the rejected list"
        }
        return Prepared.Refused(ReplayOutcome.Rejected(message = message, serverError = null))
    }

    /**
     * The request that replays [mutation], or why it cannot be sent faithfully.
     *
     * @param earlierSendUnconfirmed Whether an earlier send of it may have reached the server.
     */
    private fun requestFor(mutation: MutationRow, earlierSendUnconfirmed: Boolean): Prepared {
        val json = EngineJson
        val filter = decodeFilterOrNull(mutation.filterJson, json)
        // A stored filter that no longer decodes is not a missing one: sending without it would
        // reach rows the write never targeted
        if (mutation.filterJson != null && filter == null)
            return refuse(
                mutation,
                "has a filter that can no longer be read",
                "The queued filter can no longer be read",
            )
        return when (mutation.operation) {
            MutationOperation.INSERT -> {
                val body =
                    parseJsonArrayOrNull(json, mutation.bodyJson)
                        ?: return refuse(
                            mutation,
                            BODY_NO_LONGER_PARSES,
                            "The queued body no longer parses",
                        )
                Prepared.Ready { tableApi ->
                    sendInsert(
                        tableApi,
                        mutation.table,
                        body,
                        mutation.defaultToNull,
                        filter,
                        earlierSendUnconfirmed,
                    )
                }
            }

            MutationOperation.UPSERT -> {
                val body =
                    parseJsonArrayOrNull(json, mutation.bodyJson)
                        ?: return refuse(
                            mutation,
                            BODY_NO_LONGER_PARSES,
                            "The queued body no longer parses",
                        )
                Prepared.Ready { tableApi ->
                    sendUpsert(
                        tableApi = tableApi,
                        body = body,
                        onConflict = mutation.onConflict,
                        ignoreDuplicates = mutation.ignoreDuplicates,
                        defaultToNull = mutation.defaultToNull,
                        filter = filter,
                    )
                    false
                }
            }

            MutationOperation.UPDATE -> {
                val body =
                    parseJsonElementOrNull(json, mutation.bodyJson)
                        ?: return refuse(
                            mutation,
                            BODY_NO_LONGER_PARSES,
                            "The queued body no longer parses",
                        )
                val replayableFilter =
                    replayableFilterOrNull(filter)
                        ?: return refuseUnreplayableFilter(mutation, filter)
                Prepared.Ready { tableApi ->
                    sendUpdate(tableApi, body, replayableFilter)
                    false
                }
            }

            MutationOperation.DELETE -> {
                val replayableFilter =
                    replayableFilterOrNull(filter)
                        ?: return refuseUnreplayableFilter(mutation, filter)
                Prepared.Ready { tableApi ->
                    sendDelete(tableApi, replayableFilter)
                    false
                }
            }
        }
    }

    /**
     * [filter] when replaying it reaches exactly the rows it captured. A missing or lossy filter
     * would reach more rows than the caller targeted, so it is never sent.
     */
    private fun replayableFilterOrNull(filter: FilterDescriptor?): FilterDescriptor? =
        filter?.takeIf { descriptor ->
            replaysFully(descriptor)
        }

    private fun refuseUnreplayableFilter(
        mutation: MutationRow,
        filter: FilterDescriptor?,
    ): Prepared.Refused =
        if (filter == null) {
            refuse(
                mutation,
                "has no filter",
                "An ${mutation.operation} without a filter is never replayed",
            )
        } else {
            refuse(
                mutation,
                "has a filter that no longer replays in full",
                "The queued filter no longer replays in full",
            )
        }

    private fun parseJsonArrayOrNull(json: Json, raw: String): JsonArray? =
        try {
            json.parseToJsonElement(raw) as? JsonArray
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun parseJsonElementOrNull(json: Json, raw: String): JsonElement? =
        try {
            json.parseToJsonElement(raw)
        } catch (_: IllegalArgumentException) {
            null
        }

    /* ───────────────────────────── Remote calls ───────────────────────────── */

    /**
     * Replays an INSERT. One that may already be on the server, and whose rows all carry the
     * table's primary key, goes out as an upsert on that key that ignores duplicates and counts the
     * rows it wrote; every other INSERT goes out as sent, so a duplicate key is refused by the
     * server. An upsert with no matching unique constraint (`42P10`) is sent again as the plain
     * INSERT.
     *
     * @return whether the server already held rows of it, taken as an earlier send's.
     */
    private suspend fun sendInsert(
        tableApi: RemoteTable,
        table: String,
        body: JsonArray,
        defaultToNull: Boolean?,
        filter: FilterDescriptor?,
        earlierSendUnconfirmed: Boolean,
    ): Boolean {
        val keyColumns = primaryKeys.of(table)
        if (!earlierSendUnconfirmed || !everyRowCarriesPrimaryKey(body, keyColumns)) {
            insertAsSent(tableApi, body, defaultToNull, filter)
            return false
        }
        val result =
            try {
                tableApi.upsert(body) {
                    this.onConflict = keyColumns.joinToString(",")
                    this.ignoreDuplicates = true
                    this.defaultToNull = defaultToNull ?: true
                    count(Count.EXACT)
                    if (filter != null) replayOnto(this, filter)
                }
            } catch (failure: PostgrestRestException) {
                if (failure.code != NO_MATCHING_CONFLICT_TARGET) throw failure
                engineLogger.warning {
                    "Replayed INSERT on $table has no unique key to match; sending it as a plain INSERT"
                }
                insertAsSent(tableApi, body, defaultToNull, filter)
                return false
            }
        val written = result.countOrNull()
        if (written != null && written < body.size) {
            engineLogger.info {
                "Replayed INSERT on $table: ${body.size - written} of ${body.size} rows were already on the server, taken as its earlier send"
            }
            return true
        }
        return false
    }

    private suspend fun insertAsSent(
        tableApi: RemoteTable,
        body: JsonArray,
        defaultToNull: Boolean?,
        filter: FilterDescriptor?,
    ) {
        tableApi.insert(body) {
            this.defaultToNull = defaultToNull ?: true
            if (filter != null) replayOnto(this, filter)
        }
    }

    private suspend fun sendUpsert(
        tableApi: RemoteTable,
        body: JsonArray,
        onConflict: String?,
        ignoreDuplicates: Boolean?,
        defaultToNull: Boolean?,
        filter: FilterDescriptor? = null,
    ) {
        tableApi.upsert(body) {
            this.onConflict = onConflict
            this.ignoreDuplicates = ignoreDuplicates ?: false
            this.defaultToNull = defaultToNull ?: true
            if (filter != null) replayOnto(this, filter)
        }
    }

    private suspend fun sendUpdate(
        tableApi: RemoteTable,
        body: JsonElement,
        filter: FilterDescriptor,
    ) {
        tableApi.update(body) { replayOnto(this, filter) }
    }

    private suspend fun sendDelete(
        tableApi: RemoteTable,
        filter: FilterDescriptor,
    ) {
        tableApi.delete { replayOnto(this, filter) }
    }

    /**
     * The server's refusal as data: PostgREST's fields, or the bare status of another HTTP failure.
     */
    private fun serverErrorOf(failure: Throwable): StoredServerError? {
        var current: Throwable? = failure
        while (current != null) {
            when (current) {
                is PostgrestRestException ->
                    return StoredServerError(
                        status = current.statusCode,
                        code = current.code,
                        message = current.error,
                        details =
                            current.details?.let { details ->
                                (details as? JsonPrimitive)?.takeIf { it.isString }?.content
                                    ?: details.toString()
                            },
                        hint = current.hint,
                    )

                is ResponseException ->
                    return StoredServerError(
                        status = current.response.status.value,
                        message = current.response.status.description,
                    )
            }
            current = current.cause
        }
        return null
    }

    private companion object {
        const val HTTP_FORBIDDEN = 403
        const val HTTP_CONFLICT = 409
        const val HTTP_SERVICE_UNAVAILABLE = 503

        /**
         * Statuses after which a head send stays queued: PostgREST, or something in front of it,
         * could not complete the request, and the write may not have run.
         */
        val STAYS_QUEUED_STATUSES = setOf(HTTP_SERVICE_UNAVAILABLE, 520)

        /**
         * PostgREST's `503` codes for a request it answered without reaching the database
         * (PGRST000) or before its schema cache loaded (PGRST002). Not PGRST001: the database
         * connection broke during the request, which may have committed.
         */
        val POSTGREST_DID_NOT_RUN_CODES = setOf("PGRST000", "PGRST002")

        /**
         * PostgREST's codes for a table (PGRST205) or column (PGRST204) its schema cache does not
         * know: the statement never ran, and the cache may not have caught up with a migration.
         */
        val SCHEMA_CACHE_MISS_CODES = setOf("PGRST204", "PGRST205")

        /**
         * Schema-cache misses after which a write is rejected: at [SCHEMA_CACHE_MISS_SPACING]
         * apart, minutes of misses, where a migration's cache reload takes seconds.
         */
        const val SCHEMA_CACHE_MISS_BOUND = 10

        /** How long after a counted schema-cache miss the next one counts, in one process. */
        val SCHEMA_CACHE_MISS_SPACING = 30.seconds

        /** Why a write is rejected at the bound, when the server's answer carries no message. */
        const val SCHEMA_CACHE_MISS_REASON =
            "The server's schema does not have the table or a column this write needs"

        /** Postgres: `ON CONFLICT` names columns with no unique or exclusion constraint. */
        const val NO_MATCHING_CONFLICT_TARGET = "42P10"
        const val BODY_NO_LONGER_PARSES = "has a body that no longer parses"
    }
}
