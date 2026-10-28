// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.ReplayAttempts.Attempt
import io.github.nesfeder.supabase.offline.internal.mutationqueue.ReplayRequests.Prepared
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
import io.github.nesfeder.supabase.offline.internal.postgrest.RemoteTable
import io.github.nesfeder.supabase.offline.internal.storage.isQueuedUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Runs the replay passes: the current identity's mutations sent in insertion order, each claimed
 * under the send lock, sent, and its outcome recorded before the next is claimed, stopping at the
 * first failure that may pass. [MutationQueueReplayer.sync] starts one.
 */
internal class ReplayPass(
    /** The queue it sends, and records each send's outcome in. */
    private val mutationQueue: MutationQueue,
    /** What it shares with the other senders. */
    private val sendState: SendState,
    /** Whether the client may reach the network; nothing is sent while it is off. */
    private val networkAccess: NetworkAccess,
    /** The remote table a pass sends a table's writes to. */
    private val remoteTableFor: (schema: String, table: String) -> RemoteTable,
    /** Waits, bounded, for a settled session; `false` means it did not settle. */
    private val awaitSessionSettled: suspend () -> Boolean,
    /** The identity a request sent now would go out as, or `null` while that cannot be told. */
    private val currentIdentity: suspend () -> QueueIdentity?,
    /** Whether the session is settled now, without waiting. */
    private val isSessionSettledNow: () -> Boolean,
    /** Each table's key columns, which a report's rejected entries are told apart by. */
    private val primaryKeys: PrimaryKeys,
    /** Sends the queued Storage uploads; `null` refuses each one it meets to the rejected list. */
    private val uploadSends: QueuedUploadSends?,
    /** Builds each row write's request. */
    private val requests: ReplayRequests,
    /** Sends each request. */
    private val attempts: ReplayAttempts,
    /** Replays the guarded writes. */
    private val guardedReplay: GuardedReplay,
    private val engineLogger: EngineLogger,
) {

    /** [SendState.sendLock]. */
    private val sendLock = sendState.sendLock

    /** [SendState.inFlight]. */
    private val inFlight = sendState.inFlight

    /** [SendState.clearGeneration]. */
    private val clearGeneration = sendState.clearGeneration

    /** [SendState.activePasses]. */
    private val activePasses = sendState.activePasses

    /**
     * One pass, as [MutationQueueReplayer.sync] describes it, without telling its listener: the
     * replayer does.
     */
    suspend fun run(table: String?, schema: String): SyncReport {
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
            // Listed before it starts: a pass that holds a write is one a caller can wait out
            val pass =
                async(start = CoroutineStart.LAZY) { runPass(table, schema, generation, progress) }
            activePasses.update { passes -> passes + pass }
            try {
                pass.await()
            } catch (cancellation: CancellationException) {
                // Rethrows when the caller itself was canceled. Otherwise, when local data was
                // cleared, only the pass was canceled, by clearLocalData, which left nothing
                // queued: report what it sent before. A cancellation from anywhere else (the
                // transport's own) is this pass's failure, not the caller's and not a clear: the
                // queue stands for a later pass, as after any failure on the device
                currentCoroutineContext().ensureActive()
                if (clearGeneration.value != generation) {
                    progress.report(remainingCount = 0, stop = SyncStop.LocalDataCleared)
                } else {
                    engineLogger.warning {
                        "A replay pass was canceled by something other than a clear " +
                            "(${cancellation::class.simpleName}); the queued writes stay queued"
                    }
                    progress.report(
                        remainingCount = remainingHeld(currentIdentity(), table),
                        stop =
                            SyncStop.TransientFailure(
                                IllegalStateException("The replay pass was canceled", cancellation)
                            ),
                    )
                }
            } finally {
                activePasses.update { passes -> passes - pass }
            }
        }
    }

    /**
     * How many of [identity]'s mutations are pending in [table], or in every table, counted rather
     * than read; every identity's when [identity] is `null`.
     */
    private suspend fun remainingFor(identity: QueueIdentity?, table: String?): Int =
        if (identity == null) mutationQueue.allPendingMutationCount(table)
        else mutationQueue.pendingMutationCountOf(identity.storedValue, table)

    /**
     * [remainingFor], counted under [sendLock], as a pass's report counts it: never inside another
     * sender's recording, between a sent upload's row leaving the queue and its file's deletion.
     */
    private suspend fun remainingHeld(identity: QueueIdentity?, table: String?): Int =
        sendLock.withLock {
            remainingFor(identity, table)
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
     *
     * @param pass The pass claiming it, recorded in [inFlight] as its sender.
     */
    private suspend fun claim(
        candidate: MutationRow,
        table: String?,
        identity: QueueIdentity,
        generation: Long,
        pass: Job,
    ): Claim {
        if (clearGeneration.value != generation) return Claim.Stop(SyncStop.LocalDataCleared)
        if (networkAccess.isDisabled) return Claim.Stop(SyncStop.NetworkDisabled)
        val now = currentIdentity() ?: return Claim.Stop(SyncStop.IdentityUnknown)
        if (now != identity) return Claim.Stop(SyncStop.SessionChanged)
        if (inFlight.value.keys.any { id -> id <= candidate.id })
            return Claim.Stop(SyncStop.HeadInFlight)
        // Read again: a write sent before it may have moved its base
        val current = mutationQueue.pendingMutation(candidate.id) ?: return Claim.Skip
        if (
            table != null &&
                mutationQueue.hasEarlierPendingMutation(candidate.id, identity.storedValue)
        )
            return Claim.Stop(SyncStop.HeldBehindOtherTables(candidate.id))
        inFlight.update { sending -> sending + (candidate.id to pass) }
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
                    rejected
                        .filterNot { entry -> entry.isQueuedUpload }
                        .map { entry -> entry.toRejectedMutation(EngineJson, primaryKeys) },
                rejectedUploads = rejected.filter { entry -> entry.isQueuedUpload },
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
        val queue = sendLock.withLock {
            if (clearGeneration.value != generation)
                return SyncReport(0, emptyList(), 0, SyncStop.LocalDataCleared)
            mutationQueue.retireUnneededSyncedMutations()
            mutationQueue.adoptOwnerlessMutations(identity.storedValue)
            // Read under the lock: a sender records a sent upload in one hold of it, from the
            // row's removal to its file's deletion, so this never finds the one without the
            // other
            mutationQueue.pendingMutationsOf(identity.storedValue, table)
        }
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
                    val claim = sendLock.withLock {
                        claim(candidate, table, identity, generation, pass)
                    }
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
                                val tableApi = {
                                    tableApis.getOrPut(mutation.table) {
                                        remoteTableFor(schema, mutation.table)
                                    }
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
                            inFlight.update { sending -> sending - mutation.id }
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
                            sendLock.withLock {
                                inFlight.update { sending -> sending - mutation.id }
                            }
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
        return progress.report(remainingCount = remainingHeld(identity, table), stop = stop)
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
            is ReplayOutcome.Synced if mutation.isQueuedUpload -> {
                // Nothing overlays a sent upload: it leaves the queue, rather than stay synced.
                // The bytes it kept on the device go in the same hold of the send lock, so a pass
                // that finds the queue empty never finds the file still there
                mutationQueue.completeSentMutation(mutation.id)
                uploadSends?.sent(mutation)
                engineLogger.debug { "Synced a queued upload" }
                null
            }

            is ReplayOutcome.Synced -> {
                sendState.forgetSchemaCacheMisses(mutation.id)
                mutationQueue.recordReplayed(
                    id = mutation.id,
                    table = mutation.table,
                    returnedRows = outcome.returnedRows,
                    owner = mutation.owner,
                )
                syncedTables += mutation.table
                engineLogger.debug { "Synced ${mutation.operation} mutation for ${mutation.table}" }
                null
            }

            ReplayOutcome.KeptServer -> {
                sendState.forgetSchemaCacheMisses(mutation.id)
                mutationQueue.dropKeptServerMutation(mutation.id)
                engineLogger.info {
                    "Dropped a queued ${mutation.operation} on ${mutation.table}: the server's row stands"
                }
                null
            }

            is ReplayOutcome.Conflicted ->
                try {
                    sendState.forgetSchemaCacheMisses(mutation.id)
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
                if (sendState.schemaCacheMissReachesBound(mutation.id)) {
                    sendState.forgetSchemaCacheMisses(mutation.id)
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

    /**
     * Sends [mutation] as [identity], the pass [pass] running it: stopped before it leaves the
     * device when the session is no longer [identity]'s or [pass] was canceled.
     */
    private suspend fun replay(
        mutation: MutationRow,
        tableApi: () -> RemoteTable,
        identity: QueueIdentity,
        pass: Job,
    ): ReplayOutcome {
        // Decided before this attempt is recorded: only an earlier attempt can have reached the
        // server.
        val earlierSendUnconfirmed = mutationQueue.hasUnconfirmedSend(mutation.id)
        if (mutation.isQueuedUpload) {
            return replayUpload(mutation, earlierSendUnconfirmed, identity, pass)
        }
        return when (val prepared = requests.requestFor(mutation, earlierSendUnconfirmed)) {
            is Prepared.Refused -> prepared.outcome
            is Prepared.Ready -> {
                val newlyUnconfirmed = mutationQueue.markSendUnconfirmed(mutation.id)
                when (
                    val sent =
                        attempts.attempt(mutation, identity, pass, newlyUnconfirmed) {
                            prepared.request(tableApi())
                        }
                ) {
                    is Attempt.Done -> ReplayOutcome.Synced(rowsWereOnServer = sent.value)
                    is Attempt.Ended -> sent.outcome
                }
            }
            is Prepared.Guarded ->
                guardedReplay.replayGuarded(mutation, prepared, tableApi(), identity, pass)
        }
    }

    /**
     * Sends the queued upload [mutation] as [identity], the pass [pass] running it, as [replay]
     * sends a row write: its send recorded as unconfirmed first, and stopped before it leaves the
     * device when the session is no longer [identity]'s or [pass] was canceled.
     */
    private suspend fun replayUpload(
        mutation: MutationRow,
        earlierSendUnconfirmed: Boolean,
        identity: QueueIdentity,
        pass: Job,
    ): ReplayOutcome {
        val sends =
            uploadSends
                ?: return requests
                    .refuse(
                        mutation,
                        "cannot be sent: Storage is not installed",
                        STORAGE_NOT_INSTALLED_REASON,
                    )
                    .outcome
        return when (val prepared = sends.prepare(mutation, earlierSendUnconfirmed)) {
            is UploadSend.Refused ->
                requests.refuse(mutation, prepared.reason, prepared.message).outcome
            is UploadSend.Ready -> {
                val newlyUnconfirmed = mutationQueue.markSendUnconfirmed(mutation.id)
                when (
                    val sent =
                        attempts.attempt(mutation, identity, pass, newlyUnconfirmed) {
                            prepared.send()
                        }
                ) {
                    is Attempt.Done -> ReplayOutcome.Synced(rowsWereOnServer = sent.value)
                    is Attempt.Ended -> sent.outcome
                }
            }
        }
    }

    private companion object {
        /** Why a write is rejected at the bound, when the server's answer carries no message. */
        const val SCHEMA_CACHE_MISS_REASON =
            "The server's schema does not have the table or a column this write needs"
    }
}

/** How one mutation's replay ended. */
internal sealed interface ReplayOutcome {
    /**
     * The server accepted it.
     *
     * @property rowsWereOnServer whether it found rows of it already on the server: an earlier send
     *   that seemed lost had arrived.
     * @property returnedRows the rows the server returned, which later guarded writes to them take
     *   as their base.
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

    data class Rejected(val message: String, val serverError: StoredServerError?) : ReplayOutcome

    data class Held(val cause: Throwable) : ReplayOutcome

    /** PostgREST's schema cache did not know the table or a column; the write did not run. */
    data class SchemaCacheMiss(val cause: Exception) : ReplayOutcome

    data class Stopped(val cause: Throwable) : ReplayOutcome
}
