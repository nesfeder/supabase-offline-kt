package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.NumericDeltaColumn
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine.applyTo
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine.decodeOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.PostgrestQueryBuilderWrapper
import io.github.nesfeder.supabase.offline.internal.postgrest.PostgrestQueryBuilderWrapperImpl
import io.github.nesfeder.supabase.offline.internal.util.awaitReplaySafeSession
import io.github.nesfeder.supabase.offline.internal.util.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.internal.util.isPermanentMutationFailure
import io.github.nesfeder.supabase.offline.internal.util.isTransientAuthFailure
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * Responsible for **pushing pending local mutations** from [OfflineStore] to Supabase
 * once connectivity is available.
 *
 * This class does **not** queue mutations itself — that is handled by [OfflineStore]
 * and the offline-aware PostgREST builder. Instead, it:
 *
 * 1. Reads queued [MutationRow] records from [OfflineStore]
 * 2. Replays them against the remote Supabase PostgREST API
 * 3. Marks each mutation as synced (and later removes it) on successful sync
 *
 * Typical usage:
 *
 * ```kotlin
 * val syncer = OfflineMutationSyncer(store, supabase)
 *
 * // Called when network becomes available again:
 * scope.launch {
 *     syncer.syncAllTables()
 * }
 * ```
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
     * owner-scoped write and the permanent-failure classification below would drop the queued
     * mutation — silent data loss for a write that succeeds once the restore or refresh lands.
     * `false` means no authenticated session arrived within the bounded wait: the sync entry
     * points then return without replaying, keeping the queue intact for the next trigger.
     * Injectable for tests; defaults to [awaitReplaySafeSession] on [supabase], which fails open
     * for clients without the Auth plugin.
     */
    private val awaitSessionSettled: suspend () -> Boolean = { supabase.awaitReplaySafeSession() },
) {

    /**
     * Serializes every public sync entry point ([syncAllTables] / [syncTable]) so two concurrent
     * triggers cannot interleave their replay loops and push the same mutation twice before
     * [OfflineStore.markMutationSynced] lands (which would create duplicate INSERTs server-side).
     * Re-entrancy is avoided by having the locked bodies call the private `*Locked` helpers rather
     * than the public (self-locking) methods.
     */
    private val syncMutex = Mutex()

    /**
     * Per-process replay-failure budget keyed by [MutationRow.id]. Incremented on each unknown-error
     * replay; once a mutation reaches [MAX_REPLAY_FAILURES] it is dead-lettered (dropped) instead of
     * retried forever. Guarded by [syncMutex] (only ever touched from the locked sync bodies).
     * The budget is intentionally in-memory — a durable dead-letter store is a follow-up and would
     * require a Room schema change, which this change deliberately avoids.
     */
    private val failureCounts = mutableMapOf<Long, Int>()

    /**
     * Syncs **all tables** that currently have pending mutations.
     *
     * Each table is synced independently; a failure while syncing one table
     * must **not** prevent other tables from making progress.
     *
     * @return number of mutations successfully synced (and marked as such).
     */
    suspend fun syncAllTables(
        schema: String = supabase.postgrest.config.defaultSchema
    ): Int {
        if (!awaitSessionSettled()) {
            sdkLogger.info { "No authenticated session; deferring mutation replay" }
            return 0
        }
        return syncMutex.withLock { syncAllTablesLocked(schema) }
    }

    /**
     * Locked body of [syncAllTables]. Must only be called while [syncMutex] is held.
     */
    private suspend fun syncAllTablesLocked(schema: String): Int {
        var totalSynced = 0
        val pending = store.allPendingMutations()
        if (pending.isEmpty()) return totalSynced

        // Distinct: a table with N pending mutations must be swept once, not N times — every
        // [syncTableLocked] call already drains all of that table's pending mutations.
        val tables = pending.map { it.table }.distinct()

        // Sync each table independently so that a failure in one does not
        // prevent others from syncing. This preserves global progress while
        // still relying on per-table ordering + failure handling in
        // [syncTableLocked].
        for (table in tables) {
            try {
                totalSynced += syncTableLocked(schema, table)
            } catch (_: Throwable) {
                currentCoroutineContext().ensureActive()
                // Unknown / unexpected failure while syncing this table.
                // Per requirements we must *not* let this abort syncing of
                // subsequent tables, and we leave the failing table's
                // mutations untouched so they can be retried later.
                //
                // We intentionally swallow the exception here; callers only
                // receive the count of successfully synced mutations.
            }
        }
        return totalSynced
    }

    /**
     * Syncs all queued mutations for a single [table] against Supabase.
     *
     * Returns the number of mutations that were successfully applied on the
     * server and marked as synced locally.
     *
     * An UPDATE that declared [io.github.nesfeder.supabase.offline.NumericDeltaColumn]s is replayed
     * read-before-write: the targeted row is re-read and each declared column is rebased onto the
     * server's current value before the PATCH goes out, so a change made elsewhere while the
     * mutation sat in the queue survives instead of being clobbered by a stale absolute. See
     * [rebaseDeltasAgainstServer].
     *
     * Before anything reaches the wire the call awaits [awaitSessionSettled]: replaying without
     * an `Authenticated` session (cold-start restore in flight, signed out, or the post-offline
     * `RefreshFailure` window) sends owner-scoped writes on the anon key, turning them into RLS
     * 403s dropped via the permanent-failure branch below. No authenticated session within the
     * bound defers the whole sweep (returns 0, queue untouched).
     *
     * Behaviour by failure type:
     *
     * - **Permanent mutation failures** (403 / 404 / 409 / 422 via
     *   [isPermanentMutationFailure] — note 401 is *transient*, see
     *   [io.github.nesfeder.supabase.offline.internal.util.isPermanentMutationFailureStatus]):
     *      - offending mutation is removed from the queue
     *      - subsequent mutations for the same table continue
     *
     * - **Network / connectivity failures** (timeouts, connection errors via
     *   [isNetworkOrConnectivityFailure]):
     *      - current mutation is kept pending
     *      - syncing aborts for this table to preserve mutation ordering
     *
     * - **Transient auth failures** (401 via
     *   [io.github.nesfeder.supabase.offline.internal.util.isTransientAuthFailure]):
     *      - current mutation is kept pending and the error is rethrown
     *      - **never** counted toward the dead-letter budget, so a token-refresh race after
     *        reconnect (several rapid triggers in one bad-token window) can never drop the write
     *
     * - **Unknown / unexpected errors**:
     *      - current mutation is kept pending and the error is rethrown, up to
     *        [MAX_REPLAY_FAILURES] replays; on the final replay the mutation is
     *        dead-lettered (dropped) instead of retrying forever.
     */
    suspend fun syncTable(
        schema: String = supabase.postgrest.config.defaultSchema,
        table: String
    ): Int {
        if (!awaitSessionSettled()) {
            sdkLogger.info { "No authenticated session; deferring mutation replay for $table" }
            return 0
        }
        return syncMutex.withLock { syncTableLocked(schema, table) }
    }

    /**
     * Locked body of [syncTable]. Must only be called while [syncMutex] is held.
     */
    private suspend fun syncTableLocked(
        schema: String,
        table: String
    ): Int {
        var syncedCount = 0
        val pending = store.pendingMutationsForTable(table)
        if (pending.isEmpty()) return syncedCount

        sdkLogger.info { "Syncing ${pending.size} pending mutations for $table" }

        val tableApi = postgrestFactory(schema, table)
        val json = store.json

        for (mutation in pending) {
            try {
                when (mutation.operation) {
                    MutationOperation.INSERT -> {
                        val body = parseJsonArrayOrNull(json, mutation.bodyJson) ?: run {
                            // Un-parseable payload; drop this mutation and continue.
                            store.deleteMutation(mutation.id)
                            continue
                        }
                        val filter = decodeOrNull(mutation.filterJson, json)
                        applyInsert(
                            tableApi = tableApi,
                            body = body,
                            defaultToNull = mutation.defaultToNull,
                            filter = filter
                        )
                    }

                    MutationOperation.UPSERT -> {
                        val body = parseJsonArrayOrNull(json, mutation.bodyJson) ?: run {
                            store.deleteMutation(mutation.id)
                            continue
                        }
                        val filter = decodeOrNull(mutation.filterJson, json)
                        applyUpsert(
                            tableApi = tableApi,
                            body = body,
                            onConflict = mutation.onConflict,
                            ignoreDuplicates = mutation.ignoreDuplicates,
                            defaultToNull = mutation.defaultToNull,
                            filter = filter
                        )
                    }

                    MutationOperation.UPDATE -> {
                        val body = parseJsonElementOrNull(json, mutation.bodyJson) ?: run {
                            store.deleteMutation(mutation.id)
                            continue
                        }
                        val filter = decodeOrNull(mutation.filterJson, json)
                        if (filter == null) {
                            // Refuse to replay an unfiltered UPDATE — it would rewrite every row RLS
                            // lets the user touch. A captured filter queues faithfully and a lossy
                            // one fails loudly at enqueue, so this is reached only by a genuinely
                            // filter-free update or a legacy pre-strict row — both are dropped.
                            sdkLogger.error {
                                "refusing unfiltered UPDATE replay for $table; dropping mutation"
                            }
                            store.deleteMutation(mutation.id)
                            failureCounts.remove(mutation.id)
                            continue
                        }
                        if (!FilterEngine.replaysFully(filter)) {
                            // The descriptor decoded, but at least one condition cannot be parsed
                            // back into a filter — so the UPDATE would run scoped by the surviving
                            // conditions alone. That is the same table-wide hazard as the null
                            // case above, just harder to see, so it takes the same exit.
                            sdkLogger.error {
                                "refusing partially-replayable UPDATE filter for $table; " +
                                    "dropping mutation"
                            }
                            store.deleteMutation(mutation.id)
                            failureCounts.remove(mutation.id)
                            continue
                        }
                        applyUpdate(
                            tableApi = tableApi,
                            body = rebaseDeltasAgainstServer(
                                tableApi = tableApi,
                                table = table,
                                body = body,
                                filter = filter,
                                deltaColumns = decodeNumericDeltaColumns(mutation.deltaJson, json),
                            ),
                            filter = filter,
                        )
                    }

                    MutationOperation.DELETE -> {
                        val filter = decodeOrNull(mutation.filterJson, json)
                        if (filter == null) {
                            // Refuse to replay an unfiltered DELETE — it would delete every row RLS
                            // lets the user touch. A captured filter queues faithfully and a lossy
                            // one fails loudly at enqueue, so this is reached only by a genuinely
                            // filter-free delete or a legacy pre-strict row — both are dropped.
                            sdkLogger.error {
                                "refusing unfiltered DELETE replay for $table; dropping mutation"
                            }
                            store.deleteMutation(mutation.id)
                            failureCounts.remove(mutation.id)
                            continue
                        }
                        if (!FilterEngine.replaysFully(filter)) {
                            // Same hazard as the UPDATE branch, with a worse outcome: a DELETE
                            // that loses a condition removes every row the surviving conditions
                            // and RLS still admit. Drop it rather than execute it.
                            sdkLogger.error {
                                "refusing partially-replayable DELETE filter for $table; " +
                                    "dropping mutation"
                            }
                            store.deleteMutation(mutation.id)
                            failureCounts.remove(mutation.id)
                            continue
                        }
                        applyDelete(tableApi, filter)
                    }
                }

                // If we reach here, the remote call succeeded — mark mutation as synced
                // but keep it in the local queue so it can still participate in
                // overlays when SELECT falls back to cached data while offline.
                store.markMutationSynced(id = mutation.id, table = mutation.table)
                failureCounts.remove(mutation.id)
                syncedCount++
                sdkLogger.debug { "Synced ${mutation.operation} mutation for $table" }
            } catch (throwable: Throwable) {
                when {
                    // Permanent failures (permission/constraint). These will never
                    // succeed on retry, so we drop the offending mutation but
                    // continue processing subsequent ones.
                    throwable.isPermanentMutationFailure() -> {
                        sdkLogger.warning {
                            "Permanent failure for ${mutation.operation} on $table, dropping mutation"
                        }
                        store.deleteMutation(mutation.id)
                        failureCounts.remove(mutation.id)
                        // continue to next mutation
                    }

                    // Network / connectivity failures are transient. We keep the
                    // current mutation (and all remaining ones) in the queue and
                    // abort syncing for this table to preserve strict ordering.
                    throwable.isNetworkOrConnectivityFailure() -> {
                        sdkLogger.warning { "Network unavailable, pausing sync for $table" }
                        break
                    }

                    // Transient auth failure (401): the access token expired while offline and
                    // supabase-kt's refresh has not landed yet. Keep the mutation and rethrow so
                    // the table pauses, but never count it toward the dead-letter budget — a
                    // bad-token window (several rapid reconnect triggers) must not drop the user's
                    // queued write. A later sync retries once the refreshed token is in place.
                    throwable.isTransientAuthFailure() -> {
                        sdkLogger.warning {
                            "Unauthorized (401) replaying ${mutation.operation} on $table; " +
                                "keeping mutation for a later retry"
                        }
                        throw throwable
                    }

                    // Unknown server or application error (e.g. 500). Keep the mutation
                    // in the queue and propagate the error, but bound the retries: after
                    // MAX_REPLAY_FAILURES replays we dead-letter it instead of poisoning
                    // every future sync trigger.
                    else -> {
                        val failures = (failureCounts[mutation.id] ?: 0) + 1
                        if (failures >= MAX_REPLAY_FAILURES) {
                            sdkLogger.error {
                                "dead-lettering mutation ${mutation.id} for $table after " +
                                    "$MAX_REPLAY_FAILURES failed replays"
                            }
                            store.deleteMutation(mutation.id)
                            failureCounts.remove(mutation.id)
                            // continue to next mutation
                        } else {
                            failureCounts[mutation.id] = failures
                            throw throwable
                        }
                    }
                }
            }
        }

        sdkLogger.info { "Sync complete: $syncedCount mutations synced for $table" }
        return syncedCount
    }

    private fun parseJsonArrayOrNull(json: Json, raw: String): JsonArray? =
        runCatching {
            val jsonElement = json.parseToJsonElement(raw)
            jsonElement.jsonArray
        }.getOrNull()

    private fun parseJsonElementOrNull(json: Json, raw: String): JsonElement? =
        runCatching { json.parseToJsonElement(raw) }.getOrNull()

    /* ───────────────────────────── Remote calls ───────────────────────────── */

    /**
     * Apply an INSERT mutation online.
     */
    private suspend fun applyInsert(
        tableApi: PostgrestQueryBuilderWrapper,
        body: JsonArray,
        defaultToNull: Boolean?,
        filter: FilterDescriptor?
    ) {
        tableApi.insert(body) {
            this.defaultToNull = defaultToNull ?: true
            if (filter != null) applyTo(this, filter)
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
     * actual intent (see [io.github.nesfeder.supabase.offline.NumericDeltaColumn]).
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
     * Read failures are **not** swallowed: they propagate into [syncTableLocked]'s classifier, so a
     * connectivity failure pauses the table with the mutation still pending (correct — the device
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
     * Apply an UPDATE mutation online, optionally with a stored [FilterDescriptor].
     *
     * The filter is re-applied to a fresh [PostgrestRequestBuilder] via [FilterEngine],
     * so we target the same rows that the offline UPDATE intended to modify.
     */
    private suspend fun applyUpdate(
        tableApi: PostgrestQueryBuilderWrapper,
        body: JsonElement,
        filter: FilterDescriptor?
    ) {
        tableApi.update(body) {
            if (filter != null) applyTo(this, filter)
        }
    }

    /**
     * Apply a DELETE mutation online, optionally with a stored [FilterDescriptor].
     */
    private suspend fun applyDelete(
        tableApi: PostgrestQueryBuilderWrapper,
        filter: FilterDescriptor?
    ) {
        tableApi.delete {
            if (filter != null) applyTo(this, filter)
        }
    }

    private companion object {
        /**
         * Number of unknown-error replays a single mutation is allowed before it is dead-lettered
         * (dropped) rather than retried on every future sync trigger. The mutation is rethrown for
         * the first `MAX_REPLAY_FAILURES - 1` replays and dropped on the final one.
         */
        const val MAX_REPLAY_FAILURES = 5
    }
}