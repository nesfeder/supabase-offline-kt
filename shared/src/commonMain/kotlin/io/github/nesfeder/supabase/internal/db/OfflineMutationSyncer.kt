package io.github.nesfeder.supabase.internal.db

import io.github.nesfeder.supabase.OfflineLogLevel
import io.github.nesfeder.supabase.SdkLogger
import io.github.nesfeder.supabase.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.internal.postgrest.FilterEngine.applyTo
import io.github.nesfeder.supabase.internal.postgrest.FilterEngine.decodeOrNull
import io.github.nesfeder.supabase.internal.postgrest.PostgrestQueryBuilderWrapper
import io.github.nesfeder.supabase.internal.postgrest.PostgrestQueryBuilderWrapperImpl
import io.github.nesfeder.supabase.internal.util.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.internal.util.isPermanentMutationFailure
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
        }
) {

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
        var totalSynced = 0
        val pending = store.allPendingMutations()
        if (pending.isEmpty()) return totalSynced

        val tables = pending.map { it.table }

        // Sync each table independently so that a failure in one does not
        // prevent others from syncing. This preserves global progress while
        // still relying on per-table ordering + failure handling in
        // [syncTable].
        for (table in tables) {
            try {
                totalSynced += syncTable(schema, table)
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
     * Behaviour by failure type:
     *
     * - **Permanent mutation failures** (401 / 403 / 409 via
     *   [isPermanentMutationFailure]):
     *      - offending mutation is removed from the queue
     *      - subsequent mutations for the same table continue
     *
     * - **Network / connectivity failures** (timeouts, connection errors via
     *   [isNetworkOrConnectivityFailure]):
     *      - current mutation is kept pending
     *      - syncing aborts for this table to preserve mutation ordering
     *
     * - **Unknown / unexpected errors**:
     *      - current mutation is kept pending
     *      - exception is rethrown to the caller
     */
    suspend fun syncTable(
        schema: String = supabase.postgrest.config.defaultSchema,
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
                        applyUpdate(tableApi, body, filter)
                    }

                    MutationOperation.DELETE -> {
                        val filter = decodeOrNull(mutation.filterJson, json)
                        applyDelete(tableApi, filter)
                    }
                }

                // If we reach here, the remote call succeeded — mark mutation as synced
                // but keep it in the local queue so it can still participate in
                // overlays when SELECT falls back to cached data while offline.
                store.markMutationSynced(mutation.id)
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
                        // continue to next mutation
                    }

                    // Network / connectivity failures are transient. We keep the
                    // current mutation (and all remaining ones) in the queue and
                    // abort syncing for this table to preserve strict ordering.
                    throwable.isNetworkOrConnectivityFailure() -> {
                        sdkLogger.warning { "Network unavailable, pausing sync for $table" }
                        break
                    }

                    // Unknown server or application error. Keep the mutation in
                    // the queue and propagate the error so the caller is aware.
                    else -> throw throwable
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
}