// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.cache.CachePatcher
import io.github.nesfeder.supabase.offline.internal.cache.returnedRowsOf
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.ReplayAttempts.Attempt
import io.github.nesfeder.supabase.offline.internal.mutationqueue.ReplayRequests.Prepared
import io.github.nesfeder.supabase.offline.internal.postgrest.RemoteTable
import io.github.nesfeder.supabase.offline.internal.postgrest.RowMatch
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.replayOnto
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Replays a guarded UPDATE or DELETE and settles its conflict rounds: the precondition on its base
 * version, the read by key that tells why no row matched, the conflict policy's decision, and the
 * write parked, kept or sent again. A pass hands it each guarded write [ReplayRequests] prepares.
 */
internal class GuardedReplay(
    /** Where the ticks of a send and a read are taken. */
    private val freshness: CacheFreshness,
    /** Sends each request of a round. */
    private val attempts: ReplayAttempts,
    /**
     * Patches the rows a guarded replay finds on the server into the cache; `null` patches none.
     */
    private val cachePatcher: CachePatcher?,
    /** Settles a guarded write whose row moved on the server, or is gone. */
    private val conflictPolicy: ConflictPolicy,
    /** How long [conflictPolicy] may take before its answer reads as Hold. */
    private val conflictPolicyTimeout: Duration,
    /** Where [conflictPolicy] runs; `null` runs it on the pass's own dispatcher. */
    private val conflictPolicyDispatcher: CoroutineDispatcher?,
    private val engineLogger: EngineLogger,
) {

    /**
     * Replays a guarded UPDATE or DELETE: preconditioned on its base version, asking for its rows
     * back. Rows back mean it applied; none, and one read of the row by key tells a policy denial,
     * a lost answer, a row the filter no longer reaches, a stale base, a gone row and a moved one,
     * which [conflictPolicy] settles, in at most [MAX_CONFLICT_ROUNDS] sends before it is parked.
     */
    suspend fun replayGuarded(
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
        for (round in 1..MAX_CONFLICT_ROUNDS) {
            val sentBody = body
            val sentPrecondition = precondition
            val returning = guarded.returnedColumns(sentBody)
            val startedAtTick = freshness.tickNow()
            val result =
                when (
                    val sent =
                        attempts.attempt(mutation, identity, pass) {
                            if (sentBody == null) {
                                tableApi.delete { guardedBy(guarded, sentPrecondition, returning) }
                            } else {
                                tableApi.update(sentBody) {
                                    guardedBy(guarded, sentPrecondition, returning)
                                }
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
            val readColumns = guarded.readColumns(sentBody)
            val server =
                when (
                    val read =
                        attempts.attempt(mutation, identity, pass) {
                            tableApi.select(readColumns) {
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
                    val beyondKey = guarded.filterBeyondKeyMatch(server)
                    when {
                        // The row provably no longer meets the rest of the filter: the unguarded
                        // replay reached no row either, and that was a success
                        beyondKey == RowMatch.EXCLUDED ->
                            return ReplayOutcome.Synced(false, conflictResolved = resolved)
                        // A condition the engine cannot judge, at the version the precondition
                        // named: the server itself found the filter does not reach the row
                        beyondKey == RowMatch.UNKNOWN && serverVersion == sentPrecondition ->
                            return ReplayOutcome.Synced(false, conflictResolved = resolved)
                        serverVersion == sentPrecondition -> return policyDenied(mutation)
                        serverVersion == null ->
                            return park(
                                mutation,
                                server,
                                NO_SERVER_VERSION_REASON,
                                versionColumn,
                                readStartedAtTick,
                            )
                        // The earlier send landed and its answer was lost. Values compare as JSON:
                        // one the server rewrote (`1` as `1.0`) reads as a move, resent harmlessly
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
            // No send is left to act on an answer, so nobody is asked
            if (round == MAX_CONFLICT_ROUNDS)
                return park(case, KEPT_MOVING_REASON, versionColumn, readStartedAtTick)
            // A stale base, not a conflict: none of the UPDATE's columns moved on the server since
            // the device saw the row, so it is resent at the new version without asking anyone
            if (case is ConflictCase.Moved && sentBody != null && case.changedOnServer.isEmpty()) {
                precondition = case.serverVersion
                continue
            }
            when (val decision = decide(case, pass)) {
                ConflictDecision.KeepLocal -> {
                    if (case !is ConflictCase.Moved)
                        return park(case, HELD_REASON, versionColumn, readStartedAtTick)
                    precondition = case.serverVersion
                    resolved = true
                }
                is ConflictDecision.Apply -> {
                    if (case !is ConflictCase.Moved || sentBody == null)
                        return park(case, HELD_REASON, versionColumn, readStartedAtTick)
                    body = decision.columns
                    precondition = case.serverVersion
                    resolved = true
                }
                ConflictDecision.KeepServer -> {
                    keepServerRow(case, guarded, readStartedAtTick)
                    return ReplayOutcome.KeptServer
                }
                ConflictDecision.Hold ->
                    return park(case, HELD_REASON, versionColumn, readStartedAtTick)
            }
        }
        error("A guarded replay ends within its rounds")
    }

    /** The stored filter, the version precondition and a request for [returning] back. */
    private fun PostgrestRequestBuilder.guardedBy(
        guarded: Prepared.Guarded,
        precondition: String,
        returning: Columns,
    ) {
        replayOnto(this, guarded.filter)
        filter { eq(guarded.versionColumn, precondition) }
        select(returning)
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
     *
     * @throws CancellationException when [pass] is canceled while the policy decides.
     */
    private suspend fun decide(case: ConflictCase, pass: Job): ConflictDecision {
        val table = case.mutation.table
        return try {
            // In the pass's job, so a clear or a close that cancels the pass cuts the wait short,
            // and on the engine's dispatcher, never the caller's, with the bound on its clock
            withContext(pass + (conflictPolicyDispatcher ?: EmptyCoroutineContext)) {
                withTimeoutOrNull(conflictPolicyTimeout) { conflictPolicy.decide(case) }
            }
                ?: ConflictDecision.Hold.also {
                    engineLogger.warning {
                        "The conflict policy for $table did not decide in time; holding the write"
                    }
                }
        } catch (failure: Exception) {
            // The write stays queued, as for a request the pass's cancellation stopped
            if (pass.isCancelled) throw CancellationException("The replay pass was stopped")
            engineLogger.warning {
                "The conflict policy for $table failed (${failure::class.simpleName}); " +
                    "holding the write"
            }
            ConflictDecision.Hold
        }
    }

    /**
     * Lays the server's row of [case] into the cache, as [showServerRow] does, or drops the gone
     * row, as the write goes.
     */
    private suspend fun keepServerRow(case: ConflictCase, guarded: Prepared.Guarded, tick: Long) {
        val table = case.mutation.table
        when (case) {
            is ConflictCase.Moved -> showServerRow(table, case.server, guarded.versionColumn, tick)
            is ConflictCase.Gone ->
                patchCacheAfterReplay(table) { patcher ->
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
     * Lays the columns of [server], the row a replay read by key at [tick], into [table]'s cached
     * queries, all but [versionColumn]: a write that leaves the queue is no longer laid over them,
     * so they show the server's values for the columns the read asked for. The read asks for the
     * key, the version and the write's own columns, never the whole row, so the cached row keeps
     * the version the device saw: a later write to it still meets the move, as a stale base for the
     * columns read and a conflict for the rest.
     */
    private suspend fun showServerRow(
        table: String,
        server: JsonObject,
        versionColumn: String,
        tick: Long,
    ) {
        patchCacheAfterReplay(table) { patcher ->
            patcher.patchReturnedRows(
                table,
                JsonObject(emptyMap()),
                JsonArray(listOf(JsonObject(server - versionColumn))).toString(),
                tick,
            )
        }
    }

    /**
     * [case]'s write, parked for [message], the server's row laid into the cache first as
     * [keepServerRow] lays it: the parked write is not shown over the cache any more, so the device
     * shows the row's columns as the server has them until the app settles the write. A gone row's
     * cached copy stays, since a row that is gone and one a policy hides look the same from the
     * device; Keep theirs on the entry drops it.
     */
    private suspend fun park(
        case: ConflictCase,
        message: String,
        versionColumn: String,
        tick: Long,
    ): ReplayOutcome.Conflicted {
        if (case is ConflictCase.Moved)
            showServerRow(case.mutation.table, case.server, versionColumn, tick)
        return parked(case, message)
    }

    /** [mutation], parked with [server] as the row the replay found, laid into the cache first. */
    private suspend fun park(
        mutation: MutationRow,
        server: JsonObject,
        message: String,
        versionColumn: String,
        tick: Long,
    ): ReplayOutcome.Conflicted {
        showServerRow(mutation.table, server, versionColumn, tick)
        return parked(mutation, server, message)
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

    /** [case]'s write, parked for [message]; a gone row's always for [GONE_REASON]. */
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
                    ParkedConflict(null, null, emptySet(), emptySet(), GONE_REASON),
                )
        }

    /** [mutation], parked with [server] as the row the replay found, for [message]. */
    private fun parked(
        mutation: MutationRow,
        server: JsonObject,
        message: String,
    ): ReplayOutcome.Conflicted =
        parkedWith(mutation, ParkedConflict(server, null, emptySet(), emptySet(), message))

    /** [mutation] as parked with [parked], said at info with its operation and table only. */
    private fun parkedWith(
        mutation: MutationRow,
        parked: ParkedConflict,
    ): ReplayOutcome.Conflicted {
        engineLogger.info {
            "Parked a queued ${mutation.operation} on ${mutation.table} on the conflicted list"
        }
        return ReplayOutcome.Conflicted(parked)
    }

    private companion object {
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
    }
}

/**
 * Why a write is parked when its row is gone from the server, or hidden from the user: the one
 * reason a parked write with no server version carries when there was no row, which tells such an
 * entry from one whose row came back without a version.
 */
internal const val GONE_REASON: String = "The row is gone from the server, or hidden from this user"
