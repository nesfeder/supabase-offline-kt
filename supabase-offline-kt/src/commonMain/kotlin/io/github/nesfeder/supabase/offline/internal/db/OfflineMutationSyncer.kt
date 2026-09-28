package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.nesfeder.supabase.offline.NumericDeltaColumn
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.internal.outbox.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.outbox.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.outbox.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.outbox.SyncReport
import io.github.nesfeder.supabase.offline.internal.outbox.SyncStop
import io.github.nesfeder.supabase.offline.internal.outbox.currentQueueIdentity
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Replays the queue of offline writes from [OfflineStore] against Supabase.
 *
 * ### One global queue
 * Every table's writes form one queue, replayed in insertion order (the queue row's id). A pass
 * replays it front to back and stops at the first write that cannot go through for a reason that
 * may pass — no connectivity, a `401`, `408`, `429` or `5xx`, or an unexpected error — so a later
 * write never overtakes an earlier one, in its own table or in a table it depends on through a
 * foreign key or a row-level-security policy. Nothing transient ever removes a write.
 *
 * ### Rejections
 * A write the server refuses for good (any other `4xx`) moves to the persisted rejected list with
 * the server's status, code, message, details and hint, and the pass continues. A `403` or `409` on
 * a write that has an earlier write still pending, in any table, is *held* instead: the earlier write
 * may be the parent row or the row a policy checks. A write the engine cannot send faithfully — a
 * body that no longer parses, an UPDATE or DELETE whose filter is missing or no longer replays in
 * full — is moved to the rejected list without a request.
 *
 * ### Identity
 * The queue records the identity that queued into it. A pass made under another identity sends
 * nothing and reports [SyncStop.QueueOwnedByAnotherIdentity].
 *
 * ### Teardown
 * [clearLocalData] cancels a pass in flight and takes the sync lock, so no request goes out once it
 * has begun.
 *
 * ### Idempotence
 * An INSERT whose rows all carry the table's primary key replays as an upsert that ignores
 * duplicates: a replay of a write that reached the server but whose response was lost is then a
 * no-op instead of a duplicate row or a `409`.
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
     * Gate awaited before any replay reaches the wire. Requests carry the user JWT only while
     * the session status is `Authenticated` — during cold-start restore, after sign-out, and in
     * the post-offline `RefreshFailure` window they go out on the anon key, so RLS 403s every
     * owner-scoped write. `false` means no authenticated session arrived within the bounded wait:
     * the pass then sends nothing and reports [SyncStop.SessionNotReady]. Injectable for tests;
     * defaults to [awaitReplaySafeSession] on [supabase], which fails open for clients without the
     * Auth plugin.
     */
    private val awaitSessionSettled: suspend () -> Boolean = { supabase.awaitReplaySafeSession() },
    /**
     * The identity a pass would replay as, compared with the queue's recorded owner; `null` when it
     * cannot be told, in which case nothing is sent. Injectable for tests.
     */
    private val currentIdentity: suspend () -> QueueIdentity? = { supabase.currentQueueIdentity() },
    /** Where the engine learns each table's primary key. */
    private val primaryKeys: PrimaryKeys = DefaultPrimaryKeys,
) {

    /**
     * Serializes every pass, so two concurrent triggers cannot replay the same write twice, and is
     * taken by [clearLocalData] so a wipe never interleaves with a pass.
     */
    private val syncMutex = Mutex()

    /** Bumped by [clearLocalData]; a pass that sees it move stops before its next request. */
    private val clearGeneration = MutableStateFlow(0L)

    /** Passes in flight, so [clearLocalData] can cancel them. */
    private val activeSweeps = MutableStateFlow<Set<Job>>(emptySet())

    /**
     * Replays the whole queue.
     *
     * @return The number of writes the server accepted.
     */
    suspend fun syncAllTables(
        schema: String = supabase.postgrest.config.defaultSchema
    ): Int = sync(table = null, schema = schema).syncedCount

    /**
     * Replays [table]'s writes, in insertion order. A `403` or `409` on a write that has an earlier
     * write pending in another table is held, not rejected.
     *
     * Unlike [syncAllTables], a pass that stops on a server-side failure — a `401`, `408`, `429`,
     * `5xx` or an unexpected error — rethrows it, as this entry point always has; the write stays
     * queued. A connectivity failure returns normally.
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
     * Runs one replay pass and reports what it did. Returns promptly when offline: the first request
     * fails for connectivity and the pass stops there.
     *
     * @param table The table to replay, or `null` for the whole queue.
     * @param schema The schema the requests address.
     * @return What the pass synced, rejected and left pending, and why it stopped early if it did.
     */
    suspend fun sync(
        table: String? = null,
        schema: String = supabase.postgrest.config.defaultSchema,
    ): SyncReport {
        if (!awaitSessionSettled()) {
            sdkLogger.info {
                if (table == null) "No authenticated session; deferring mutation replay"
                else "No authenticated session; deferring mutation replay for $table"
            }
            return SyncReport(0, emptyList(), remainingCount(table), SyncStop.SessionNotReady)
        }
        val generation = clearGeneration.value
        return coroutineScope {
            val sweep = async {
                syncMutex.withLock {
                    if (clearGeneration.value != generation) {
                        SyncReport(0, emptyList(), remainingCount(table), SyncStop.LocalDataCleared)
                    } else {
                        sweepLocked(table, schema, generation)
                    }
                }
            }
            activeSweeps.update { sweeps -> sweeps + sweep }
            try {
                sweep.await()
            } catch (cancellation: CancellationException) {
                // Rethrows when the caller itself was cancelled; otherwise only the pass was, by
                // clearLocalData, and there is nothing left to report on.
                currentCoroutineContext().ensureActive()
                SyncReport(0, emptyList(), remainingCount(table), SyncStop.LocalDataCleared)
            } finally {
                activeSweeps.update { sweeps -> sweeps - sweep }
            }
        }
    }

    /**
     * Wipes local data with [clear] once no pass can send anything more: a pass in flight is
     * cancelled, and [clear] runs under the sync lock.
     *
     * @param clear The wipe.
     */
    suspend fun clearLocalData(clear: suspend () -> Unit) {
        clearGeneration.update { generation -> generation + 1 }
        activeSweeps.value.forEach { sweep -> sweep.cancel(CancellationException("Local data cleared")) }
        syncMutex.withLock { clear() }
    }

    private suspend fun remainingCount(table: String?): Int =
        if (table == null) store.pendingMutationCount() else store.pendingMutationCountForTable(table)

    /** The pass itself. Must only be called while [syncMutex] is held. */
    private suspend fun sweepLocked(table: String?, schema: String, generation: Long): SyncReport {
        val identity = currentIdentity()
        val mayReplay = try {
            identity != null && store.mayReplayAs(identity.storedValue)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unreadableOwner: Exception) {
            // The recorded owner cannot be decoded. Sending nothing is the only safe answer.
            sdkLogger.error { "The queue owner cannot be read; not replaying (${unreadableOwner::class.simpleName})" }
            return SyncReport(0, emptyList(), remainingCount(table), SyncStop.TransientFailure(unreadableOwner))
        }
        if (!mayReplay) {
            sdkLogger.warning {
                "Pending mutations were queued under another identity than the current session; not replaying"
            }
            return SyncReport(0, emptyList(), remainingCount(table), SyncStop.QueueOwnedByAnotherIdentity)
        }

        val pending = if (table == null) store.allPendingMutations() else store.pendingMutationsForTable(table)
        if (pending.isEmpty()) return SyncReport(0, emptyList(), 0, null)

        sdkLogger.info {
            if (table == null) "Syncing ${pending.size} pending mutations"
            else "Syncing ${pending.size} pending mutations for $table"
        }

        val tableApis = mutableMapOf<String, PostgrestQueryBuilderWrapper>()
        val syncedTables = mutableSetOf<String>()
        val rejected = mutableListOf<StoredRejection>()
        var syncedCount = 0
        var stop: SyncStop? = null

        for (mutation in pending) {
            if (clearGeneration.value != generation) {
                stop = SyncStop.LocalDataCleared
                break
            }
            val tableApi = tableApis.getOrPut(mutation.table) { postgrestFactory(schema, mutation.table) }
            when (val outcome = replay(mutation, tableApi)) {
                ReplayOutcome.Synced -> {
                    store.markMutationSynced(id = mutation.id, table = mutation.table)
                    syncedTables += mutation.table
                    syncedCount++
                    sdkLogger.debug { "Synced ${mutation.operation} mutation for ${mutation.table}" }
                }

                is ReplayOutcome.Rejected -> {
                    try {
                        rejected += store.rejectMutation(mutation, outcome.message, outcome.serverError)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (unrecorded: Exception) {
                        // The rejected list cannot be written (it cannot be decoded). The write
                        // stays queued rather than vanish, and the pass stops here.
                        sdkLogger.error {
                            "Could not record the rejection of ${mutation.operation} on ${mutation.table}; " +
                                "keeping it queued (${unrecorded::class.simpleName})"
                        }
                        stop = SyncStop.TransientFailure(unrecorded)
                        break
                    }
                }

                is ReplayOutcome.Held -> {
                    stop = SyncStop.HeldBehindEarlierMutations(mutation.id, outcome.cause)
                    break
                }

                is ReplayOutcome.Stopped -> {
                    stop = SyncStop.TransientFailure(outcome.cause)
                    break
                }
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
            remainingCount = remainingCount(table),
            stop = stop,
        )
    }

    /** How one write's replay ended. */
    private sealed interface ReplayOutcome {
        data object Synced : ReplayOutcome
        data class Rejected(val message: String, val serverError: StoredServerError?) : ReplayOutcome
        data class Held(val cause: Throwable) : ReplayOutcome
        data class Stopped(val cause: Throwable) : ReplayOutcome
    }

    private suspend fun replay(mutation: MutationRow, tableApi: PostgrestQueryBuilderWrapper): ReplayOutcome =
        when (val prepared = prepare(mutation)) {
            is Prepared.Refused -> prepared.outcome
            is Prepared.Ready -> try {
                prepared.request(tableApi)
                ReplayOutcome.Synced
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                classify(mutation, failure)
            }
        }

    private suspend fun classify(mutation: MutationRow, failure: Exception): ReplayOutcome = when {
        failure.isNetworkOrConnectivityFailure() -> {
            sdkLogger.warning { "Network unavailable, pausing sync for ${mutation.table}" }
            ReplayOutcome.Stopped(failure)
        }

        failure.isPermanentMutationFailure() -> {
            val status = failure.httpStatusOrNull()
            if ((status == HTTP_FORBIDDEN || status == HTTP_CONFLICT) && store.hasPendingMutationBefore(mutation.id)) {
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
