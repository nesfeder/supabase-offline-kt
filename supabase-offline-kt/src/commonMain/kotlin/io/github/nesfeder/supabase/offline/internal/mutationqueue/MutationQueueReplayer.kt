// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.exception.TokenExpiredException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.cache.CachePatcher
import io.github.nesfeder.supabase.offline.internal.cache.returnedRowsOf
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.knownOperation
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
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.replayOnto
import io.github.nesfeder.supabase.offline.internal.postgrest.replaysFully
import io.github.nesfeder.supabase.offline.internal.postgrest.sendCapturingEngineFailure
import io.ktor.client.plugins.ResponseException
import kotlin.time.Duration
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

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
    /**
     * Patches the rows a guarded replay finds on the server into the cache; `null` patches none.
     */
    private val cachePatcher: CachePatcher? = null,
    /** Settles a guarded write whose row moved on the server, or is gone. */
    private val conflictPolicy: ConflictPolicy = DefaultConflictPolicy,
    /** How long [conflictPolicy] may take before its answer reads as Hold; tests shorten it. */
    private val conflictPolicyTimeout: Duration = CONFLICT_POLICY_TIMEOUT,
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
        val progress = PassProgress()
        return coroutineScope {
            val pass = async { runPass(table, schema, generation, progress) }
            activePasses.update { passes -> passes + pass }
            try {
                pass.await()
            } catch (cancellation: CancellationException) {
                // Rethrows when the caller itself was canceled; otherwise only the pass was, by
                // clearLocalData, which left nothing queued: report what it sent before
                currentCoroutineContext().ensureActive()
                progress.report(remainingCount = 0, stop = SyncStop.LocalDataCleared)
            } finally {
                activePasses.update { passes -> passes - pass }
            }
        }
    }

    /**
     * Wipes local data with [clear] once no pass can send anything more: passes in flight are
     * canceled and [clear] runs under the send lock. An outcome arriving afterward is discarded.
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
     * write. A caller canceled while the request is on the wire leaves the mutation queued.
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
     * senders; and a pass for one [table] stops at a write that has an earlier one of [identity]
     * still queued, in another table, so it never overtakes it. Caller holds [sendLock].
     */
    private suspend fun claim(
        candidate: MutationRow,
        table: String?,
        identity: QueueIdentity,
        generation: Long,
    ): Claim {
        if (clearGeneration.value != generation) return Claim.Stop(SyncStop.LocalDataCleared)
        if (networkAccess.isDisabled) return Claim.Stop(SyncStop.NetworkDisabled)
        val now = currentIdentity() ?: return Claim.Stop(SyncStop.IdentityUnknown)
        if (now != identity) return Claim.Stop(SyncStop.SessionChanged)
        if (inFlight.value.any { id -> id <= candidate.id })
            return Claim.Stop(SyncStop.HeadInFlight)
        // Read again: a write sent before it may have moved its base
        val current = mutationQueue.pendingMutation(candidate.id) ?: return Claim.Skip
        if (
            table != null &&
                mutationQueue.hasEarlierPendingMutation(candidate.id, identity.storedValue)
        )
            return Claim.Stop(SyncStop.HeldBehindOtherTables(candidate.id))
        inFlight.update { ids -> ids + candidate.id }
        return Claim.Send(current)
    }

    /**
     * What a pass sent and recorded so far. Kept outside the pass, so one a clear cancels still
     * reports the writes the server accepted or refused before.
     */
    private inner class PassProgress {
        var syncedCount = 0
        var alreadyOnServerCount = 0
        var conflictsResolvedCount = 0
        var conflictsKeptServerCount = 0
        val rejected = mutableListOf<StoredRejection>()
        val conflicted = mutableListOf<StoredConflict>()

        /** The report of a pass that ended with [remainingCount] queued, for [stop]. */
        fun report(remainingCount: Int, stop: SyncStop?): SyncReport =
            SyncReport(
                syncedCount = syncedCount,
                rejected =
                    rejected.map { entry -> entry.toRejectedMutation(EngineJson, primaryKeys) },
                remainingCount = remainingCount,
                stop = stop,
                alreadyOnServerCount = alreadyOnServerCount,
                conflictsResolvedCount = conflictsResolvedCount,
                conflictsKeptServerCount = conflictsKeptServerCount,
                conflicted = conflicted.toList(),
            )
    }

    /**
     * One pass, counting into [progress]. Reads the identity's queue once, then re-checks each
     * mutation as it claims it. Canceled, it stops between mutations: a request already sent
     * finishes and its answer is recorded, since a write the server applied but the queue kept
     * would be sent again.
     */
    private suspend fun runPass(
        table: String?,
        schema: String,
        generation: Long,
        progress: PassProgress,
    ): SyncReport {
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
        var stop: SyncStop? = null

        for (candidate in queue) {
            pass.ensureActive()
            val mutation =
                when (
                    val claim = sendLock.withLock { claim(candidate, table, identity, generation) }
                ) {
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
                    // is canceled first
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
                                else
                                    recordOutcome(
                                        mutation,
                                        outcome,
                                        progress.rejected,
                                        syncedTables,
                                        progress.conflicted,
                                    )
                        }
                    }
                } finally {
                    if (!released)
                        withContext(NonCancellable) {
                            sendLock.withLock { inFlight.update { ids -> ids - mutation.id } }
                        }
                }
            if (outcome is ReplayOutcome.Synced && recorded == null) {
                progress.syncedCount++
                if (outcome.rowsWereOnServer) progress.alreadyOnServerCount++
                if (outcome.conflictResolved) progress.conflictsResolvedCount++
            }
            if (outcome == ReplayOutcome.KeptServer && recorded == null)
                progress.conflictsKeptServerCount++
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
            if (table == null) "Sync complete: ${progress.syncedCount} mutations synced"
            else "Sync complete: ${progress.syncedCount} mutations synced for $table"
        }
        return progress.report(remainingCount = remainingFor(identity, table), stop = stop)
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
        conflicted: MutableList<StoredConflict>,
    ): SyncStop? =
        when (outcome) {
            is ReplayOutcome.Synced -> {
                schemaCacheMissesCountedAt -= mutation.id
                mutationQueue.recordReplayed(
                    id = mutation.id,
                    table = mutation.table,
                    returnedRows = outcome.returnedRows,
                )
                syncedTables += mutation.table
                engineLogger.debug { "Synced ${mutation.operation} mutation for ${mutation.table}" }
                null
            }

            ReplayOutcome.KeptServer -> {
                schemaCacheMissesCountedAt -= mutation.id
                mutationQueue.dropKeptServerMutation(mutation.id)
                engineLogger.info {
                    "Dropped a queued ${mutation.operation} on ${mutation.table}: the server's row stands"
                }
                null
            }

            is ReplayOutcome.Conflicted ->
                try {
                    schemaCacheMissesCountedAt -= mutation.id
                    conflicted += mutationQueue.parkConflictedMutation(mutation, outcome.parked)
                    null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (unrecorded: Exception) {
                    // The mutation stays queued rather than vanish, and the pass stops here.
                    engineLogger.error {
                        "Could not park the conflicted ${mutation.operation} on ${mutation.table}; " +
                            "keeping it queued (${unrecorded::class.simpleName})"
                    }
                    SyncStop.TransientFailure(unrecorded)
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
                        conflicted,
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
         * @property returnedRows the rows the server returned, which later guarded writes to them
         *   take as their base.
         * @property conflictResolved whether its row had moved and the conflict policy settled it.
         */
        data class Synced(
            val rowsWereOnServer: Boolean,
            val returnedRows: List<JsonObject> = emptyList(),
            val conflictResolved: Boolean = false,
        ) : ReplayOutcome

        /** Its row moved or went, and the conflict policy let the server's row stand: dropped. */
        data object KeptServer : ReplayOutcome

        /** Its row moved and it is parked on the conflicted list, as [parked] describes. */
        data class Conflicted(val parked: ParkedConflict) : ReplayOutcome

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
     * device when the session is no longer [identity]'s or [pass] was canceled.
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
                when (
                    val sent =
                        attempt(mutation, identity, pass, newlyUnconfirmed) {
                            prepared.request(tableApi)
                        }
                ) {
                    is Attempt.Done -> ReplayOutcome.Synced(rowsWereOnServer = sent.value)
                    is Attempt.Ended -> sent.outcome
                }
            }
            is Prepared.Guarded -> replayGuarded(mutation, prepared, tableApi, identity, pass)
        }
    }

    /** One request of a replay: what it returned, or how the replay ends. */
    private sealed interface Attempt<out T> {
        /** The server answered with [value]. */
        class Done<T>(val value: T) : Attempt<T>

        /** The request failed; the replay ends with [outcome]. */
        class Ended(val outcome: ReplayOutcome) : Attempt<Nothing>
    }

    /**
     * Sends [request] for [mutation] as [identity], the pass [pass] running it: stopped before it
     * leaves the device when the session is no longer [identity]'s or [pass] was canceled.
     *
     * @param newlyUnconfirmed whether this attempt recorded the mutation's send as unconfirmed.
     * @throws CancellationException when [pass] was canceled before the request left.
     */
    private suspend fun <T> attempt(
        mutation: MutationRow,
        identity: QueueIdentity,
        pass: Job,
        newlyUnconfirmed: Boolean = false,
        request: suspend () -> T,
    ): Attempt<T> {
        val owner = ReplayOwner(identity, sender = pass)
        return when (val sent = sendCapturingEngineFailure { withContext(owner) { request() } }) {
            is SendOutcome.Succeeded -> Attempt.Done(sent.value)
            is SendOutcome.Failed ->
                if (owner.stoppedWithItsSender) {
                    // It never left the device: the pass ends here, canceled
                    if (newlyUnconfirmed) mutationQueue.clearUnconfirmedSend(mutation.id)
                    throw CancellationException("The replay pass was stopped")
                } else if (owner.stoppedForAnotherSession) {
                    stoppedForAnotherSession(mutation.id, newlyUnconfirmed, identity)
                    Attempt.Ended(ReplayOutcome.SessionChanged)
                } else {
                    if (newlyUnconfirmed && sent.provesWriteNotApplied())
                        mutationQueue.clearUnconfirmedSend(mutation.id)
                    Attempt.Ended(classify(mutation, sent.thrown, identity))
                }
        }
    }

    /**
     * Replays a guarded UPDATE or DELETE: preconditioned on its base version, asking for its rows
     * back. Rows back mean it applied; none, and one read of the row by key tells a policy denial,
     * a lost answer, a gone row and a moved one, which [conflictPolicy] settles, resending at most
     * [MAX_CONFLICT_ROUNDS] times in all before the write is parked.
     */
    private suspend fun replayGuarded(
        mutation: MutationRow,
        guarded: Prepared.Guarded,
        tableApi: RemoteTable,
        identity: QueueIdentity,
        pass: Job,
    ): ReplayOutcome {
        val table = mutation.table
        val versionColumn = guarded.versionColumn
        val base = baseOf(mutation, versionColumn, guarded.baseVersion)
        var precondition = guarded.baseVersion
        var body = guarded.body
        var resolved = false
        var lastCase: ConflictCase? = null
        repeat(MAX_CONFLICT_ROUNDS) {
            val sentBody = body
            val sentPrecondition = precondition
            val startedAtTick = freshness.tickNow()
            val result =
                when (
                    val sent =
                        attempt(mutation, identity, pass) {
                            if (sentBody == null) {
                                tableApi.delete { guardedBy(guarded, sentPrecondition) }
                            } else {
                                tableApi.update(sentBody) { guardedBy(guarded, sentPrecondition) }
                            }
                        }
                ) {
                    is Attempt.Done -> sent.value
                    is Attempt.Ended -> return sent.outcome
                }
            val returned = returnedRowsOf(EngineJson, result.data)
            if (returned.isNotEmpty()) {
                if (sentBody == null)
                    return ReplayOutcome.Synced(false, conflictResolved = resolved)
                patchCacheAfterReplay(table) { patcher ->
                    patcher.patchReturnedRows(table, sentBody, result.data, startedAtTick)
                }
                return ReplayOutcome.Synced(false, returned, conflictResolved = resolved)
            }

            // No row matched: one read of it by key tells why
            val readStartedAtTick = freshness.tickNow()
            val server =
                when (
                    val read =
                        attempt(mutation, identity, pass) {
                            tableApi.select(Columns.ALL) {
                                replayOnto(this, guarded.keyFilter)
                                limit(1)
                            }
                        }
                ) {
                    is Attempt.Done -> returnedRowsOf(EngineJson, read.value.data).firstOrNull()
                    is Attempt.Ended -> return read.outcome
                }
            val case =
                if (server == null) {
                    // A DELETE whose row is gone has nothing left to do
                    if (sentBody == null)
                        return ReplayOutcome.Synced(true, conflictResolved = resolved)
                    ConflictCase.Gone(mutation, base, sentBody)
                } else {
                    val serverVersion = versionTextOf(server[versionColumn])
                    when {
                        serverVersion == sentPrecondition -> return policyDenied(mutation)
                        serverVersion == null ->
                            return parked(mutation, server, NO_SERVER_VERSION_REASON)
                        // The earlier send landed and its answer was lost
                        sentBody != null &&
                            sentBody.all { (column, value) -> server[column] == value } -> {
                            patchCacheAfterReplay(table) { patcher ->
                                patcher.patchReturnedRows(
                                    table,
                                    JsonObject(emptyMap()),
                                    JsonArray(listOf(server)).toString(),
                                    readStartedAtTick,
                                )
                            }
                            return ReplayOutcome.Synced(true, listOf(server), resolved)
                        }
                        else -> movedCase(mutation, base, sentBody, server, serverVersion)
                    }
                }
            lastCase = case
            when (val decision = decide(case, pass)) {
                ConflictDecision.KeepLocal -> {
                    if (case !is ConflictCase.Moved) return parked(case, HELD_REASON)
                    precondition = case.serverVersion
                    resolved = true
                }
                is ConflictDecision.Apply -> {
                    if (case !is ConflictCase.Moved || sentBody == null)
                        return parked(case, HELD_REASON)
                    body = decision.columns
                    precondition = case.serverVersion
                    resolved = true
                }
                ConflictDecision.KeepServer -> {
                    keepServerRow(case, guarded, readStartedAtTick)
                    return ReplayOutcome.KeptServer
                }
                ConflictDecision.Hold -> return parked(case, HELD_REASON)
            }
        }
        return parked(checkNotNull(lastCase), KEPT_MOVING_REASON)
    }

    /** The stored filter, the version precondition and a request for the rows back. */
    private fun PostgrestRequestBuilder.guardedBy(guarded: Prepared.Guarded, precondition: String) {
        replayOnto(this, guarded.filter)
        filter { eq(guarded.versionColumn, precondition) }
        select()
    }

    /** [mutation]'s base row as stored, or just its [baseVersion] when that no longer reads. */
    private fun baseOf(
        mutation: MutationRow,
        versionColumn: String,
        baseVersion: String,
    ): JsonObject {
        val stored =
            mutation.baseJson?.let { text ->
                try {
                    EngineJson.parseToJsonElement(text) as? JsonObject
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        return stored ?: JsonObject(mapOf(versionColumn to JsonPrimitive(baseVersion)))
    }

    /** A moved row as a [ConflictCase.Moved], its columns classified against [base]. */
    private fun movedCase(
        mutation: MutationRow,
        base: JsonObject,
        local: JsonObject?,
        server: JsonObject,
        serverVersion: String,
    ): ConflictCase.Moved {
        val changedOnServer =
            local?.keys.orEmpty().filterTo(LinkedHashSet()) { column ->
                server[column] != base[column]
            }
        val conflicting =
            changedOnServer.filterTo(LinkedHashSet()) { column ->
                local?.get(column) != server[column]
            }
        return ConflictCase.Moved(
            mutation = mutation,
            base = base,
            local = local,
            server = server,
            serverVersion = serverVersion,
            changedOnServer = changedOnServer,
            conflictingColumns = conflicting,
        )
    }

    /**
     * [conflictPolicy]'s decision on [case], bounded by [conflictPolicyTimeout]; a timeout or a
     * failure reads as Hold, logged with the table and the failure's type only.
     */
    private suspend fun decide(case: ConflictCase, pass: Job): ConflictDecision {
        val table = case.mutation.table
        return try {
            withTimeoutOrNull(conflictPolicyTimeout) { conflictPolicy.decide(case) }
                ?: ConflictDecision.Hold.also {
                    engineLogger.warning {
                        "The conflict policy for $table did not decide in time; holding the write"
                    }
                }
        } catch (failure: Exception) {
            if (failure is CancellationException && pass.isCancelled) throw failure
            engineLogger.warning {
                "The conflict policy for $table failed (${failure::class.simpleName}); " +
                    "holding the write"
            }
            ConflictDecision.Hold
        }
    }

    /** Lays the server's row of [case] into the cache, or drops the gone row, as the write goes. */
    private suspend fun keepServerRow(case: ConflictCase, guarded: Prepared.Guarded, tick: Long) {
        val table = case.mutation.table
        patchCacheAfterReplay(table) { patcher ->
            when (case) {
                is ConflictCase.Moved ->
                    patcher.patchReturnedRows(
                        table,
                        JsonObject(emptyMap()),
                        JsonArray(listOf(case.server)).toString(),
                        tick,
                    )
                is ConflictCase.Gone ->
                    patcher.patchCachedQueries(
                        table = table,
                        operation = MutationOperation.DELETE,
                        bodyJson = "{}",
                        filterJson = encodeFilter(guarded.keyFilter, EngineJson),
                        startedAtTick = tick,
                    )
            }
        }
    }

    /**
     * Runs [patch] on the cache patcher, logging a failure: the server has the write either way.
     */
    private suspend fun patchCacheAfterReplay(
        table: String,
        patch: suspend (CachePatcher) -> Unit,
    ) {
        val patcher = cachePatcher ?: return
        try {
            patch(patcher)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Patching the cache after a replayed write to $table failed: ${failure::class.simpleName}"
            }
        }
    }

    /** A guarded write the server's row still matches but did not change: a policy denies it. */
    private fun policyDenied(mutation: MutationRow): ReplayOutcome.Rejected {
        engineLogger.warning {
            "Replayed ${mutation.operation} on ${mutation.table} changed no row the server still " +
                "holds unchanged; moved to the rejected list"
        }
        return ReplayOutcome.Rejected(message = POLICY_DENIED_REASON, serverError = null)
    }

    /** [case]'s write, parked on the conflicted list for [message]. */
    private fun parked(case: ConflictCase, message: String): ReplayOutcome.Conflicted =
        when (case) {
            is ConflictCase.Moved ->
                parkedWith(
                    case.mutation,
                    ParkedConflict(
                        serverRow = case.server,
                        serverVersion = case.serverVersion,
                        changedOnServer = case.changedOnServer,
                        conflictingColumns = case.conflictingColumns,
                        message = message,
                    ),
                )
            is ConflictCase.Gone ->
                parkedWith(
                    case.mutation,
                    ParkedConflict(null, null, emptySet(), emptySet(), message),
                )
        }

    /** [mutation], parked with [server] as the row the replay found, for [message]. */
    private fun parked(
        mutation: MutationRow,
        server: JsonObject,
        message: String,
    ): ReplayOutcome.Conflicted =
        parkedWith(mutation, ParkedConflict(server, null, emptySet(), emptySet(), message))

    private fun parkedWith(
        mutation: MutationRow,
        parked: ParkedConflict,
    ): ReplayOutcome.Conflicted {
        engineLogger.info {
            "Parked a queued ${mutation.operation} on ${mutation.table} on the conflicted list"
        }
        return ReplayOutcome.Conflicted(parked)
    }

    private suspend fun classify(
        mutation: MutationRow,
        failure: Exception,
        identity: QueueIdentity,
    ): ReplayOutcome =
        when {
            failure.isNetworkOrConnectivityFailure() -> {
                engineLogger.info { "Network unavailable, pausing sync for ${mutation.table}" }
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

        /**
         * A guarded UPDATE ([body] set) or DELETE (`null`): [filter] plus `eq` on [versionColumn],
         * first at [baseVersion]; [keyFilter] reads its one row back.
         */
        class Guarded(
            val body: JsonObject?,
            val filter: FilterDescriptor,
            val keyFilter: FilterDescriptor,
            val versionColumn: String,
            val baseVersion: String,
        ) : Prepared
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
        return when (mutation.knownOperation) {
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
                if (body is JsonObject) {
                    guardedOrNull(mutation, replayableFilter, body)?.let { guarded ->
                        return guarded
                    }
                }
                Prepared.Ready { tableApi ->
                    sendUpdate(tableApi, body, replayableFilter)
                    false
                }
            }

            MutationOperation.DELETE -> {
                val replayableFilter =
                    replayableFilterOrNull(filter)
                        ?: return refuseUnreplayableFilter(mutation, filter)
                guardedOrNull(mutation, replayableFilter, body = null)?.let { guarded ->
                    return guarded
                }
                Prepared.Ready { tableApi ->
                    sendDelete(tableApi, replayableFilter)
                    false
                }
            }

            null ->
                refuse(
                    mutation,
                    "has an unknown operation",
                    "The queued operation is not recognized",
                )
        }
    }

    /**
     * [mutation] as a guarded replay of [filter], with [body] for an UPDATE: when it has a base,
     * its table still has a version column [filter] does not name, and [filter] pins one row's key.
     */
    private fun guardedOrNull(
        mutation: MutationRow,
        filter: FilterDescriptor,
        body: JsonObject?,
    ): Prepared.Guarded? {
        val baseVersion = mutation.baseVersion ?: return null
        val versionColumn = mutationQueue.versionColumns.of(mutation.table) ?: return null
        if (filter.conditions.any { condition -> condition.column == versionColumn }) return null
        val key = pinnedRowKeyOf(filter, primaryKeys.of(mutation.table)) ?: return null
        val keyFilter =
            FilterDescriptor(
                conditions =
                    key.map { (column, value) ->
                        FilterDescriptor.Condition(column, "eq", value.jsonPrimitive.content)
                    }
            )
        return Prepared.Guarded(body, filter, keyFilter, versionColumn, baseVersion)
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

        /** The most sends of one guarded write in one pass, the first included. */
        const val MAX_CONFLICT_ROUNDS = 3

        /** Why a guarded write the server's row still matches, unchanged, is rejected. */
        const val POLICY_DENIED_REASON =
            "The row cannot be changed under the server's current policies"

        /** Why a write is parked when the conflict policy holds it. */
        const val HELD_REASON = "The row changed on the server since this write was made"

        /** Why a write is parked when its row kept moving while it was replayed. */
        const val KEPT_MOVING_REASON =
            "The row kept changing on the server while this write was replayed"

        /** Why a write is parked when the server's row has no version to precondition on. */
        const val NO_SERVER_VERSION_REASON =
            "The server's row has no version to check this write against"

        /** Postgres: `ON CONFLICT` names columns with no unique or exclusion constraint. */
        const val NO_MATCHING_CONFLICT_TARGET = "42P10"
        const val BODY_NO_LONGER_PARSES = "has a body that no longer parses"
    }
}
