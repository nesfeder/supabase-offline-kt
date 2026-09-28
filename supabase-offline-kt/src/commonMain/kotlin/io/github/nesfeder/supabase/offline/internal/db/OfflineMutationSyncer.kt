package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.NumericDeltaColumn
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.internal.outbox.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.outbox.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.outbox.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.outbox.SyncReport
import io.github.nesfeder.supabase.offline.internal.outbox.SyncStop
import io.github.nesfeder.supabase.offline.internal.outbox.currentReplayIdentity
import io.github.nesfeder.supabase.offline.internal.outbox.everyRowCarriesPrimaryKey
import io.github.nesfeder.supabase.offline.internal.outbox.toRejectedMutation
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine.applyTo
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine.decodeOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.PostgrestQueryBuilderWrapper
import io.github.nesfeder.supabase.offline.internal.postgrest.PostgrestQueryBuilderWrapperImpl
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
 * Sends the queue of writes from [OfflineStore] to Supabase: replay passes, and the immediate send of
 * a write that is at the head of the queue when it is made.
 *
 * ### One queue per identity, in insertion order
 * Each write records the identity that made it. A pass replays only the current identity's writes,
 * across every table, in the order they were made (the queue row's id); another identity's writes
 * neither block it nor are sent by it. The identity is checked again before **every** request, so a
 * sign-out or an account switch stops a pass before its next request.
 *
 * A pass stops at the first write that cannot go through for a reason that may pass — no
 * connectivity, a `401`, `408`, `429` or `5xx`, or an unexpected error — so a later write never
 * overtakes an earlier one. Nothing transient ever removes a write. One pass is one attempt at the
 * head.
 *
 * ### Rejections
 * A write the server refuses for good (any other `4xx`) moves to the persisted rejected list, and the
 * pass continues. A `403` or `409` on a write that has an earlier write of the same identity pending,
 * in any table, is *held* instead. A write the engine cannot send faithfully is moved to the rejected
 * list without a request.
 *
 * ### One sender at a time per write
 * Choosing the next write and recording its outcome happen under a short send lock; the request
 * itself does not hold it. A write being sent is marked in flight, so a pass never sends a write the
 * builder is sending at the head (it stops there instead), and two passes never send the same write.
 *
 * ### Teardown
 * [clearLocalData] cancels passes in flight and wipes under the send lock; an outcome that arrives
 * afterwards is discarded.
 *
 * ### Idempotence
 * An INSERT whose rows all carry the table's primary key replays as an upsert that ignores
 * duplicates. UPSERT, absolute UPDATE and DELETE are idempotent as sent. A keyless INSERT and an
 * UPDATE with numeric delta columns are not: replaying one whose response was lost applies it twice.
 */
internal class OfflineMutationSyncer(
    private val store: OfflineStore,
    private val supabase: SupabaseClient,
    private val sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    private val postgrestFactory: (schema: String, table: String) -> PostgrestQueryBuilderWrapper =
        { schema, table ->
            PostgrestQueryBuilderWrapperImpl(
                supabase.postgrest.from(schema, table)
            )
        },
    /**
     * Gate awaited before a pass: the session must be settled (signed in, or signed out) so the
     * requests go out as a definite identity. `false` means it did not settle within the bounded
     * wait: the pass sends nothing and reports [SyncStop.SessionNotReady]. Injectable for tests;
     * defaults to [awaitReplaySafeSession] on [supabase], which fails open without the Auth plugin.
     */
    private val awaitSessionSettled: suspend () -> Boolean = { supabase.awaitReplaySafeSession() },
    /**
     * The identity a request sent now would go out as, or `null` when that cannot be told (the
     * session restoring or refreshing). Read at the start of a pass and again before every request.
     * Injectable for tests.
     */
    private val currentIdentity: suspend () -> QueueIdentity? = { supabase.currentReplayIdentity() },
    /** Where the engine learns each table's primary key. */
    private val primaryKeys: PrimaryKeys = DefaultPrimaryKeys,
) {

    /** Guards choosing a write to send and recording its outcome. Never held across a request. */
    private val sendLock = Mutex()

    /** Ids of the writes being sent right now, by a pass or at the head. Guarded by [sendLock]. */
    private val inFlight = mutableSetOf<Long>()

    /** Bumped by [clearLocalData]; an outcome recorded after it moved is discarded. */
    private val clearGeneration = MutableStateFlow(0L)

    /** Passes in flight, so [clearLocalData] can cancel them. */
    private val activeSweeps = MutableStateFlow<Set<Job>>(emptySet())

    /**
     * Replays the current identity's writes, across every table.
     *
     * @return The number of writes the server accepted.
     */
    suspend fun syncAllTables(
        schema: String = supabase.postgrest.config.defaultSchema
    ): Int = sync(table = null, schema = schema).syncedCount

    /**
     * Replays the current identity's writes to [table], in insertion order. A `403` or `409` on a
     * write that has an earlier write of the same identity pending in another table is held.
     *
     * A pass that stops on a server-side failure — a `401`, `408`, `429`, `5xx` or an unexpected
     * error — rethrows it, as this entry point always has; the write stays queued. A connectivity
     * failure returns normally.
     *
     * @return The number of writes the server accepted.
     */
    suspend fun syncTable(
        schema: String = supabase.postgrest.config.defaultSchema,
        table: String
    ): Int {
        val report = sync(table = table, schema = schema)
        val stop = report.stop
        if (stop is SyncStop.TransientFailure && !stop.cause.isNetworkOrConnectivityFailure()) {
            throw stop.cause
        }
        return report.syncedCount
    }

    /**
     * Runs one replay pass for the current identity and reports what it did. Returns promptly when
     * offline: the first request fails for connectivity and the pass stops there.
     *
     * @param table The table to replay, or `null` for every table.
     * @param schema The schema the requests address.
     * @return What the pass synced, rejected and left pending, and why it stopped early if it did.
     */
    suspend fun sync(
        table: String? = null,
        schema: String = supabase.postgrest.config.defaultSchema,
    ): SyncReport {
        if (!awaitSessionSettled()) {
            sdkLogger.info {
                if (table == null) "Session not settled; deferring mutation replay"
                else "Session not settled; deferring mutation replay for $table"
            }
            return SyncReport(0, emptyList(), remainingFor(null, table), SyncStop.SessionNotReady)
        }
        val generation = clearGeneration.value
        return coroutineScope {
            val sweep = async { runPass(table, schema, generation) }
            activeSweeps.update { sweeps -> sweeps + sweep }
            try {
                sweep.await()
            } catch (cancellation: CancellationException) {
                // Rethrows when the caller itself was cancelled; otherwise only the pass was, by
                // clearLocalData, and there is nothing left to report on.
                currentCoroutineContext().ensureActive()
                SyncReport(0, emptyList(), 0, SyncStop.LocalDataCleared)
            } finally {
                activeSweeps.update { sweeps -> sweeps - sweep }
            }
        }
    }

    /**
     * Wipes local data with [clear] once no pass can send anything more: passes in flight are
     * cancelled, and [clear] runs under the send lock. An outcome of a request already on the wire
     * is discarded when it arrives.
     *
     * @param clear The wipe.
     */
    suspend fun clearLocalData(clear: suspend () -> Unit) {
        clearGeneration.update { generation -> generation + 1 }
        activeSweeps.value.forEach { sweep -> sweep.cancel(CancellationException("Local data cleared")) }
        sendLock.withLock { clear() }
    }

    /** What happened to a write offered for sending at the head of the queue. */
    sealed interface HeadSendOutcome {

        /**
         * It stays queued unsent: something of the same identity is pending ahead of it, it is
         * already being sent, or the session is no longer (or not yet) the identity that made it.
         */
        data object NotAtHead : HeadSendOutcome

        /**
         * The server accepted it; it has left the queue.
         *
         * @property result The server's response.
         */
        data class Sent(val result: PostgrestResult) : HeadSendOutcome

        /**
         * It could not reach the server; it stays queued.
         *
         * @property cause The connectivity failure.
         */
        data class Offline(val cause: Exception) : HeadSendOutcome

        /**
         * The server refused it, or a failure other than connectivity ended the request; it has left
         * the queue, and the caller reports the failure.
         *
         * @property cause The failure.
         */
        data class Refused(val cause: Exception) : HeadSendOutcome

        /**
         * Local data was cleared while it was being sent; nothing about it was recorded.
         *
         * @property result The server's response, when it accepted the write.
         * @property cause The failure, when it did not.
         */
        data class Cleared(val result: PostgrestResult?, val cause: Exception?) : HeadSendOutcome
    }

    /**
     * Sends the queued write [mutationId] now, if it is at the head of [identity]'s queue, nobody
     * else is sending it, and the session is still [identity] — the path every `QueueIfOffline`
     * write takes right after it is queued.
     *
     * While the write is being sent it is marked in flight, so no pass sends it too. On success
     * [onAccepted] runs (the caller patches the cache with the response) before the write leaves the
     * queue, still marked in flight: removing it first would drop it from the overlay for one
     * emission before the patched cache shows it.
     *
     * A connectivity failure leaves it queued. Any other failure — a refusal, or a `5xx` — removes
     * it and is handed back for the caller to throw, as a write sent online always has been.
     *
     * @param mutationId The queued write.
     * @param identity The identity that made it.
     * @param send Sends it and returns the server's response.
     * @param onAccepted Runs with the response once the server accepted it.
     * @return What happened.
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
            val result = try {
                send()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return sendLock.withLock {
                    when {
                        clearGeneration.value != generation -> HeadSendOutcome.Cleared(result = null, cause = failure)
                        failure.isNetworkOrConnectivityFailure() -> HeadSendOutcome.Offline(failure)
                        else -> {
                            store.abandonSentMutation(mutationId)
                            HeadSendOutcome.Refused(failure)
                        }
                    }
                }
            }
            if (clearGeneration.value != generation) return HeadSendOutcome.Cleared(result = result, cause = null)
            onAccepted(result)
            store.completeSentMutation(mutationId)
            return HeadSendOutcome.Sent(result)
        } finally {
            withContext(NonCancellable) { sendLock.withLock { inFlight -= mutationId } }
        }
    }

    /**
     * Discards the pending write [id] of [identity] without sending it. A write being sent cannot be
     * discarded.
     *
     * @param id The queued write.
     * @param identity The asking identity, or `null` to skip the ownership check.
     * @return `true` when it was pending, not in flight, and [identity]'s.
     */
    suspend fun discardPendingMutation(id: Long, identity: QueueIdentity?): Boolean = sendLock.withLock {
        if (id in inFlight) false else store.discardPendingMutation(id, identity?.storedValue)
    }

    private suspend fun remainingFor(identity: QueueIdentity?, table: String?): Int {
        if (identity == null) {
            return store.allPendingMutations().count { row -> table == null || row.table == table }
        }
        return store.pendingMutationsOf(identity.storedValue, table)?.size ?: 0
    }

    /** The next step of a pass, chosen under [sendLock]. */
    private sealed interface Claim {
        data class Send(val mutation: MutationRow) : Claim
        data class Stop(val stop: SyncStop?) : Claim
    }

    /** Chooses the next write of [identity] to send, or why the pass ends. Caller holds [sendLock]. */
    private suspend fun claimNext(identity: QueueIdentity, table: String?, generation: Long): Claim {
        if (clearGeneration.value != generation) return Claim.Stop(SyncStop.LocalDataCleared)
        val now = currentIdentity() ?: return Claim.Stop(SyncStop.IdentityUnknown)
        if (now != identity) return Claim.Stop(SyncStop.SessionChanged)
        val pending = store.pendingMutationsOf(identity.storedValue, table)
            ?: return Claim.Stop(SyncStop.OwnershipUnreadable)
        val next = pending.firstOrNull() ?: return Claim.Stop(null)
        if (next.id in inFlight) return Claim.Stop(SyncStop.HeadInFlight)
        inFlight += next.id
        return Claim.Send(next)
    }

    /** One pass. */
    private suspend fun runPass(table: String?, schema: String, generation: Long): SyncReport {
        val identity = currentIdentity()
        if (identity == null) {
            sdkLogger.warning { "The session's identity cannot be told; not replaying" }
            return SyncReport(0, emptyList(), remainingFor(null, table), SyncStop.IdentityUnknown)
        }
        val ready = sendLock.withLock {
            if (clearGeneration.value != generation) return SyncReport(0, emptyList(), 0, SyncStop.LocalDataCleared)
            store.retireAgedSyncedMutations()
            store.adoptUnownedMutations(identity.storedValue)
        }
        if (!ready) {
            return SyncReport(0, emptyList(), remainingFor(null, table), SyncStop.OwnershipUnreadable)
        }

        val pendingAtStart = remainingFor(identity, table)
        if (pendingAtStart == 0) return SyncReport(0, emptyList(), 0, null)
        sdkLogger.info {
            if (table == null) "Syncing $pendingAtStart pending mutations"
            else "Syncing $pendingAtStart pending mutations for $table"
        }

        val tableApis = mutableMapOf<String, PostgrestQueryBuilderWrapper>()
        val syncedTables = mutableSetOf<String>()
        val rejected = mutableListOf<StoredRejection>()
        var syncedCount = 0
        var stop: SyncStop?

        while (true) {
            val mutation = when (val claim = sendLock.withLock { claimNext(identity, table, generation) }) {
                is Claim.Stop -> {
                    stop = claim.stop
                    break
                }
                is Claim.Send -> claim.mutation
            }
            val outcome = try {
                val tableApi = tableApis.getOrPut(mutation.table) { postgrestFactory(schema, mutation.table) }
                replay(mutation, tableApi, identity)
            } catch (cancellation: CancellationException) {
                withContext(NonCancellable) { sendLock.withLock { inFlight -= mutation.id } }
                throw cancellation
            }
            val recorded = sendLock.withLock {
                inFlight -= mutation.id
                if (clearGeneration.value != generation) return@withLock SyncStop.LocalDataCleared
                record(mutation, outcome, rejected, syncedTables)
            }
            when (outcome) {
                ReplayOutcome.Synced -> if (recorded == null) syncedCount++
                else -> Unit
            }
            if (recorded != null) {
                stop = recorded
                break
            }
        }

        // A table nothing caches or observes has no use for its synced writes; others keep them
        // until every cached query has refreshed past them.
        syncedTables.forEach { syncedTable -> store.clearSyncedMutationsForTable(syncedTable) }

        sdkLogger.info {
            if (table == null) "Sync complete: $syncedCount mutations synced"
            else "Sync complete: $syncedCount mutations synced for $table"
        }
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
            // The write stays queued rather than vanish, and the pass stops here.
            sdkLogger.error {
                "Could not record the rejection of ${mutation.operation} on ${mutation.table}; " +
                    "keeping it queued (${unrecorded::class.simpleName})"
            }
            SyncStop.TransientFailure(unrecorded)
        }

        is ReplayOutcome.Held -> SyncStop.HeldBehindEarlierMutations(mutation.id, outcome.cause)
        is ReplayOutcome.Stopped -> SyncStop.TransientFailure(outcome.cause)
    }

    /** How one write's replay ended. */
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
    ): ReplayOutcome = when (val prepared = prepare(mutation)) {
        is Prepared.Refused -> prepared.outcome
        is Prepared.Ready -> try {
            prepared.request(tableApi)
            ReplayOutcome.Synced
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            classify(mutation, failure, identity)
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
                sdkLogger.warning {
                    "Holding ${mutation.operation} on ${mutation.table} ($status) behind an earlier pending mutation"
                }
                ReplayOutcome.Held(failure)
            } else {
                val serverError = serverErrorOf(failure)
                sdkLogger.warning {
                    "Permanent failure for ${mutation.operation} on ${mutation.table} ($status); " +
                        "moved to the rejected list"
                }
                ReplayOutcome.Rejected(
                    message = serverError?.message ?: "The server refused the write",
                    serverError = serverError,
                )
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

    /** A write ready to send, or refused before any request. */
    private sealed interface Prepared {
        class Ready(val request: suspend (PostgrestQueryBuilderWrapper) -> Unit) : Prepared
        class Refused(val outcome: ReplayOutcome.Rejected) : Prepared
    }

    private fun refuse(mutation: MutationRow, reason: String, message: String): Prepared.Refused {
        sdkLogger.warning { "Queued ${mutation.operation} on ${mutation.table} $reason; moving it to the rejected list" }
        return Prepared.Refused(ReplayOutcome.Rejected(message = message, serverError = null))
    }

    private fun prepare(mutation: MutationRow): Prepared {
        val json = store.json
        val filter = decodeOrNull(mutation.filterJson, json)
        return when (mutation.operation) {
            MutationOperation.INSERT -> {
                val body = parseJsonArrayOrNull(json, mutation.bodyJson)
                    ?: return refuse(mutation, BODY_NO_LONGER_PARSES, "The queued body no longer parses")
                Prepared.Ready { tableApi ->
                    applyInsert(tableApi, mutation.table, body, mutation.defaultToNull, filter)
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
     * [filter] when replaying it reaches exactly the rows it captured. An UPDATE or DELETE with no
     * filter would rewrite or remove every row RLS lets the user touch; one whose filter loses a
     * condition on replay would reach more rows than the caller targeted. Neither is ever sent.
     */
    private fun replayableFilterOrNull(filter: FilterDescriptor?): FilterDescriptor? =
        filter?.takeIf { descriptor -> FilterEngine.replaysFully(descriptor) }

    private fun refuseUnreplayableFilter(mutation: MutationRow, filter: FilterDescriptor?): Prepared.Refused =
        if (filter == null) {
            refuse(mutation, "has no filter", "An ${mutation.operation} without a filter is never replayed")
        } else {
            refuse(
                mutation,
                "has a filter that no longer replays in full",
                "The queued filter no longer replays in full",
            )
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
     * Apply an INSERT mutation online. When every row carries the table's primary key, it goes out
     * as an upsert on that key that ignores duplicates, so replaying a write that already reached
     * the server is a no-op.
     */
    private suspend fun applyInsert(
        tableApi: PostgrestQueryBuilderWrapper,
        table: String,
        body: JsonArray,
        defaultToNull: Boolean?,
        filter: FilterDescriptor?
    ) {
        val keyColumns = primaryKeys.of(table)
        if (everyRowCarriesPrimaryKey(body, keyColumns)) {
            tableApi.upsert(body) {
                this.onConflict = keyColumns.joinToString(",")
                this.ignoreDuplicates = true
                this.defaultToNull = defaultToNull ?: true
                if (filter != null) applyTo(this, filter)
            }
        } else {
            tableApi.insert(body) {
                this.defaultToNull = defaultToNull ?: true
                if (filter != null) applyTo(this, filter)
            }
        }
    }

    /**
     * Apply an UPSERT mutation online.
     */
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
     * Re-reads the row this UPDATE targets and rebases its delta-declared columns onto what the
     * server holds *now* — the read-before-write that turns a queued absolute into the caller's
     * actual intent (see [NumericDeltaColumn]).
     *
     * The read reuses the mutation's own stored filter, so it resolves exactly the rows the UPDATE
     * is about to write, and it is only issued when the mutation declares delta columns: an
     * ordinary queued UPDATE keeps its single-round-trip replay.
     *
     * Rebasing needs an unambiguous "current value", so it applies only when the filter resolves
     * to **exactly one** row. Anything else falls back to the stored absolute body — the
     * pre-delta behaviour:
     * - **no rows**: the target is gone (deleted elsewhere); the UPDATE will match nothing either
     *   way, so there is nothing to preserve.
     * - **several rows**: per-row bases would need per-row UPDATEs, which is a different mutation
     *   than the one queued. Logged, because a delta-declared UPDATE is expected to be row-scoped.
     *
     * Read failures are **not** swallowed: they propagate into the pass's classifier, so a
     * connectivity failure stops the pass with the mutation still pending (correct — the device
     * went offline again mid-drain) rather than writing a stale absolute value.
     *
     * The rebase narrows the clobber window; it does not close it. A change landing between this
     * read and the PATCH that follows it is still overwritten — the write is not atomic, and
     * PostgREST cannot express `col = col + x`. What changes is the size of the window: from
     * "however long the mutation sat in the queue", which can be days offline, to one round trip.
     * Closing it outright needs the increment to happen server-side (an RPC, or a trigger owning
     * the column), which is a larger change than the queue can make on its own.
     *
     * @param tableApi PostgREST handle for the mutation's table.
     * @param table Table name, for log lines.
     * @param body The stored UPDATE body.
     * @param filter The mutation's captured filter, already proven fully replayable.
     * @param deltaColumns Declarations decoded from the mutation.
     * @return The body to send — rebased, or [body] unchanged.
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
                "delta-encoded UPDATE on $table re-read ${rows?.size ?: "an unusable payload"}; " +
                    "replaying the absolute body"
            }
            return body
        }
        val row = rows.single() as? JsonObject ?: return body
        return rebaseNumericDeltas(body = body, serverRow = row, deltaColumns = deltaColumns)
    }

    /**
     * Apply an UPDATE mutation online with its stored [FilterDescriptor].
     *
     * The filter is re-applied to a fresh [PostgrestRequestBuilder] via [FilterEngine],
     * so we target the same rows that the offline UPDATE intended to modify.
     */
    private suspend fun applyUpdate(
        tableApi: PostgrestQueryBuilderWrapper,
        body: JsonElement,
        filter: FilterDescriptor
    ) {
        tableApi.update(body) {
            applyTo(this, filter)
        }
    }

    /**
     * Apply a DELETE mutation online with its stored [FilterDescriptor].
     */
    private suspend fun applyDelete(
        tableApi: PostgrestQueryBuilderWrapper,
        filter: FilterDescriptor
    ) {
        tableApi.delete {
            applyTo(this, filter)
        }
    }

    /**
     * The server's refusal as data: PostgREST's fields from a [PostgrestRestException], or the bare
     * status from any other HTTP failure in [failure]'s cause chain.
     */
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
        const val BODY_NO_LONGER_PARSES = "has a body that no longer parses"
    }
}
