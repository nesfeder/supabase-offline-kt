// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.Returning
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.internal.ClientOperationGate
import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.cache.EnqueueOutcome
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.cache.returnedRowsOf
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.httpStatusOrNull
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.currentQueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import io.github.nesfeder.supabase.offline.internal.newEngineScope
import io.github.nesfeder.supabase.offline.internal.postgrest.DryRunVerdict
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.LiveQueries
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryReader
import io.github.nesfeder.supabase.offline.internal.postgrest.RemoteTable
import io.github.nesfeder.supabase.offline.internal.postgrest.RequestSession
import io.github.nesfeder.supabase.offline.internal.postgrest.ResponseTrustPolicy
import io.github.nesfeder.supabase.offline.internal.postgrest.SupabaseRemoteTable
import io.github.nesfeder.supabase.offline.internal.postgrest.UncapturableFilterException
import io.github.nesfeder.supabase.offline.internal.postgrest.captureFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.captureFilterExactly
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.readShapePostgrestOf
import io.github.nesfeder.supabase.offline.internal.postgrest.replaysFully
import io.github.nesfeder.supabase.offline.internal.postgrest.rowsForCachePatch
import io.github.nesfeder.supabase.offline.internal.postgrest.wasRolledBack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray

/**
 * Offline-aware PostgREST query builder.
 *
 * The mutation verbs are named to mirror the supabase-kt SDK (`insertJson` / `insertWith` /
 * `upsertJson` / `upsertWith` / `updateJson` / `updateWith` / `delete`) but return a
 * [MutationOutcome] rather than a bare [PostgrestResult]: an online write reports
 * [MutationOutcome.Applied] carrying the live response, while a write that waits in the queue — the
 * network unavailable, or earlier writes still pending — reports [MutationOutcome.Queued]. Callers
 * that read the written row back branch on the outcome; callers that fire-and-forget can ignore it.
 * Use [MutationOutcome.resultOrNull] for the "result or null when queued" shape. See
 * [MutationStrategy.QueueIfOffline] for how a write moves through the queue.
 *
 * A verb may run its `request` lambda more than once — a read once to learn its shape and once per
 * request sent, a queueable write up to three times — so the lambda must configure the same request
 * every time: no side effects, and no clock or counter read.
 */
public interface OfflinePostgrestQueryBuilder {

    /**
     * One-shot read that consults the network and the offline cache according to [readStrategy].
     *
     * Only a list of rows is cached. A read that asks for another shape — `single()`,
     * `maybeSingle()`, `head`, a `count` (with rows or without), `csv()`, `geojson()`,
     * `stripNulls()` or `explain()` — goes to the network under every strategy but
     * [ReadStrategy.CacheOnly], is not stored, and offline fails as [ReadStrategy.NetworkOnly]
     * does. Under [ReadStrategy.CacheOnly] it sends nothing and throws [NoSuchElementException]. To
     * read one row offline, read the list and take its first row.
     *
     * [request] runs once to learn the read's shape and again each time the read is sent, so it
     * must have no side effects and read no clock or counter.
     *
     * @param columns Columns to select.
     * @param readStrategy How the network and the cache are combined; defaults to
     *   [ReadStrategy.NetworkFirst], which falls back to the cache on a connectivity failure.
     * @param request PostgREST request builder (filters, ordering, …).
     * @return The server's result, or the cached payload wrapped in a result.
     */
    public suspend fun select(
        columns: Columns = Columns.ALL,
        readStrategy: ReadStrategy = ReadStrategy.NetworkFirst(),
        request: SelectRequestBuilder.() -> Unit = {},
    ): PostgrestResult

    /**
     * Reactive read that surfaces per-query read and refresh state in-band as [OfflineReadResult].
     * That is the background [RefreshState] (so a consumer can tell "refresh failed, showing
     * stale/empty cache" from "genuinely empty") and the typed "no data yet" state (a `null`
     * result) that replaces the old fabricated empty-array cache seed.
     *
     * Consumers that want today's instant-empty-state rendering map the no-data case to
     * `emptyList()` in their repository; consumers that want a Loading state until data is known
     * leave the no-data emissions unmapped. [RefreshState] is available for a future refresh
     * affordance and may be ignored today.
     *
     * Every strategy ends the same way when the client closes: a collection live at
     * `SupabaseOfflineClient.close()` fails with [IllegalStateException], and so does one collected
     * after it, through the flow's own error channel (`catch` sees it). A read that fails for any
     * other reason delivers its values before the failure (to a slow collector of
     * `CacheFirstThenNetwork`, the latest of them), then the failure itself, a cancellation
     * included. A slow collector can be handed a snapshot a newer one has already replaced, and the
     * last it is handed is the latest. Under `CacheFirstThenNetwork` that is at most one, and a
     * collector slow to take its first snapshot is handed a later one instead; the other strategies
     * can hand several in turn.
     *
     * A read of a shape the cache does not hold (see [select]) emits the network's answer once,
     * with [RefreshState.Idle], and completes; offline, it fails. Under
     * [FlowReadStrategy.CacheOnly] it fails at once with [NoSuchElementException]. [request] may
     * run several times, as for [select].
     *
     * @param columns Columns to select.
     * @param readStrategy Flow read strategy.
     * @param request PostgREST request builder (filters, ordering, …).
     * @return A flow of [OfflineReadResult] carrying payload and refresh state.
     */
    public fun selectAsFlow(
        columns: Columns = Columns.ALL,
        readStrategy: FlowReadStrategy = FlowReadStrategy.CacheFirstThenNetwork,
        request: SelectRequestBuilder.() -> Unit = {},
    ): Flow<OfflineReadResult>

    /**
     * Insert [body] rows, reporting whether the INSERT executed on the server
     * ([MutationOutcome.Applied], carrying the PostgREST response) or was queued for offline replay
     * ([MutationOutcome.Queued]) instead of collapsing a queued write into a synthetic empty
     * result.
     *
     * @param body rows to insert, as a JSON array.
     * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
     * @param request request-configuration block (e.g. `select()`).
     * @return the mutation outcome.
     */
    public suspend fun insertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: InsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome

    /**
     * Insert a list of [values].
     *
     * @param serializer element serializer.
     * @param values rows to insert.
     * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
     * @param request request-configuration block.
     * @return the mutation outcome.
     */
    public suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: InsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome

    /**
     * Insert a single [value].
     *
     * @param serializer element serializer.
     * @param value row to insert.
     * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
     * @param request request-configuration block.
     * @return the mutation outcome.
     */
    public suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: InsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome

    /**
     * Upsert [body] rows.
     *
     * @param body rows to upsert, as a JSON array.
     * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
     * @param request request-configuration block (e.g. `onConflict = …; select()`).
     * @return the mutation outcome.
     */
    public suspend fun upsertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: UpsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome

    /**
     * Upsert a list of [values].
     *
     * @param serializer element serializer.
     * @param values rows to upsert.
     * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
     * @param request request-configuration block.
     * @return the mutation outcome.
     */
    public suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: UpsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome

    /**
     * Upsert a single [value].
     *
     * @param serializer element serializer.
     * @param value row to upsert.
     * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
     * @param request request-configuration block.
     * @return the mutation outcome.
     */
    public suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: UpsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome

    /**
     * Update columns from [body], targeting rows via the [request] filter. A queued update replays
     * exactly the values in [body]; a value several devices change concurrently belongs in a
     * server-side computation, not in a queued write.
     *
     * @param body columns to update, as a JSON element.
     * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
     * @param request request-configuration block (e.g. `filter { … }; select()`).
     * @return the mutation outcome.
     */
    public suspend fun updateJson(
        body: JsonElement,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: PostgrestRequestBuilder.() -> Unit = {},
    ): MutationOutcome

    /**
     * Update columns from a single [value], targeting rows via the [request] filter.
     *
     * @param serializer value serializer.
     * @param value columns to update.
     * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
     * @param request request-configuration block.
     * @return the mutation outcome.
     */
    public suspend fun <T : Any> updateWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: PostgrestRequestBuilder.() -> Unit = {},
    ): MutationOutcome

    /**
     * Delete rows targeting via the [request] filter.
     *
     * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
     * @param request request-configuration block (e.g. `filter { … }`).
     * @return the mutation outcome.
     */
    public suspend fun delete(
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: PostgrestRequestBuilder.() -> Unit = {},
    ): MutationOutcome
}

/**
 * Internal implementation that delegates to the online [PostgrestQueryBuilder] and augments it with
 * offline caching, mutation queuing, and overlay via [OfflineStore].
 */
internal class OfflinePostgrestQueryBuilderImpl(
    private val delegate: RemoteTable,
    private val store: OfflineStore,
    private val engineLogger: EngineLogger = EngineLogger(null, OfflineLogLevel.INFO),
    /**
     * The session a read's request goes out under; built directly, a client without the Auth
     * plugin.
     */
    private val requestSession: () -> RequestSession = { RequestSession.WithoutAuth },
    /** Which read responses may replace what the cache holds. */
    private val trustPolicy: ResponseTrustPolicy = ResponseTrustPolicy.SETTLED_SESSION,
    /**
     * Coordinator forwarded to [QueryReader] for single-flight refresh sharing per `(table,
     * signature)` and the live-signature registry backing materialize-on-write.
     *
     * The real client passes its **client-scoped** instance so it is shared across every builder it
     * creates — single-flight per signature only works when one coordinator spans all builders, so
     * production must never rely on the default. The default builds a fresh per-builder coordinator
     * over a standalone scope purely so tests and direct construction can call
     * `OfflinePostgrestQueryBuilderImpl(delegate, store)` without wiring one up; the scope hosts
     * `WhileSubscribed(0, 0)` shares that tear down when collection stops, so nothing runs once a
     * short-lived subscription ends.
     */
    private val liveQueries: LiveQueries = defaultLiveQueries(),
    /**
     * Internal edge-triggered recovery accelerator (auth restored / connectivity regained)
     * forwarded to [QueryReader] to short-circuit a stalled refresh's backoff. Defaults to an empty
     * flow (recovery still happens on the capped schedule) for direct construction.
     */
    private val wakeUps: Flow<Unit> = emptyFlow(),
    /**
     * The identity a write is made for, read as the write is issued and recorded as its owner when
     * it is queued; see [OfflineStore.enqueueOwnedBy]. `null` from the provider means it cannot be
     * told, and the write is then sent directly, never queued.
     */
    private val queueIdentity: suspend () -> QueueIdentity? = {
        delegate.postgrest.supabaseClient.currentQueueIdentity()
    },
    /**
     * Asks for a replay pass. Called when a write was queued behind pending writes, and when more
     * are pending after a write sent at the head, so they go out without waiting for the app. Does
     * nothing when built directly. A switch that turns automatic replay off must not stop these
     * passes: they are what sends an online write that returned `Queued` (see `AutomaticReplay`).
     */
    private val requestSync: () -> Unit = {},
    /**
     * Sends a queued write at the head of its identity's queue. The real client passes its one
     * syncer, which every builder and replay pass share; built directly, the builder has its own.
     */
    private val headSender: MutationQueueReplayer =
        MutationQueueReplayer(
            store = store,
            supabase = delegate.postgrest.supabaseClient,
            engineLogger = engineLogger,
            currentIdentity = queueIdentity,
        ),
    /** The client's gate: every read and write passes it, so the client's close waits for them. */
    private val operationGate: ClientOperationGate = ClientOperationGate(),
) : OfflinePostgrestQueryBuilder {

    constructor(
        delegate: PostgrestQueryBuilder,
        store: OfflineStore,
        engineLogger: EngineLogger = EngineLogger(null, OfflineLogLevel.INFO),
        requestSession: () -> RequestSession = { RequestSession.WithoutAuth },
        trustPolicy: ResponseTrustPolicy = ResponseTrustPolicy.SETTLED_SESSION,
        liveQueries: LiveQueries = defaultLiveQueries(),
        wakeUps: Flow<Unit> = emptyFlow(),
        queueIdentity: suspend () -> QueueIdentity? = {
            delegate.postgrest.supabaseClient.currentQueueIdentity()
        },
        requestSync: () -> Unit = {},
        headSender: MutationQueueReplayer =
            MutationQueueReplayer(
                store = store,
                supabase = delegate.postgrest.supabaseClient,
                engineLogger = engineLogger,
                currentIdentity = queueIdentity,
            ),
        operationGate: ClientOperationGate = ClientOperationGate(),
        readShapePostgrest: Postgrest = readShapePostgrestOf(delegate.postgrest),
    ) : this(
        delegate = SupabaseRemoteTable(delegate, readShapePostgrest),
        store = store,
        engineLogger = engineLogger,
        requestSession = requestSession,
        trustPolicy = trustPolicy,
        liveQueries = liveQueries,
        wakeUps = wakeUps,
        queueIdentity = queueIdentity,
        requestSync = requestSync,
        headSender = headSender,
        operationGate = operationGate,
    )

    private val json: Json = EngineJson
    private val queryReader =
        QueryReader(
            delegate = delegate,
            store = store,
            engineLogger = engineLogger,
            requestSession = requestSession,
            trustPolicy = trustPolicy,
            liveQueries = liveQueries,
            wakeUps = wakeUps,
        )

    override suspend fun select(
        columns: Columns,
        readStrategy: ReadStrategy,
        request: SelectRequestBuilder.() -> Unit,
    ): PostgrestResult = operationGate.call { queryReader.select(columns, readStrategy, request) }

    override fun selectAsFlow(
        columns: Columns,
        readStrategy: FlowReadStrategy,
        request: SelectRequestBuilder.() -> Unit,
    ): Flow<OfflineReadResult> {
        // Every strategy one way: counted while collected, refused in band once closed, and ended
        // by close() however its collector is stuck
        return operationGate.callCollecting(
            queryReader.selectAsFlow(columns, readStrategy, request)
        )
    }

    // INSERT -------------------------------------------------------------------

    override suspend fun insertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit,
    ): MutationOutcome {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        engineLogger.debug { "INSERT $table request body: $body" }
        // One build of the request serves its schema, its queue capture and the cache patch
        val built = lazy { InsertRequestBuilder(delegate.schema, conversionMethod).apply(request) }
        return executeMutation(
            verbLabel = "INSERT",
            table = table,
            mutationStrategy = mutationStrategy,
            writeSchema = { built.value.schema },
            perform = { handle -> handle.insert(body, request) },
            patchCache = { result, startedAtTick ->
                // Honor QueueIfOffline's "UI reflects immediately" promise on the online-success
                // path so reactive `selectAsFlow` observers re-emit without waiting for a re-fetch.
                // OnlineOnly callers opted out of this — leave their cache untouched.
                val insertBuilder = built.value
                val descriptor = captureFilter(insertBuilder)
                store.patchCachedQueries(
                    table = table,
                    operation = MutationOperation.INSERT,
                    bodyJson =
                        preferResponseForCache(body, result.data, store.primaryKeys.of(table)),
                    filterJson = descriptor?.let { encodeFilter(it, json) },
                    defaultToNull = insertBuilder.defaultToNull,
                    startedAtTick = startedAtTick,
                )
            },
            captureForQueue = {
                val insertBuilder = built.value
                val descriptor = captureFilter(insertBuilder)
                QueueCapture.Queueable {
                    enqueueInsert(
                        table = table,
                        body = body,
                        defaultToNull = insertBuilder.defaultToNull,
                        filter = descriptor,
                    )
                }
            },
        )
    }

    override suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit,
    ): MutationOutcome {
        val listSerializer = ListSerializer(serializer)
        val body = json.encodeToJsonElement(listSerializer, values).jsonArray
        return insertJson(body, mutationStrategy, request)
    }

    override suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit,
    ): MutationOutcome {
        val element = json.encodeToJsonElement(serializer, value)
        val body = buildJsonArray { add(element) }
        return insertJson(body, mutationStrategy, request)
    }

    // UPSERT -------------------------------------------------------------------

    override suspend fun upsertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit,
    ): MutationOutcome {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        engineLogger.debug { "UPSERT $table request body: $body" }
        // One build of the request serves its schema, its queue capture and the cache patch
        val built = lazy { UpsertRequestBuilder(delegate.schema, conversionMethod).apply(request) }
        return executeMutation(
            verbLabel = "UPSERT",
            table = table,
            mutationStrategy = mutationStrategy,
            writeSchema = { built.value.schema },
            perform = { handle -> handle.upsert(body, request) },
            patchCache = { result, startedAtTick ->
                val upsertBuilder = built.value
                val descriptor = captureFilter(upsertBuilder)
                val conflictColumns =
                    upsertBuilder.onConflict
                        ?.split(',')
                        ?.map { column -> column.trim() }
                        ?.filter { column -> column.isNotEmpty() }
                        ?.takeIf { it.isNotEmpty() } ?: store.primaryKeys.of(table)
                store.patchCachedQueries(
                    table = table,
                    operation = MutationOperation.UPSERT,
                    bodyJson = preferResponseForCache(body, result.data, conflictColumns),
                    filterJson = descriptor?.let { encodeFilter(it, json) },
                    defaultToNull = upsertBuilder.defaultToNull,
                    onConflict = upsertBuilder.onConflict,
                    ignoreDuplicates = upsertBuilder.ignoreDuplicates,
                    startedAtTick = startedAtTick,
                )
            },
            captureForQueue = {
                val upsertBuilder = built.value
                val descriptor = captureFilter(upsertBuilder)
                QueueCapture.Queueable {
                    enqueueUpsert(
                        table = table,
                        bodyJson = body,
                        defaultToNull = upsertBuilder.defaultToNull,
                        onConflict = upsertBuilder.onConflict,
                        ignoreDuplicates = upsertBuilder.ignoreDuplicates,
                        filter = descriptor,
                    )
                }
            },
        )
    }

    override suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit,
    ): MutationOutcome {
        val listSerializer = ListSerializer(serializer)
        val body = json.encodeToJsonElement(listSerializer, values).jsonArray
        return upsertJson(body, mutationStrategy, request)
    }

    override suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit,
    ): MutationOutcome {
        val element = json.encodeToJsonElement(serializer, value)
        val body = buildJsonArray { add(element) }
        return upsertJson(body, mutationStrategy, request)
    }

    // UPDATE -------------------------------------------------------------------

    override suspend fun updateJson(
        body: JsonElement,
        mutationStrategy: MutationStrategy,
        request: PostgrestRequestBuilder.() -> Unit,
    ): MutationOutcome {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        engineLogger.debug { "UPDATE $table request body: $body" }
        // One build of the request serves its schema, its queue capture and the cache patch
        val built = lazy { SelectRequestBuilder(delegate.schema, conversionMethod).apply(request) }
        return executeMutation(
            verbLabel = "UPDATE",
            table = table,
            mutationStrategy = mutationStrategy,
            writeSchema = { built.value.schema },
            perform = { handle -> handle.update(body, request) },
            patchCache = { result, startedAtTick ->
                // Asked for rows back, the response names every row the update changed, none when
                // it is empty; each carrying its key is patched by it alone, in one pass
                val returnsRows = built.value.returning is Returning.Representation
                val keyColumns = store.primaryKeys.of(table)
                val returned = returnedRowsOf(json, result.data)
                if (!returnsRows || returned.any { row -> primaryKeyOf(row, keyColumns) == null }) {
                    patchFilteredWrite(
                        MutationOperation.UPDATE,
                        table,
                        body.toString(),
                        built.value,
                        startedAtTick,
                    )
                }
                // Rows it returned carry what the server set itself, such as a trigger's
                // `updated_at`
                store.patchReturnedRows(table, body, result.data, startedAtTick)
            },
            captureForQueue = {
                captureFilteredWrite(built.value) { descriptor ->
                    enqueueUpdate(table = table, body = body, filter = descriptor)
                }
            },
        )
    }

    override suspend fun <T : Any> updateWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: PostgrestRequestBuilder.() -> Unit,
    ): MutationOutcome {
        val element = json.encodeToJsonElement(serializer, value)
        return updateJson(element, mutationStrategy, request)
    }

    // DELETE -------------------------------------------------------------------

    override suspend fun delete(
        mutationStrategy: MutationStrategy,
        request: PostgrestRequestBuilder.() -> Unit,
    ): MutationOutcome {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        engineLogger.debug { "DELETE $table attempting network" }
        // One build of the request serves its schema, its queue capture and the cache patch
        val built = lazy { SelectRequestBuilder(delegate.schema, conversionMethod).apply(request) }
        return executeMutation(
            verbLabel = "DELETE",
            table = table,
            mutationStrategy = mutationStrategy,
            writeSchema = { built.value.schema },
            perform = { handle -> handle.delete(request) },
            patchCache = { _, startedAtTick ->
                patchFilteredWrite(
                    MutationOperation.DELETE,
                    table,
                    "{}",
                    built.value,
                    startedAtTick,
                )
            },
            captureForQueue = {
                captureFilteredWrite(built.value) { descriptor ->
                    enqueueDelete(
                        table = table,
                        filter = descriptor,
                    )
                }
            },
        )
    }

    // --------------------------------------------------------------------------

    /**
     * Patches an UPDATE or DELETE the server applied into the cached queries of [table], by the
     * filter the queue would record for it. When that filter cannot be recorded exactly, or there
     * is none, no row is guessed at: the cached queries it may have reached are marked stale, by
     * the part of the filter that can be read, and their live readers fetch again. [builder] is the
     * write's request, already built.
     */
    private suspend fun patchFilteredWrite(
        operation: String,
        table: String,
        bodyJson: String,
        builder: PostgrestRequestBuilder,
        startedAtTick: Long,
    ) {
        val filter =
            try {
                captureFilterExactly(builder)
            } catch (_: UncapturableFilterException) {
                null
            }
        if (filter == null) {
            engineLogger.info {
                "$operation $table: its filter cannot be matched against cached rows; refreshing the queries it may reach"
            }
            // The flat conditions a row it reaches meets, logical groups and the rest aside
            val narrowing = captureFilter(builder)
            store.markQueriesAWriteMayReach(table, operation, bodyJson, narrowing, startedAtTick)
            return
        }
        store.patchCachedQueries(
            table = table,
            operation = operation,
            bodyJson = bodyJson,
            filterJson = encodeFilter(filter, json),
            startedAtTick = startedAtTick,
        )
    }

    /**
     * How a write would be queued, decided before it is sent: either the action that queues it, or
     * why it cannot be queued faithfully.
     */
    private sealed interface QueueCapture {

        /** The write can be queued; [enqueue] inserts its mutation row and returns the id. */
        class Queueable(val enqueue: suspend OfflineStore.MutationWriter.() -> Long) : QueueCapture

        /**
         * The write cannot be queued faithfully. [reason] carries no filter value and may be logged
         * at any level; [detail] may quote the filter, so it is logged at DEBUG only.
         */
        class Refused(val reason: String, val detail: String? = null) : QueueCapture
    }

    /**
     * The queue capture of an UPDATE or DELETE: queueable only with a filter the strict capture
     * records exactly and that replays in full. Anything else would replay against more rows than
     * the caller targeted — every row RLS admits, for a missing filter — so it is refused up front
     * rather than queued and refused at replay. [builder] is the write's request, already built.
     */
    private fun captureFilteredWrite(
        builder: PostgrestRequestBuilder,
        enqueue: suspend OfflineStore.MutationWriter.(FilterDescriptor) -> Long,
    ): QueueCapture {
        val descriptor =
            try {
                captureFilterExactly(builder)
            } catch (lossy: UncapturableFilterException) {
                return QueueCapture.Refused(
                    reason = "its filter cannot be captured exactly",
                    detail = lossy.message,
                )
            }
        return when {
            descriptor == null ->
                QueueCapture.Refused("a write without a filter would reach every row")
            !replaysFully(descriptor) ->
                QueueCapture.Refused("its filter cannot be replayed in full")
            else -> QueueCapture.Queueable { enqueue(descriptor) }
        }
    }

    /**
     * Sends or queues one write. `OnlineOnly` sends it and nothing else. `QueueIfOffline` saves it
     * to the mutation queue first and sends it at once only at the head of its identity's queue:
     * accepted, it is `Applied`; unanswered or answered `503`/`520`, so that it may not have run,
     * it stays `Queued`; refused, it leaves the queue and the failure is thrown; behind other
     * mutations, it waits and a pass is requested. A write the queue cannot hold faithfully — a dry
     * run, one it cannot classify, a filter it cannot record, an unknown identity — is sent
     * directly and never queued. The request lambda runs up to three times: built once for the
     * schema, the queue capture and the cache patch, once to tell a dry run and once to send.
     *
     * @param perform Sends the write on the handle it is given: this builder's, or one the dry-run
     *   check reads it on.
     * @param patchCache Patches the cache with the result, given the write's start tick.
     * @param captureForQueue How the write would be queued; see [QueueCapture].
     */
    private suspend fun executeMutation(
        verbLabel: String,
        table: String,
        mutationStrategy: MutationStrategy,
        writeSchema: () -> String,
        perform: suspend (RemoteTable) -> PostgrestResult,
        patchCache: suspend (result: PostgrestResult, startedAtTick: Long) -> Unit,
        captureForQueue: () -> QueueCapture,
    ): MutationOutcome = operationGate.call {
        executeMutationNow(
            verbLabel,
            table,
            mutationStrategy,
            writeSchema,
            perform,
            patchCache,
            captureForQueue,
        )
    }

    /**
     * [executeMutation]'s body, run while the call holds the client's gate.
     *
     * @throws IOException for a `QueueIfOffline` write to a schema other than the client's default
     *   that could not be sent: the queue replays to the default schema only, so it is never
     *   queued.
     */
    private suspend fun executeMutationNow(
        verbLabel: String,
        table: String,
        mutationStrategy: MutationStrategy,
        writeSchema: () -> String,
        perform: suspend (RemoteTable) -> PostgrestResult,
        patchCache: suspend (result: PostgrestResult, startedAtTick: Long) -> Unit,
        captureForQueue: () -> QueueCapture,
    ): MutationOutcome {
        val performOnline: suspend () -> PostgrestResult = { perform(delegate) }
        if (mutationStrategy == MutationStrategy.OnlineOnly) {
            val result = performOnline()
            engineLogger.debug { "$verbLabel $table response: ${result.data}" }
            return MutationOutcome.Applied(result)
        }
        val schema = writeSchema()
        if (schema.isNotBlank() && schema != delegate.postgrest.config.defaultSchema) {
            return sendToAnotherSchema(verbLabel, schema, table, performOnline)
        }

        val startedAtTick = store.monotonicTickNow()
        // Taken as the write is issued: a session switch while it is in flight must not re-own it.
        val identity = queueIdentity()
        val direct = DirectSend(verbLabel, table, performOnline, patchCache, startedAtTick)

        val queueable =
            when (val capture = captureForQueue()) {
                is QueueCapture.Queueable -> capture
                // Replaying it would target a broader row set than the caller wrote.
                is QueueCapture.Refused -> {
                    capture.detail?.let { detail ->
                        engineLogger.debug {
                            "Offline $verbLabel for $table would not be queued: $detail"
                        }
                    }
                    return direct.send(OfflineLogLevel.ERROR) {
                        "Refusing to queue offline $verbLabel for $table: ${capture.reason}"
                    }
                }
            }
        if (identity == null) {
            return direct.send(OfflineLogLevel.WARNING) {
                "Not queueing offline $verbLabel for $table: the session's identity cannot be told"
            }
        }
        // Read from the request's shape, on a handle that needs no session token: a session whose
        // token cannot be refreshed offline does not stop a write from queueing
        when (val verdict = delegate.classifyTokenFree(perform)) {
            DryRunVerdict.NotDryRun -> Unit
            DryRunVerdict.DryRun ->
                return direct.send(OfflineLogLevel.INFO) {
                    "Not queueing offline $verbLabel for $table: a dry run is never queued"
                }
            // The write's own failure, before any request: sending it directly reports it as it is.
            is DryRunVerdict.NotBuilt ->
                return direct.send(OfflineLogLevel.INFO) {
                    "Not queueing offline $verbLabel for $table: its request could not be built"
                }
            DryRunVerdict.Unavailable -> {
                reportUnclassifiableWriteOnce()
                return direct.send(OfflineLogLevel.WARNING) {
                    "Not queueing offline $verbLabel for $table: it may be a dry run"
                }
            }
            is DryRunVerdict.Sent -> {
                reportUnclassifiableWriteOnce()
                return direct.applied(verdict.result)
            }
        }

        val mutationId =
            when (
                val enqueued =
                    store.enqueueOwnedBy(identity.storedValue, startedAtTick) {
                        queueable.enqueue(this)
                    }
            ) {
                is EnqueueOutcome.Queued -> enqueued.id
                EnqueueOutcome.LocalDataCleared ->
                    return direct.send(OfflineLogLevel.INFO) {
                        "Not queueing offline $verbLabel for $table: local data was cleared while it was in flight"
                    }
            }

        val sent =
            try {
                headSender.sendAtHead(mutationId, identity, performOnline) { result ->
                    direct.overlay(result)
                }
            } catch (cancellation: CancellationException) {
                // Cancelled on the wire: whether the server got it is not known, so it stays
                // queued, and
                // a pass sends it.
                requestSync()
                throw cancellation
            }
        return when (sent) {
            MutationQueueReplayer.HeadSendOutcome.NotAtHead -> {
                engineLogger.info { "$verbLabel $table queued behind pending mutations" }
                requestSync()
                MutationOutcome.Queued
            }

            is MutationQueueReplayer.HeadSendOutcome.Sent -> {
                engineLogger.debug { "$verbLabel $table response: ${sent.result.data}" }
                requestPassIfPending(identity)
                MutationOutcome.Applied(sent.result)
            }

            is MutationQueueReplayer.HeadSendOutcome.StaysQueued -> {
                engineLogger.info {
                    val status = sent.cause.httpStatusOrNull()
                    when {
                        sent.cause.isNetworkOrConnectivityFailure() ->
                            "$verbLabel $table queued offline (network unavailable)"
                        status == null ->
                            "$verbLabel $table queued (it could not be sent: " +
                                "${sent.cause::class.simpleName})"
                        else ->
                            "$verbLabel $table queued (the server answered $status; " +
                                "it may not have run)"
                    }
                }
                MutationOutcome.Queued
            }

            is MutationQueueReplayer.HeadSendOutcome.Refused -> {
                requestPassIfPending(identity)
                throw sent.cause
            }

            is MutationQueueReplayer.HeadSendOutcome.Cleared -> {
                engineLogger.info {
                    "$verbLabel $table returned after local data was cleared; the cache is left as it was"
                }
                requestPassIfPending(identity)
                sent.result?.let { result -> MutationOutcome.Applied(result) }
                    ?: throw checkNotNull(sent.cause)
            }
        }
    }

    /**
     * Sends a write to [schema], not the client's default, at once: the queue replays to the
     * default schema only, so it is never queued, and it does not wait for writes that are. Its
     * cache cannot be patched from a write it cannot replay, so that schema's cached queries of
     * [table] are marked stale and fetched again.
     *
     * @throws IOException when it cannot be sent for want of a connection, naming [schema].
     */
    private suspend fun sendToAnotherSchema(
        verbLabel: String,
        schema: String,
        table: String,
        performOnline: suspend () -> PostgrestResult,
    ): MutationOutcome {
        val startedAtTick = store.monotonicTickNow()
        val result =
            try {
                performOnline()
            } catch (failure: Exception) {
                if (!failure.isNetworkOrConnectivityFailure()) throw failure
                throw IOException(
                    "$verbLabel $table in the \"$schema\" schema could not be sent, and a write to a schema " +
                        "other than the client's default is never queued: the queue replays to the " +
                        "default schema only",
                    failure,
                )
            }
        engineLogger.debug { "$verbLabel $schema.$table response: ${result.data}" }
        if (!result.wasRolledBack) store.markEveryQueryStale("$schema.$table", startedAtTick)
        return MutationOutcome.Applied(result)
    }

    /**
     * Asks for a pass when [identity] has mutations queued, which the head it just left held back.
     * Reads the owner index only, whatever other identities have queued.
     */
    private suspend fun requestPassIfPending(identity: QueueIdentity) {
        if (store.hasPendingMutationsOf(identity.storedValue)) requestSync()
    }

    /**
     * Logs, once per store, that the dry-run check cannot tell: such writes lose their offline
     * safety net.
     */
    private fun reportUnclassifiableWriteOnce() {
        if (store.claimUnclassifiableWriteReport()) {
            engineLogger.error {
                "Could not tell whether a write is a dry run; such writes are sent without being queued, " +
                    "so they fail rather than queue when offline"
            }
        }
    }

    /** A write sent directly, outside the queue, and the cache patch that follows its success. */
    private inner class DirectSend(
        private val verbLabel: String,
        private val table: String,
        private val performOnline: suspend () -> PostgrestResult,
        private val patchCache: suspend (result: PostgrestResult, startedAtTick: Long) -> Unit,
        private val startedAtTick: Long,
    ) {

        /**
         * Sends the write. A connectivity failure is logged with [whyNotQueued] and rethrown, like
         * every other failure.
         *
         * @param level The level to log a connectivity failure at.
         * @param whyNotQueued Why the write was not queued, for the log line.
         * @return [MutationOutcome.Applied].
         */
        suspend fun send(level: OfflineLogLevel, whyNotQueued: () -> String): MutationOutcome {
            val result =
                try {
                    performOnline()
                } catch (failure: Exception) {
                    if (failure.isNetworkOrConnectivityFailure())
                        engineLogger.log(level, whyNotQueued)
                    throw failure
                }
            return applied(result)
        }

        /** The outcome of a write the server applied with [result], its cache patch done. */
        suspend fun applied(result: PostgrestResult): MutationOutcome {
            engineLogger.debug { "$verbLabel $table response: ${result.data}" }
            overlay(result)
            return MutationOutcome.Applied(result)
        }

        /**
         * Patches the cache with [result], unless PostgREST rolled the write back or local data was
         * cleared since it started.
         */
        suspend fun overlay(result: PostgrestResult) {
            when {
                result.wasRolledBack ->
                    engineLogger.debug {
                        "$verbLabel $table was rolled back (a dry run); the cache is left as it was"
                    }

                store.wasClearedSince(startedAtTick) ->
                    engineLogger.info {
                        "$verbLabel $table returned after local data was cleared; the cache is left as it was"
                    }

                else -> patchCache(result, startedAtTick)
            }
        }
    }

    private fun preferResponseForCache(
        requestBody: JsonArray,
        responseData: String,
        keyColumns: List<String>,
    ): String = rowsForCachePatch(requestBody, responseData, json, keyColumns)

    companion object {

        /**
         * A standalone coordinator for a builder constructed directly, as tests do; the client
         * injects its own. Its engine scope contains any failure a share leaks.
         */
        private fun defaultLiveQueries(): LiveQueries =
            LiveQueries(newEngineScope(engineLogger = null))
    }
}
