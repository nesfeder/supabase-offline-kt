package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.NumericDeltaColumn
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncReport
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.internal.mutationqueue.currentReplayIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.everyRowCarriesPrimaryKey
import io.github.nesfeder.supabase.offline.internal.mutationqueue.toRejectedMutation
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine.applyTo
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine.decodeOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.PostgrestQueryBuilderWrapper
import io.github.nesfeder.supabase.offline.internal.postgrest.PostgrestQueryBuilderWrapperImpl
import io.github.nesfeder.supabase.offline.internal.postgrest.SendOutcome
import io.github.nesfeder.supabase.offline.internal.postgrest.sendCapturingEngineFailure
import io.github.nesfeder.supabase.offline.internal.util.awaitReplaySafeSession
import io.github.nesfeder.supabase.offline.internal.util.httpStatusOrNull
import io.github.nesfeder.supabase.offline.internal.util.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.internal.util.isPermanentMutationFailure
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sends the mutation queue to Supabase: replay passes, and the immediate send of a mutation that is
 * at the head of its identity's queue when it is made. Exactly one sender at a time per mutation.
 */
internal class MutationQueueReplayer(
    private val store: OfflineStore,
    private val supabase: SupabaseClient,
    private val sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    private val postgrestFactory: (schema: String, table: String) -> PostgrestQueryBuilderWrapper =
        { schema, table -> PostgrestQueryBuilderWrapperImpl(supabase.postgrest.from(schema, table)) },
    /** Waits, bounded, for a settled session; `false` means it did not settle and nothing is sent. */
    private val awaitSessionSettled: suspend () -> Boolean = { supabase.awaitReplaySafeSession() },
    /** The identity a request sent now would go out as, or `null` while that cannot be told. */
    private val currentIdentity: suspend () -> QueueIdentity? = { supabase.currentReplayIdentity() },
    private val primaryKeys: PrimaryKeys = DefaultPrimaryKeys,
) {

    /** Guards choosing a mutation to send and recording its outcome. Never held across a request. */
    private val sendLock = Mutex()

    /** Ids of the mutations being sent right now, by a pass or at the head. Guarded by [sendLock]. */
    private val inFlight = mutableSetOf<Long>()

    /** Bumped by [clearLocalData]; an outcome recorded after it moved is discarded. */
    private val clearGeneration = MutableStateFlow(0L)

    /** Passes in flight, so [clearLocalData] can cancel them. */
    private val activePasses = MutableStateFlow<Set<Job>>(emptySet())

    /**
     * Replays the current identity's mutations across every table.
     *
     * @return The number the server accepted.
     */
    suspend fun syncAllTables(schema: String = supabase.postgrest.config.defaultSchema): Int =
        sync(table = null, schema = schema).syncedCount

    /**
     * Replays the current identity's mutations to [table]. A pass that stops on a failure other than
     * connectivity rethrows it; the mutation stays queued.
     *
     * @return The number the server accepted.
     */
    suspend fun syncTable(schema: String = supabase.postgrest.config.defaultSchema, table: String): Int {
        val report = sync(table = table, schema = schema)
        val stop = report.stop
        if (stop is SyncStop.TransientFailure && !stop.cause.isNetworkOrConnectivityFailure()) throw stop.cause
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
    ): SyncReport {
        if (!awaitSessionSettled()) {
            sdkLogger.info { if (table == null) "Session not settled; deferring mutation replay" else "Session not settled; deferring mutation replay for $table" }
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
        activePasses.value.forEach { pass -> pass.cancel(CancellationException("Local data cleared")) }
        sendLock.withLock { clear() }
    }

    /** What happened to a mutation offered for sending at the head of its queue. */
    sealed interface HeadSendOutcome {

        /** It stays queued unsent: something is ahead of it, it is being sent, or the session moved on. */
        data object NotAtHead : HeadSendOutcome

        /** The server accepted it and it left the queue. */
        data class Sent(val result: PostgrestResult) : HeadSendOutcome

        /**
         * It did not run on the server — no connection, or PostgREST answered `503`/`520` — and stays
         * queued.
         */
        data class StaysQueued(val cause: Exception) : HeadSendOutcome

        /** The server refused it, or answered with another failure; it left the queue. */
        data class Refused(val cause: Exception) : HeadSendOutcome

        /** Local data was cleared while it was on the wire; nothing about it was recorded. */
        data class Cleared(val result: PostgrestResult?, val cause: Exception?) : HeadSendOutcome
    }

    /**
     * Sends queued mutation [mutationId] now if it is at the head of [identity]'s queue, nobody else
     * is sending it, and the session is still [identity]. On success [onAccepted] runs before the
     * mutation leaves the queue; a failure of [onAccepted] is logged, since the server has the write.
     * A caller cancelled while the request is on the wire leaves the mutation queued.
     */
    suspend fun sendAtHead(
        mutationId: Long,
        identity: QueueIdentity,
        send: suspend () -> PostgrestResult,
        onAccepted: suspend (PostgrestResult) -> Unit,
    ): HeadSendOutcome {
        val generation = clearGeneration.value
        val claimed = sendLock.withLock {
            val available = clearGeneration.value == generation &&
                mutationId !in inFlight &&
                currentIdentity() == identity &&
                store.isAtHead(mutationId, identity.storedValue)
            if (available) inFlight += mutationId
            available
        }
        if (!claimed) return HeadSendOutcome.NotAtHead
        try {
            val newlyUnconfirmed = store.markSendUnconfirmed(mutationId)
            val result = when (val sent = sendCapturingEngineFailure(send)) {
                is SendOutcome.Succeeded -> sent.value
                is SendOutcome.Failed -> return sendLock.withLock {
                    headFailureOutcome(mutationId, sent, newlyUnconfirmed, generation)
                }
            }
            if (clearGeneration.value != generation) return HeadSendOutcome.Cleared(result = result, cause = null)
            try {
                onAccepted(result)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                sdkLogger.warning { "Patching the cache after an accepted write failed: ${failure::class.simpleName}" }
            } finally {
                // The server has the write: it leaves the queue whatever happened here, or a pass
                // would send it again.
                withContext(NonCancellable) { completeAcceptedMutation(mutationId) }
            }
            return HeadSendOutcome.Sent(result)
        } finally {
            withContext(NonCancellable) { sendLock.withLock { inFlight -= mutationId } }
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
            clearGeneration.value != generation -> HeadSendOutcome.Cleared(result = null, cause = cause)
            cause.isNetworkOrConnectivityFailure() || cause.httpStatusOrNull() in STAYS_QUEUED_STATUSES -> {
                if (newlyUnconfirmed && (failure.neverReachedServer || cause.httpStatusOrNull() == HTTP_SERVICE_UNAVAILABLE)) {
                    store.clearUnconfirmedSend(mutationId)
                }
                HeadSendOutcome.StaysQueued(cause)
            }
            else -> {
                store.abandonSentMutation(mutationId)
                HeadSendOutcome.Refused(cause)
            }
        }
    }

    /** Removes an accepted mutation, logging rather than failing a write the server applied. */
    private suspend fun completeAcceptedMutation(mutationId: Long) {
        try {
            store.completeSentMutation(mutationId)
        } catch (failure: Exception) {
            sdkLogger.error { "Could not remove an accepted mutation from the queue; it may be sent again (${failure::class.simpleName})" }
        }
    }

    /**
     * Discards the pending mutation [id] of [identity] unsent. A mutation being sent cannot be.
     *
     * @param identity The asking identity, or `null` to skip the ownership check.
     * @return `true` when it was pending, not in flight, and [identity]'s.
     */
    suspend fun discardPendingMutation(id: Long, identity: QueueIdentity?): Boolean = sendLock.withLock {
        if (id in inFlight) false else store.discardPendingMutation(id, identity?.storedValue)
    }

    private suspend fun remainingFor(identity: QueueIdentity?, table: String?): Int {
        if (identity == null) return store.allPendingMutations().count { row -> table == null || row.table == table }
        return store.pendingMutationsOf(identity.storedValue, table)?.size ?: 0
    }

    /** The next step of a pass, chosen under [sendLock]. */
    private sealed interface Claim {
        data class Send(val mutation: MutationRow) : Claim
        data object Skip : Claim
        data class Stop(val stop: SyncStop?) : Claim
    }

    /**
     * Claims [candidate], the next of [identity]'s mutations as the pass loaded them, or says why the
     * pass ends. Nothing earlier may still be in flight, so order holds across concurrent senders.
     * Caller holds [sendLock].
     */
    private suspend fun claim(candidate: MutationRow, identity: QueueIdentity, generation: Long): Claim {
        if (clearGeneration.value != generation) return Claim.Stop(SyncStop.LocalDataCleared)
        val now = currentIdentity() ?: return Claim.Stop(SyncStop.IdentityUnknown)
        if (now != identity) return Claim.Stop(SyncStop.SessionChanged)
        if (inFlight.any { id -> id <= candidate.id }) return Claim.Stop(SyncStop.HeadInFlight)
        if (!store.isPending(candidate.id)) return Claim.Skip
        inFlight += candidate.id
        return Claim.Send(candidate)
    }

    /** One pass. Reads the identity's queue once, then re-checks each mutation as it claims it. */
    private suspend fun runPass(table: String?, schema: String, generation: Long): SyncReport {
        val identity = currentIdentity()
        if (identity == null) {
            sdkLogger.warning { "The session's identity cannot be told; not replaying" }
            return SyncReport(0, emptyList(), remainingFor(null, table), SyncStop.IdentityUnknown)
        }
        val ready = sendLock.withLock {
            if (clearGeneration.value != generation) return SyncReport(0, emptyList(), 0, SyncStop.LocalDataCleared)
            store.retireAgedSyncedMutations()
            store.adoptOwnerlessMutations(identity.storedValue)
        }
        val queue = if (ready) store.pendingMutationsOf(identity.storedValue, table) else null
        if (queue == null) return SyncReport(0, emptyList(), remainingFor(null, table), SyncStop.OwnershipUnreadable)
        if (queue.isEmpty()) return SyncReport(0, emptyList(), 0, null)
        sdkLogger.info { if (table == null) "Syncing ${queue.size} pending mutations" else "Syncing ${queue.size} pending mutations for $table" }

        val tableApis = mutableMapOf<String, PostgrestQueryBuilderWrapper>()
        val syncedTables = mutableSetOf<String>()
        val rejected = mutableListOf<StoredRejection>()
        var syncedCount = 0
        var stop: SyncStop? = null

        for (candidate in queue) {
            val mutation = when (val claim = sendLock.withLock { claim(candidate, identity, generation) }) {
                is Claim.Stop -> {
                    stop = claim.stop
                    break
                }
                Claim.Skip -> continue
                is Claim.Send -> claim.mutation
            }
            // The in-flight mark is released with the outcome recorded, so no other sender can claim
            // the mutation in between; on any other way out, in the finally.
            var released = false
            val (outcome, recorded) = try {
                val outcome = try {
                    val tableApi = tableApis.getOrPut(mutation.table) { postgrestFactory(schema, mutation.table) }
                    replay(mutation, tableApi, identity)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (localFailure: Exception) {
                    // Request failures are outcomes already; this is the store failing around one.
                    sdkLogger.error {
                        "Replay of ${mutation.operation} on ${mutation.table} stopped by a local failure " +
                            "(${localFailure::class.simpleName}); keeping it for a later sync"
                    }
                    ReplayOutcome.Stopped(localFailure)
                }
                sendLock.withLock {
                    inFlight -= mutation.id
                    released = true
                    outcome to if (clearGeneration.value != generation) SyncStop.LocalDataCleared else record(mutation, outcome, rejected, syncedTables)
                }
            } finally {
                if (!released) withContext(NonCancellable) { sendLock.withLock { inFlight -= mutation.id } }
            }
            if (outcome == ReplayOutcome.Synced && recorded == null) syncedCount++
            if (recorded != null) {
                stop = recorded
                break
            }
        }

        // A table nothing caches or observes has no use for its synced mutations; others keep them
        // until every cached query has refreshed past them.
        syncedTables.forEach { syncedTable -> store.clearSyncedMutationsForTable(syncedTable) }

        sdkLogger.info { if (table == null) "Sync complete: $syncedCount mutations synced" else "Sync complete: $syncedCount mutations synced for $table" }
        return SyncReport(
            syncedCount = syncedCount,
            rejected = rejected.map { entry -> entry.toRejectedMutation(store.json, primaryKeys) },
            remainingCount = remainingFor(identity, table),
            stop = stop,
        )
    }

    /**
     * Records the [outcome] of replaying [mutation]. Caller holds [sendLock].
     *
     * @return Why the pass stops here, or `null` to go on.
     */
    private suspend fun record(
        mutation: MutationRow,
        outcome: ReplayOutcome,
        rejected: MutableList<StoredRejection>,
        syncedTables: MutableSet<String>,
    ): SyncStop? = when (outcome) {
        ReplayOutcome.Synced -> {
            store.markMutationSynced(id = mutation.id, table = mutation.table)
            syncedTables += mutation.table
            sdkLogger.debug { "Synced ${mutation.operation} mutation for ${mutation.table}" }
            null
        }

        is ReplayOutcome.Rejected -> try {
            rejected += store.rejectMutation(mutation, outcome.message, outcome.serverError)
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unrecorded: Exception) {
            // The mutation stays queued rather than vanish, and the pass stops here.
            sdkLogger.error {
                "Could not record the rejection of ${mutation.operation} on ${mutation.table}; " +
                    "keeping it queued (${unrecorded::class.simpleName})"
            }
            SyncStop.TransientFailure(unrecorded)
        }

        is ReplayOutcome.Held -> SyncStop.HeldBehindEarlierMutations(mutation.id, outcome.cause)
        is ReplayOutcome.Stopped -> SyncStop.TransientFailure(outcome.cause)
    }

    /** How one mutation's replay ended. */
    private sealed interface ReplayOutcome {
        data object Synced : ReplayOutcome
        data class Rejected(val message: String, val serverError: StoredServerError?) : ReplayOutcome
        data class Held(val cause: Throwable) : ReplayOutcome
        data class Stopped(val cause: Throwable) : ReplayOutcome
    }

    private suspend fun replay(
        mutation: MutationRow,
        tableApi: PostgrestQueryBuilderWrapper,
        identity: QueueIdentity,
    ): ReplayOutcome {
        // Decided before this attempt is recorded: only an earlier attempt can have reached the server.
        val earlierSendUnconfirmed = store.hasUnconfirmedSend(mutation.id)
        return when (val prepared = prepare(mutation, earlierSendUnconfirmed)) {
            is Prepared.Refused -> prepared.outcome
            is Prepared.Ready -> {
                val newlyUnconfirmed = store.markSendUnconfirmed(mutation.id)
                when (val sent = sendCapturingEngineFailure { prepared.request(tableApi) }) {
                    is SendOutcome.Succeeded -> ReplayOutcome.Synced
                    is SendOutcome.Failed -> {
                        if (newlyUnconfirmed && sent.neverReachedServer) store.clearUnconfirmedSend(mutation.id)
                        classify(mutation, sent.thrown, identity)
                    }
                }
            }
        }
    }

    private suspend fun classify(mutation: MutationRow, failure: Exception, identity: QueueIdentity): ReplayOutcome = when {
        failure.isNetworkOrConnectivityFailure() -> {
            sdkLogger.warning { "Network unavailable, pausing sync for ${mutation.table}" }
            ReplayOutcome.Stopped(failure)
        }

        failure.isPermanentMutationFailure() -> {
            val status = failure.httpStatusOrNull()
            val mayDependOnEarlierWrite = status == HTTP_FORBIDDEN || status == HTTP_CONFLICT
            if (mayDependOnEarlierWrite && store.hasEarlierPendingMutation(mutation.id, identity.storedValue)) {
                sdkLogger.warning { "Holding ${mutation.operation} on ${mutation.table} ($status) behind an earlier pending mutation" }
                ReplayOutcome.Held(failure)
            } else {
                val serverError = serverErrorOf(failure)
                sdkLogger.warning { "Permanent failure for ${mutation.operation} on ${mutation.table} ($status); moved to the rejected list" }
                ReplayOutcome.Rejected(message = serverError?.message ?: "The server refused the write", serverError = serverError)
            }
        }

        else -> {
            val status = failure.httpStatusOrNull()
            sdkLogger.warning {
                "Replay of ${mutation.operation} on ${mutation.table} failed " +
                    "(${status ?: failure::class.simpleName}); keeping it for a later sync"
            }
            ReplayOutcome.Stopped(failure)
        }
    }

    /** A mutation ready to send, or refused before any request. */
    private sealed interface Prepared {
        class Ready(val request: suspend (PostgrestQueryBuilderWrapper) -> Unit) : Prepared
        class Refused(val outcome: ReplayOutcome.Rejected) : Prepared
    }

    private fun refuse(mutation: MutationRow, reason: String, message: String): Prepared.Refused {
        sdkLogger.warning { "Queued ${mutation.operation} on ${mutation.table} $reason; moving it to the rejected list" }
        return Prepared.Refused(ReplayOutcome.Rejected(message = message, serverError = null))
    }

    /**
     * The request that replays [mutation], or why it cannot be sent faithfully.
     *
     * @param earlierSendUnconfirmed Whether an earlier send of it may have reached the server.
     */
    private fun prepare(mutation: MutationRow, earlierSendUnconfirmed: Boolean): Prepared {
        val json = store.json
        val filter = decodeOrNull(mutation.filterJson, json)
        return when (mutation.operation) {
            MutationOperation.INSERT -> {
                val body = parseJsonArrayOrNull(json, mutation.bodyJson)
                    ?: return refuse(mutation, BODY_NO_LONGER_PARSES, "The queued body no longer parses")
                Prepared.Ready { tableApi ->
                    applyInsert(tableApi, mutation.table, body, mutation.defaultToNull, filter, earlierSendUnconfirmed)
                }
            }

            MutationOperation.UPSERT -> {
                val body = parseJsonArrayOrNull(json, mutation.bodyJson)
                    ?: return refuse(mutation, BODY_NO_LONGER_PARSES, "The queued body no longer parses")
                Prepared.Ready { tableApi ->
                    applyUpsert(
                        tableApi = tableApi,
                        body = body,
                        onConflict = mutation.onConflict,
                        ignoreDuplicates = mutation.ignoreDuplicates,
                        defaultToNull = mutation.defaultToNull,
                        filter = filter,
                    )
                }
            }

            MutationOperation.UPDATE -> {
                val body = parseJsonElementOrNull(json, mutation.bodyJson)
                    ?: return refuse(mutation, BODY_NO_LONGER_PARSES, "The queued body no longer parses")
                val replayableFilter = replayableFilterOrNull(filter)
                    ?: return refuseUnreplayableFilter(mutation, filter)
                Prepared.Ready { tableApi ->
                    applyUpdate(
                        tableApi = tableApi,
                        body = rebaseDeltasAgainstServer(
                            tableApi = tableApi,
                            table = mutation.table,
                            body = body,
                            filter = replayableFilter,
                            deltaColumns = decodeNumericDeltaColumns(mutation.deltaJson, json),
                        ),
                        filter = replayableFilter,
                    )
                }
            }

            MutationOperation.DELETE -> {
                val replayableFilter = replayableFilterOrNull(filter)
                    ?: return refuseUnreplayableFilter(mutation, filter)
                Prepared.Ready { tableApi -> applyDelete(tableApi, replayableFilter) }
            }

            else -> refuse(mutation, "has an unknown operation", "The queued operation is not recognised")
        }
    }

    /**
     * [filter] when replaying it reaches exactly the rows it captured. A missing or lossy filter would
     * reach more rows than the caller targeted, so it is never sent.
     */
    private fun replayableFilterOrNull(filter: FilterDescriptor?): FilterDescriptor? =
        filter?.takeIf { descriptor -> FilterEngine.replaysFully(descriptor) }

    private fun refuseUnreplayableFilter(mutation: MutationRow, filter: FilterDescriptor?): Prepared.Refused =
        if (filter == null) {
            refuse(mutation, "has no filter", "An ${mutation.operation} without a filter is never replayed")
        } else {
            refuse(mutation, "has a filter that no longer replays in full", "The queued filter no longer replays in full")
        }

    private fun parseJsonArrayOrNull(json: Json, raw: String): JsonArray? = try {
        json.parseToJsonElement(raw) as? JsonArray
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseJsonElementOrNull(json: Json, raw: String): JsonElement? = try {
        json.parseToJsonElement(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    /* ───────────────────────────── Remote calls ───────────────────────────── */

    /**
     * Replays an INSERT. One that may already be on the server, and whose rows all carry the table's
     * primary key, goes out as an upsert on that key that ignores duplicates and counts the rows it
     * wrote; every other INSERT goes out as sent, so a duplicate key is refused by the server. An
     * upsert with no matching unique constraint (`42P10`) is sent again as the plain INSERT.
     */
    private suspend fun applyInsert(
        tableApi: PostgrestQueryBuilderWrapper,
        table: String,
        body: JsonArray,
        defaultToNull: Boolean?,
        filter: FilterDescriptor?,
        earlierSendUnconfirmed: Boolean,
    ) {
        val keyColumns = primaryKeys.of(table)
        if (!earlierSendUnconfirmed || !everyRowCarriesPrimaryKey(body, keyColumns)) {
            insertAsSent(tableApi, body, defaultToNull, filter)
            return
        }
        val result = try {
            tableApi.upsert(body) {
                this.onConflict = keyColumns.joinToString(",")
                this.ignoreDuplicates = true
                this.defaultToNull = defaultToNull ?: true
                count(Count.EXACT)
                if (filter != null) applyTo(this, filter)
            }
        } catch (failure: PostgrestRestException) {
            if (failure.code != NO_MATCHING_CONFLICT_TARGET) throw failure
            sdkLogger.warning { "Replayed INSERT on $table has no unique key to match; sending it as a plain INSERT" }
            insertAsSent(tableApi, body, defaultToNull, filter)
            return
        }
        val written = result.countOrNull()
        if (written != null && written < body.size) {
            sdkLogger.info { "Replayed INSERT on $table: ${body.size - written} of ${body.size} rows were already on the server, taken as its earlier send" }
        }
    }

    private suspend fun insertAsSent(tableApi: PostgrestQueryBuilderWrapper, body: JsonArray, defaultToNull: Boolean?, filter: FilterDescriptor?) {
        tableApi.insert(body) {
            this.defaultToNull = defaultToNull ?: true
            if (filter != null) applyTo(this, filter)
        }
    }

    private suspend fun applyUpsert(
        tableApi: PostgrestQueryBuilderWrapper,
        body: JsonArray,
        onConflict: String?,
        ignoreDuplicates: Boolean?,
        defaultToNull: Boolean?,
        filter: FilterDescriptor? = null
    ) {
        tableApi.upsert(body) {
            this.onConflict = onConflict
            this.ignoreDuplicates = ignoreDuplicates ?: false
            this.defaultToNull = defaultToNull ?: true
            if (filter != null) applyTo(this, filter)
        }
    }

    /**
     * Rebases [body]'s delta-declared columns onto the value the server holds now, read with the
     * UPDATE's own filter (see [NumericDeltaColumn]). Applies only when the filter matches exactly one
     * row; otherwise [body] is sent as it is. Read failures propagate, so the mutation stays queued.
     */
    private suspend fun rebaseDeltasAgainstServer(
        tableApi: PostgrestQueryBuilderWrapper,
        table: String,
        body: JsonElement,
        filter: FilterDescriptor,
        deltaColumns: List<NumericDeltaColumn>,
    ): JsonElement {
        if (deltaColumns.isEmpty()) return body

        val current = tableApi.select(Columns.ALL) { applyTo(this, filter) }
        val rows = parseJsonArrayOrNull(store.json, current.data)
        if (rows == null || rows.size != 1) {
            sdkLogger.warning {
                "delta-encoded UPDATE on $table re-read ${rows?.size ?: "an unusable payload"}; replaying the absolute body"
            }
            return body
        }
        val row = rows.single() as? JsonObject ?: return body
        return rebaseNumericDeltas(body = body, serverRow = row, deltaColumns = deltaColumns)
    }

    private suspend fun applyUpdate(tableApi: PostgrestQueryBuilderWrapper, body: JsonElement, filter: FilterDescriptor) {
        tableApi.update(body) { applyTo(this, filter) }
    }

    private suspend fun applyDelete(tableApi: PostgrestQueryBuilderWrapper, filter: FilterDescriptor) {
        tableApi.delete { applyTo(this, filter) }
    }

    /** The server's refusal as data: PostgREST's fields, or the bare status of another HTTP failure. */
    private fun serverErrorOf(failure: Throwable): StoredServerError? {
        var current: Throwable? = failure
        while (current != null) {
            when (current) {
                is PostgrestRestException -> return StoredServerError(
                    status = current.statusCode,
                    code = current.code,
                    message = current.error,
                    details = current.details?.let { details ->
                        (details as? JsonPrimitive)?.takeIf { it.isString }?.content ?: details.toString()
                    },
                    hint = current.hint,
                )

                is ResponseException -> return StoredServerError(
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

        /** Statuses PostgREST answers when the write never ran: it could not reach the database. */
        val STAYS_QUEUED_STATUSES = setOf(HTTP_SERVICE_UNAVAILABLE, 520)

        /** Postgres: `ON CONFLICT` names columns with no unique or exclusion constraint. */
        const val NO_MATCHING_CONFLICT_TARGET = "42P10"
        const val BODY_NO_LONGER_PARSES = "has a body that no longer parses"
    }
}
