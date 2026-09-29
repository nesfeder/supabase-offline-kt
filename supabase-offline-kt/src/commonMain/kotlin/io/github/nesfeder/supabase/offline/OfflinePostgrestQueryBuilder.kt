@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.db.EnqueueOutcome
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.currentQueueIdentity
import io.github.nesfeder.supabase.offline.internal.postgrest.AuthSessionState
import io.github.nesfeder.supabase.offline.internal.postgrest.DryRunProbe
import io.github.nesfeder.supabase.offline.internal.postgrest.DryRunVerdict
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.OfflineSelectResolver
import io.github.nesfeder.supabase.offline.internal.postgrest.PostgrestQueryBuilderWrapper
import io.github.nesfeder.supabase.offline.internal.postgrest.PostgrestQueryBuilderWrapperImpl
import io.github.nesfeder.supabase.offline.internal.postgrest.RefreshCoordinator
import io.github.nesfeder.supabase.offline.internal.postgrest.UnsupportedOfflineFilterException
import io.github.nesfeder.supabase.offline.internal.postgrest.buildDescriptorFromBuilder
import io.github.nesfeder.supabase.offline.internal.postgrest.buildDescriptorFromRequest
import io.github.nesfeder.supabase.offline.internal.postgrest.buildDescriptorStrictFromRequest
import io.github.nesfeder.supabase.offline.internal.postgrest.wasRolledBack
import io.github.nesfeder.supabase.offline.internal.util.httpStatusOrNull
import io.github.nesfeder.supabase.offline.internal.util.isNetworkOrConnectivityFailure
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
 */
public interface OfflinePostgrestQueryBuilder {

    /**
     * One-shot read that consults the network and the offline cache according to [readStrategy].
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
        request: SelectRequestBuilder.() -> Unit = {}
    ): PostgrestResult

    /**
     * Reactive read that surfaces per-query read+refresh state in-band as [OfflineReadResult]: the
     * background [RefreshState] (so a consumer can tell "refresh failed, showing stale/empty cache"
     * from "genuinely empty") and the typed "no data yet" state (a `null` result) that replaces the
     * old fabricated empty-array cache seed.
     *
     * Consumers that want today's instant-empty-state rendering map the no-data case to
     * `emptyList()` in their repository; consumers that want a Loading state until data is known
     * leave the no-data emissions unmapped. [RefreshState] is available for a future refresh
     * affordance and may be ignored today.
     *
     * @param columns Columns to select.
     * @param readStrategy Flow read strategy.
     * @param request PostgREST request builder (filters, ordering, …).
     * @return A flow of [OfflineReadResult] carrying payload and refresh state.
     */
    public fun selectAsFlow(
        columns: Columns = Columns.ALL,
        readStrategy: FlowReadStrategy = FlowReadStrategy.CacheFirstThenNetwork,
        request: SelectRequestBuilder.() -> Unit = {}
    ): Flow<OfflineReadResult>

    /**
     * Insert [body] rows, reporting whether the INSERT executed on the server
     * ([MutationOutcome.Applied], carrying the PostgREST response) or was queued for offline replay
     * ([MutationOutcome.Queued]) instead of collapsing a queued write into a synthetic empty result.
     *
     * @param body rows to insert, as a JSON array.
     * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
     * @param request request-configuration block (e.g. `select()`).
     * @return the mutation outcome.
     */
    public suspend fun insertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: InsertRequestBuilder.() -> Unit = {}
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
        request: InsertRequestBuilder.() -> Unit = {}
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
        request: InsertRequestBuilder.() -> Unit = {}
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
        request: UpsertRequestBuilder.() -> Unit = {}
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
        request: UpsertRequestBuilder.() -> Unit = {}
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
        request: UpsertRequestBuilder.() -> Unit = {}
    ): MutationOutcome

    /**
     * Update columns from [body], targeting rows via the [request] filter.
     *
     * @param body columns to update, as a JSON element.
     * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
     * @param deltaColumns columns of [body] that carry a change *relative* to the snapshot each
     *   declaration names, rather than an absolute value. Only consulted when the write is queued:
     *   the replay re-reads the targeted row and writes `serverCurrent + (desired - snapshot)`, so
     *   a change that landed while the mutation waited is carried rather than clobbered. See
     *   [NumericDeltaColumn]. Empty (the default) queues an ordinary absolute UPDATE.
     * @param request request-configuration block (e.g. `filter { … }; select()`).
     * @return the mutation outcome.
     */
    public suspend fun updateJson(
        body: JsonElement,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        deltaColumns: List<NumericDeltaColumn> = emptyList(),
        request: PostgrestRequestBuilder.() -> Unit = {}
    ): MutationOutcome

    /**
     * Update columns from a single [value], targeting rows via the [request] filter.
     *
     * @param serializer value serializer.
     * @param value columns to update.
     * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
     * @param deltaColumns delta-encoded columns; see [updateJson].
     * @param request request-configuration block.
     * @return the mutation outcome.
     */
    public suspend fun <T : Any> updateWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        deltaColumns: List<NumericDeltaColumn> = emptyList(),
        request: PostgrestRequestBuilder.() -> Unit = {}
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
        request: PostgrestRequestBuilder.() -> Unit = {}
    ): MutationOutcome
}

/**
 * Internal implementation that delegates to the online [PostgrestQueryBuilder] and augments it
 * with offline caching, mutation queuing, and overlay via [OfflineStore].
 */
internal class OfflinePostgrestQueryBuilderImpl(
    private val delegate: PostgrestQueryBuilderWrapper,
    private val store: OfflineStore,
    private val sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    /**
     * Snapshot of the auth session, forwarded to [OfflineSelectResolver], which reads it once
     * before each network SELECT to decide how an empty response is treated: retried while the
     * session restores, authoritative (cache-replacing) when authenticated, dropped when
     * unauthenticated. Defaults to always-[AuthSessionState.AUTHENTICATED] to preserve prior
     * behavior for tests and any caller that wires the builder up directly.
     */
    private val authSessionState: () -> AuthSessionState = { AuthSessionState.AUTHENTICATED },
    /**
     * Coordinator forwarded to [OfflineSelectResolver] for single-flight refresh sharing per
     * `(table, signature)` and the live-signature registry backing materialize-on-write.
     *
     * The real client passes its **client-scoped** instance so it is shared across every builder it
     * creates — single-flight per signature only works when one coordinator spans all builders, so
     * production must never rely on the default. The default builds a fresh per-builder coordinator
     * over a standalone scope purely so tests and direct construction can call
     * `OfflinePostgrestQueryBuilderImpl(delegate, store)` without wiring one up; the scope hosts
     * `WhileSubscribed(0, 0)` shares that tear down when collection stops, so nothing runs once a
     * short-lived subscription ends.
     */
    private val refreshCoordinator: RefreshCoordinator = defaultRefreshCoordinator(),
    /**
     * Internal edge-triggered recovery accelerator (auth restored / connectivity regained)
     * forwarded to [OfflineSelectResolver] to short-circuit a stalled refresh's backoff. Defaults
     * to an empty flow (recovery still happens on the capped schedule) for direct construction.
     */
    private val accelerator: Flow<Unit> = emptyFlow(),
    /**
     * The identity a write is made for, read as the write is issued and recorded as its owner when
     * it is queued; see [OfflineStore.enqueueOwnedBy]. `null` from the provider means it cannot be
     * told, and the write is then sent directly, never queued.
     */
    private val queueIdentity: suspend () -> QueueIdentity? = { delegate.postgrest.supabaseClient.currentQueueIdentity() },
    /**
     * Asks for a replay pass. Called when a write was queued behind pending writes, and when more
     * are pending after a write sent at the head, so they go out without waiting for the app. Does
     * nothing when built directly.
     */
    private val requestSync: () -> Unit = {},
    /** Tells a dry run from a write before the write is queued; see [DryRunProbe]. */
    private val dryRunProbe: DryRunProbe = delegate,
    /**
     * Sends a queued write at the head of its identity's queue. The real client passes its one
     * syncer, which every builder and replay pass share; built directly, the builder has its own.
     */
    private val headSender: MutationQueueReplayer = MutationQueueReplayer(
        store = store,
        supabase = delegate.postgrest.supabaseClient,
        sdkLogger = sdkLogger,
        currentIdentity = queueIdentity,
    ),
) : OfflinePostgrestQueryBuilder {

    constructor(
        delegate: PostgrestQueryBuilder,
        store: OfflineStore,
        sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
        authSessionState: () -> AuthSessionState = { AuthSessionState.AUTHENTICATED },
        refreshCoordinator: RefreshCoordinator = defaultRefreshCoordinator(),
        accelerator: Flow<Unit> = emptyFlow(),
        queueIdentity: suspend () -> QueueIdentity? = { delegate.postgrest.supabaseClient.currentQueueIdentity() },
        requestSync: () -> Unit = {},
        headSender: MutationQueueReplayer = MutationQueueReplayer(
            store = store,
            supabase = delegate.postgrest.supabaseClient,
            sdkLogger = sdkLogger,
            currentIdentity = queueIdentity,
        ),
    ) : this(
        delegate = PostgrestQueryBuilderWrapperImpl(delegate),
        store = store,
        sdkLogger = sdkLogger,
        authSessionState = authSessionState,
        refreshCoordinator = refreshCoordinator,
        accelerator = accelerator,
        queueIdentity = queueIdentity,
        requestSync = requestSync,
        headSender = headSender,
    )

    private val json: Json = OfflineJson
    private val selectResolver = OfflineSelectResolver(
        delegate = delegate,
        store = store,
        sdkLogger = sdkLogger,
        authSessionState = authSessionState,
        refreshCoordinator = refreshCoordinator,
        accelerator = accelerator,
    )

    override suspend fun select(
        columns: Columns,
        readStrategy: ReadStrategy,
        request: SelectRequestBuilder.() -> Unit
    ): PostgrestResult {
        return selectResolver.select(columns, readStrategy, request)
    }

    override fun selectAsFlow(
        columns: Columns,
        readStrategy: FlowReadStrategy,
        request: SelectRequestBuilder.() -> Unit
    ): Flow<OfflineReadResult> {
        return selectResolver.selectAsFlow(columns, readStrategy, request)
    }

    // INSERT -------------------------------------------------------------------

    override suspend fun insertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit
    ): MutationOutcome {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        sdkLogger.debug { "INSERT $table request body: $body" }
        return executeMutation(
            verbLabel = "INSERT",
            table = table,
            mutationStrategy = mutationStrategy,
            performOnline = { delegate.insert(body, request) },
            applyOnlineSuccessOverlay = { result, startedAtTick ->
                // Honor QueueIfOffline's "UI reflects immediately" promise on the online-success
                // path so reactive `selectAsFlow` observers re-emit without waiting for a re-fetch.
                // OnlineOnly callers opted out of this — leave their cache untouched.
                val insertBuilder = InsertRequestBuilder(delegate.schema, conversionMethod).apply(request)
                val descriptor = buildDescriptorFromBuilder(insertBuilder)
                store.applyLocalMutationToCache(
                    table = table,
                    operation = MutationOperation.INSERT,
                    bodyJson = preferResponseForCache(body, result.data),
                    filterJson = descriptor?.let { FilterEngine.encode(it, json) },
                    defaultToNull = insertBuilder.defaultToNull,
                    startedAtTick = startedAtTick,
                )
            },
            captureForQueue = {
                val insertBuilder = InsertRequestBuilder(delegate.schema, conversionMethod).apply(request)
                val descriptor = buildDescriptorFromBuilder(insertBuilder)
                QueueCapture.Queueable {
                    enqueueInsert(
                        table = table,
                        body = body,
                        defaultToNull = insertBuilder.defaultToNull,
                        filter = descriptor
                    )
                }
            },
        )
    }

    override suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit
    ): MutationOutcome {
        val listSerializer = ListSerializer(serializer)
        val body = json.encodeToJsonElement(listSerializer, values).jsonArray
        return insertJson(body, mutationStrategy, request)
    }

    override suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit
    ): MutationOutcome {
        val element = json.encodeToJsonElement(serializer, value)
        val body = buildJsonArray { add(element) }
        return insertJson(body, mutationStrategy, request)
    }

    // UPSERT -------------------------------------------------------------------

    override suspend fun upsertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit
    ): MutationOutcome {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        sdkLogger.debug { "UPSERT $table request body: $body" }
        return executeMutation(
            verbLabel = "UPSERT",
            table = table,
            mutationStrategy = mutationStrategy,
            performOnline = { delegate.upsert(body, request) },
            applyOnlineSuccessOverlay = { result, startedAtTick ->
                val upsertBuilder = UpsertRequestBuilder(delegate.schema, conversionMethod).apply(request)
                val descriptor = buildDescriptorFromBuilder(upsertBuilder)
                store.applyLocalMutationToCache(
                    table = table,
                    operation = MutationOperation.UPSERT,
                    bodyJson = preferResponseForCache(body, result.data),
                    filterJson = descriptor?.let { FilterEngine.encode(it, json) },
                    defaultToNull = upsertBuilder.defaultToNull,
                    onConflict = upsertBuilder.onConflict,
                    ignoreDuplicates = upsertBuilder.ignoreDuplicates,
                    startedAtTick = startedAtTick,
                )
            },
            captureForQueue = {
                val upsertBuilder = UpsertRequestBuilder(delegate.schema, conversionMethod).apply(request)
                val descriptor = buildDescriptorFromBuilder(upsertBuilder)
                QueueCapture.Queueable {
                    enqueueUpsert(
                        table = table,
                        bodyJson = body,
                        defaultToNull = upsertBuilder.defaultToNull,
                        onConflict = upsertBuilder.onConflict,
                        ignoreDuplicates = upsertBuilder.ignoreDuplicates,
                        filter = descriptor
                    )
                }
            },
        )
    }

    override suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit
    ): MutationOutcome {
        val listSerializer = ListSerializer(serializer)
        val body = json.encodeToJsonElement(listSerializer, values).jsonArray
        return upsertJson(body, mutationStrategy, request)
    }

    override suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit
    ): MutationOutcome {
        val element = json.encodeToJsonElement(serializer, value)
        val body = buildJsonArray { add(element) }
        return upsertJson(body, mutationStrategy, request)
    }

    // UPDATE -------------------------------------------------------------------

    override suspend fun updateJson(
        body: JsonElement,
        mutationStrategy: MutationStrategy,
        deltaColumns: List<NumericDeltaColumn>,
        request: PostgrestRequestBuilder.() -> Unit
    ): MutationOutcome {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        sdkLogger.debug { "UPDATE $table request body: $body" }
        return executeMutation(
            verbLabel = "UPDATE",
            table = table,
            mutationStrategy = mutationStrategy,
            // [deltaColumns] deliberately does not touch the online path: the write lands now,
            // against the row the caller just read, so there is no queue window to rebase across
            // — and PostgREST cannot express `col = col + x` in a PATCH anyway.
            performOnline = { delegate.update(body, request) },
            applyOnlineSuccessOverlay = { _, startedAtTick ->
                // Online-success overlay keeps the lenient capture: it never replays, so an
                // un-representable filter is harmless here (worst case it overlays nothing).
                val descriptor = buildDescriptorFromRequest(
                    defaultSchema = delegate.schema,
                    conversionMethod = conversionMethod,
                    request = request
                )
                store.applyLocalMutationToCache(
                    table = table,
                    operation = MutationOperation.UPDATE,
                    bodyJson = body.toString(),
                    filterJson = descriptor?.let { FilterEngine.encode(it, json) },
                    startedAtTick = startedAtTick,
                )
            },
            captureForQueue = {
                captureFilteredWrite(conversionMethod, request) { descriptor ->
                    enqueueUpdate(
                        table = table,
                        body = body,
                        filter = descriptor,
                        deltaColumns = deltaColumns
                    )
                }
            },
        )
    }

    override suspend fun <T : Any> updateWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        deltaColumns: List<NumericDeltaColumn>,
        request: PostgrestRequestBuilder.() -> Unit
    ): MutationOutcome {
        val element = json.encodeToJsonElement(serializer, value)
        return updateJson(element, mutationStrategy, deltaColumns, request)
    }

    // DELETE -------------------------------------------------------------------

    override suspend fun delete(
        mutationStrategy: MutationStrategy,
        request: PostgrestRequestBuilder.() -> Unit
    ): MutationOutcome {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        sdkLogger.debug { "DELETE $table attempting network" }
        return executeMutation(
            verbLabel = "DELETE",
            table = table,
            mutationStrategy = mutationStrategy,
            performOnline = { delegate.delete(request) },
            applyOnlineSuccessOverlay = { _, startedAtTick ->
                val descriptor = buildDescriptorFromRequest(
                    defaultSchema = delegate.schema,
                    conversionMethod = conversionMethod,
                    request = request
                )
                store.applyLocalMutationToCache(
                    table = table,
                    operation = MutationOperation.DELETE,
                    bodyJson = "{}",
                    filterJson = descriptor?.let { FilterEngine.encode(it, json) },
                    startedAtTick = startedAtTick,
                )
            },
            captureForQueue = {
                captureFilteredWrite(conversionMethod, request) { descriptor ->
                    enqueueDelete(
                        table = table,
                        filter = descriptor
                    )
                }
            },
        )
    }

    // --------------------------------------------------------------------------

    /**
     * How a write would be queued, decided before it is sent: either the action that queues it, or
     * why it cannot be queued faithfully.
     */
    private sealed interface QueueCapture {

        /** The write can be queued; [enqueue] inserts its mutation row and returns the id. */
        class Queueable(val enqueue: suspend OfflineStore.() -> Long) : QueueCapture

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
     * rather than queued and refused at replay.
     */
    private fun captureFilteredWrite(
        conversionMethod: PropertyConversionMethod,
        request: PostgrestRequestBuilder.() -> Unit,
        enqueue: suspend OfflineStore.(FilterDescriptor) -> Long,
    ): QueueCapture {
        val descriptor = try {
            buildDescriptorStrictFromRequest(
                defaultSchema = delegate.schema,
                conversionMethod = conversionMethod,
                request = request
            )
        } catch (lossy: UnsupportedOfflineFilterException) {
            return QueueCapture.Refused(reason = "its filter cannot be captured exactly", detail = lossy.message)
        }
        return when {
            descriptor == null -> QueueCapture.Refused("a write without a filter would reach every row")
            !FilterEngine.replaysFully(descriptor) -> QueueCapture.Refused("its filter cannot be replayed in full")
            else -> QueueCapture.Queueable { enqueue(descriptor) }
        }
    }

    /**
     * Shared body for the offline-aware mutation verbs.
     *
     * Under [MutationStrategy.OnlineOnly] the write is sent and its result returned; nothing else.
     * It never touches the queue, so it can overtake writes still pending in it.
     *
     * Under [MutationStrategy.QueueIfOffline] every write goes through the queue, write-ahead:
     * - It is persisted **first**, which fixes its place in its identity's queue, and is then sent at
     *   once only if it is at the head — nothing earlier of the same identity pending, in any table,
     *   and nothing sending it already. On success the cache is patched with the response and the
     *   write leaves the queue: [MutationOutcome.Applied]. On a connectivity failure or a timeout it
     *   stays queued: [MutationOutcome.Queued]. On any other failure — a refusal or a `5xx` — it
     *   leaves the queue and the failure is thrown, as before.
     * - Not at the head, it stays queued behind the earlier writes, a replay pass is requested, and
     *   it reports [MutationOutcome.Queued]. A refusal then lands on the rejected list, not here.
     * - A caller cancelled while its write is on the wire leaves the write queued, since whether the
     *   server got it is not known; a pass is requested and it replays.
     *
     * A few writes cannot be queued faithfully, and are sent directly instead, bypassing the queue —
     * so they can overtake pending writes, and fail rather than queue when offline:
     * - an UPDATE or DELETE whose filter the queue cannot record exactly (see [captureFilteredWrite]);
     * - a write whose identity cannot be told (the session restoring or refreshing);
     * - a dry run (`dryRun()`): the queue does not carry `Prefer`, so a queued dry run would replay as
     *   a real write. It is classified before it is persisted, and a write that cannot be classified
     *   is treated the same way — never persisted — and reported once at ERROR;
     * - a write started before local data was cleared, or while the queue's owner records cannot be
     *   read.
     *
     * On a directly sent write, the response decides the overlay: a write PostgREST reports it rolled
     * back (`Preference-Applied: tx=rollback`) is not overlaid, and every other one is — a dry run on
     * a server that ignores `tx=rollback`, as PostgREST does by default, commits.
     *
     * The request lambda runs up to three times: to capture the write for the queue, to classify it,
     * and to send it.
     *
     * @param verbLabel human-readable verb name for log lines (e.g. `"INSERT"`).
     * @param table target table name, for log lines.
     * @param mutationStrategy the caller's offline strategy.
     * @param performOnline issues the live PostgREST mutation and returns its result.
     * @param applyOnlineSuccessOverlay patches the cache with the result, given the tick the write
     *   started at; invoked only for [MutationStrategy.QueueIfOffline].
     * @param captureForQueue decides how the write would be queued; see [QueueCapture].
     * @return [MutationOutcome.Applied] when the server executed the mutation, or
     *   [MutationOutcome.Queued] when it waits in the queue.
     */
    private suspend fun executeMutation(
        verbLabel: String,
        table: String,
        mutationStrategy: MutationStrategy,
        performOnline: suspend () -> PostgrestResult,
        applyOnlineSuccessOverlay: suspend (result: PostgrestResult, startedAtTick: Long) -> Unit,
        captureForQueue: () -> QueueCapture,
    ): MutationOutcome {
        if (mutationStrategy == MutationStrategy.OnlineOnly) {
            val result = performOnline()
            sdkLogger.debug { "$verbLabel $table response: ${result.data}" }
            return MutationOutcome.Applied(result)
        }

        val startedAtTick = store.monotonicTickNow()
        // Taken as the write is issued: a session switch while it is in flight must not re-own it.
        val identity = queueIdentity()
        val direct = DirectSend(verbLabel, table, performOnline, applyOnlineSuccessOverlay, startedAtTick)

        val queueable = when (val capture = captureForQueue()) {
            is QueueCapture.Queueable -> capture
            // Replaying it would target a broader row set than the caller wrote.
            is QueueCapture.Refused -> {
                capture.detail?.let { detail -> sdkLogger.debug { "Offline $verbLabel for $table would not be queued: $detail" } }
                return direct.send(OfflineLogLevel.ERROR) { "Refusing to queue offline $verbLabel for $table: ${capture.reason}" }
            }
        }
        if (identity == null) {
            return direct.send(OfflineLogLevel.WARNING) { "Not queueing offline $verbLabel for $table: the session's identity cannot be told" }
        }
        when (val verdict = dryRunProbe.classify(performOnline)) {
            DryRunVerdict.NotDryRun -> Unit
            DryRunVerdict.DryRun ->
                return direct.send(OfflineLogLevel.INFO) { "Not queueing offline $verbLabel for $table: a dry run is never queued" }
            // The write's own failure, before any request: sending it directly reports it as it is.
            is DryRunVerdict.NotBuilt ->
                return direct.send(OfflineLogLevel.INFO) { "Not queueing offline $verbLabel for $table: its request could not be built" }
            DryRunVerdict.Unavailable -> {
                reportUnclassifiableWriteOnce()
                return direct.send(OfflineLogLevel.WARNING) { "Not queueing offline $verbLabel for $table: it may be a dry run" }
            }
            is DryRunVerdict.Sent -> {
                reportUnclassifiableWriteOnce()
                return direct.applied(verdict.result)
            }
        }

        val mutationId = when (val enqueued = store.enqueueOwnedBy(identity.storedValue, startedAtTick) { queueable.enqueue(this) }) {
            is EnqueueOutcome.Queued -> enqueued.id
            EnqueueOutcome.LocalDataCleared ->
                return direct.send(OfflineLogLevel.INFO) { "Not queueing offline $verbLabel for $table: local data was cleared while it was in flight" }
            EnqueueOutcome.OwnershipUnreadable ->
                return direct.send(OfflineLogLevel.WARNING) { "Not queueing offline $verbLabel for $table: the queue's owner records cannot be read" }
        }

        val sent = try {
            headSender.sendAtHead(mutationId, identity, performOnline) { result -> direct.overlay(result) }
        } catch (cancellation: CancellationException) {
            // Cancelled on the wire: whether the server got it is not known, so it stays queued, and
            // a pass sends it.
            requestSync()
            throw cancellation
        }
        return when (sent) {
            MutationQueueReplayer.HeadSendOutcome.NotAtHead -> {
                sdkLogger.info { "$verbLabel $table queued behind pending mutations" }
                requestSync()
                MutationOutcome.Queued
            }

            is MutationQueueReplayer.HeadSendOutcome.Sent -> {
                sdkLogger.debug { "$verbLabel $table response: ${sent.result.data}" }
                requestPassIfPending(identity)
                MutationOutcome.Applied(sent.result)
            }

            is MutationQueueReplayer.HeadSendOutcome.StaysQueued -> {
                sdkLogger.info {
                    if (sent.cause.isNetworkOrConnectivityFailure()) {
                        "$verbLabel $table queued offline (network unavailable)"
                    } else {
                        "$verbLabel $table queued (the server could not run it: ${sent.cause.httpStatusOrNull()})"
                    }
                }
                MutationOutcome.Queued
            }

            is MutationQueueReplayer.HeadSendOutcome.Refused -> {
                requestPassIfPending(identity)
                throw sent.cause
            }

            is MutationQueueReplayer.HeadSendOutcome.Cleared -> {
                sdkLogger.info { "$verbLabel $table returned after local data was cleared; the cache is left as it was" }
                requestPassIfPending(identity)
                sent.result?.let { result -> MutationOutcome.Applied(result) } ?: throw checkNotNull(sent.cause)
            }
        }
    }

    /** Asks for a pass when [identity] has mutations queued, which the head it just left held back. */
    private suspend fun requestPassIfPending(identity: QueueIdentity) {
        if (store.pendingMutationsOf(identity.storedValue)?.isNotEmpty() == true) requestSync()
    }

    /** Logs, once per store, that the dry-run check cannot tell: such writes lose their offline safety net. */
    private fun reportUnclassifiableWriteOnce() {
        if (store.claimUnclassifiableWriteReport()) {
            sdkLogger.error {
                "Could not tell whether a write is a dry run; such writes are sent without being queued, " +
                    "so they fail rather than queue when offline"
            }
        }
    }

    /**
     * A write sent directly, outside the queue, and the cache patch that follows its success.
     */
    private inner class DirectSend(
        private val verbLabel: String,
        private val table: String,
        private val performOnline: suspend () -> PostgrestResult,
        private val applyOnlineSuccessOverlay: suspend (result: PostgrestResult, startedAtTick: Long) -> Unit,
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
            val result = try {
                performOnline()
            } catch (failure: Exception) {
                if (failure.isNetworkOrConnectivityFailure()) sdkLogger.log(level, whyNotQueued)
                throw failure
            }
            return applied(result)
        }

        /** The outcome of a write the server applied with [result], its cache patch done. */
        suspend fun applied(result: PostgrestResult): MutationOutcome {
            sdkLogger.debug { "$verbLabel $table response: ${result.data}" }
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
                    sdkLogger.debug { "$verbLabel $table was rolled back (a dry run); the cache is left as it was" }

                store.wasClearedSince(startedAtTick) ->
                    sdkLogger.info { "$verbLabel $table returned after local data was cleared; the cache is left as it was" }

                else -> applyOnlineSuccessOverlay(result, startedAtTick)
            }
        }
    }

    private fun preferResponseForCache(requestBody: JsonArray, responseData: String): String =
        preferResponseForCacheOverlay(requestBody, responseData, json)

    companion object {

        /**
         * A fresh, standalone [RefreshCoordinator] for direct/test construction only — the real
         * client always injects its client-scoped instance so single-flight refresh spans every
         * builder. The scope is a plain [SupervisorJob] on [Dispatchers.Default]; because the
         * coordinator's shares use `WhileSubscribed(0, 0)`, no coroutine outlives an active
         * collection, so a short-lived subscription leaves nothing running behind it.
         */
        private fun defaultRefreshCoordinator(): RefreshCoordinator =
            RefreshCoordinator(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}

/**
 * Prefer the server response payload as the mutation body for cache overlay when it carries
 * usable row data; otherwise fall back to the original request body.
 *
 * For INSERT/UPSERT the request body typically omits server-assigned columns (`id`,
 * `created_at`, `updated_at`). If a live `selectAsFlow` collector was subscribed with
 * `CacheFirstThenNetwork` and the parallel network SELECT hasn't yet written the full server
 * row, [io.github.nesfeder.supabase.offline.internal.db.OfflineStore.applyLocalMutationToCache] will overlay
 * the request body onto the cache — producing a row where the assigned id is missing
 * (decodes as an empty string via @Serializable defaults). Any downstream join keyed on that
 * id (e.g., `attachmentsByNoteId[note.id]`) then silently misses, so the UI renders the row as
 * "saved but empty" until a tab-switch forces a fresh subscription.
 *
 * When the caller asked for the row back (`select()` on the request), the server response is
 * the authoritative post-write shape, so overlaying that into the cache keeps live collectors
 * consistent with what the server now has. When the response is empty (e.g. a `minimal`
 * INSERT that didn't request returning rows), we fall back to the request body — which is the
 * legacy behavior and matches the existing INSERT/UPSERT overlay semantics.
 *
 * Top-level so unit tests can exercise the branches without faking a full PostgREST delegate.
 */
internal fun preferResponseForCacheOverlay(
    requestBody: JsonArray,
    responseData: String,
    json: Json,
): String {
    return try {
        val element = json.parseToJsonElement(responseData)
        if (element is JsonArray && element.isNotEmpty()) responseData else requestBody.toString()
    } catch (_: Exception) {
        requestBody.toString()
    }
}
