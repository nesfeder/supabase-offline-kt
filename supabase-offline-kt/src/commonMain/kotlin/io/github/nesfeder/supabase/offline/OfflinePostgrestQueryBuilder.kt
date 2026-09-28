@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.db.EnqueueOutcome
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.outbox.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.outbox.currentQueueIdentity
import io.github.nesfeder.supabase.offline.internal.postgrest.AuthSessionState
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.OfflineSelectResolver
import io.github.nesfeder.supabase.offline.internal.postgrest.PostgrestQueryBuilderWrapper
import io.github.nesfeder.supabase.offline.internal.postgrest.PostgrestQueryBuilderWrapperImpl
import io.github.nesfeder.supabase.offline.internal.postgrest.PreferHeaderProbe
import io.github.nesfeder.supabase.offline.internal.postgrest.RefreshCoordinator
import io.github.nesfeder.supabase.offline.internal.postgrest.UnsupportedOfflineFilterException
import io.github.nesfeder.supabase.offline.internal.postgrest.buildDescriptorFromBuilder
import io.github.nesfeder.supabase.offline.internal.postgrest.buildDescriptorFromRequest
import io.github.nesfeder.supabase.offline.internal.postgrest.buildDescriptorStrictFromRequest
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
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
 * [MutationOutcome.Applied] carrying the live response, while a write queued because the network was
 * unavailable reports [MutationOutcome.Queued]. Callers that read the written row back branch on the
 * outcome; callers that fire-and-forget can ignore it. Use [MutationOutcome.resultOrNull] for the
 * "result or null when queued" shape.
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
     * The identity a write is made for, recorded as the queue's owner when the write is queued; see
     * [OfflineStore.enqueueOwnedBy]. `null` from the provider means it cannot be told, and the
     * write is then not queued.
     */
    private val queueIdentity: suspend () -> QueueIdentity? = { delegate.postgrest.supabaseClient.currentQueueIdentity() },
    /**
     * Asks for a replay pass. Called after a write made while online joined the queue behind
     * pending writes, so it goes out as soon as they have. Does nothing when built directly.
     */
    private val requestSync: () -> Unit = {},
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
    ) : this(
        PostgrestQueryBuilderWrapperImpl(delegate),
        store,
        sdkLogger,
        authSessionState,
        refreshCoordinator,
        accelerator,
        queueIdentity,
        requestSync,
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
            applyOnlineSuccessOverlay = { result ->
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
            applyOnlineSuccessOverlay = { result ->
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
            applyOnlineSuccessOverlay = {
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
            applyOnlineSuccessOverlay = {
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

        /** The write can be queued; [enqueue] inserts its queue row and returns the id. */
        class Queueable(val enqueue: suspend OfflineStore.() -> Long) : QueueCapture

        /** The write cannot be queued faithfully, for [reason]. */
        class Refused(val reason: String) : QueueCapture
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
            return QueueCapture.Refused(lossy.message.orEmpty())
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
     *
     * Under [MutationStrategy.QueueIfOffline]:
     * - A write to a table that already has pending writes **joins the queue** behind them and a
     *   replay pass is requested, so it cannot overtake them: sent straight away it would land
     *   first, and the older write would replay over it. It is reported [MutationOutcome.Queued].
     * - Otherwise it is sent. On success its result is overlaid onto the cache (see
     *   [applyOnlineSuccessOverlay]) and [MutationOutcome.Applied] returned.
     * - On a connectivity failure it is queued and [MutationOutcome.Queued] returned — unless it
     *   cannot be queued faithfully, in which case the original failure is rethrown.
     *
     * A write built with `dryRun()` is treated as `OnlineOnly` whatever the strategy: it never joins
     * the queue, is never queued, and its result is never overlaid, since the server rolled it back.
     * A write whose request started before local data was cleared is neither overlaid nor queued:
     * it belongs to the identity that was torn down. And a write is not queued while the queue holds
     * another identity's pending writes.
     *
     * @param verbLabel human-readable verb name for log lines (e.g. `"INSERT"`).
     * @param table target table name, for log lines.
     * @param mutationStrategy the caller's offline strategy.
     * @param performOnline issues the live PostgREST mutation and returns its result.
     * @param applyOnlineSuccessOverlay applies the online-success cache overlay; invoked only for
     *   [MutationStrategy.QueueIfOffline].
     * @param captureForQueue decides how the write would be queued; see [QueueCapture].
     * @return [MutationOutcome.Applied] when the server executed the mutation, or
     *   [MutationOutcome.Queued] when it was queued for replay.
     */
    private suspend fun executeMutation(
        verbLabel: String,
        table: String,
        mutationStrategy: MutationStrategy,
        performOnline: suspend () -> PostgrestResult,
        applyOnlineSuccessOverlay: suspend (PostgrestResult) -> Unit,
        captureForQueue: () -> QueueCapture,
    ): MutationOutcome {
        if (mutationStrategy == MutationStrategy.OnlineOnly) {
            val result = performOnline()
            sdkLogger.debug { "$verbLabel $table response: ${result.data}" }
            return MutationOutcome.Applied(result)
        }

        val startedAtTick = store.monotonicTickNow()
        val capture = captureForQueue()

        if (capture is QueueCapture.Queueable && store.pendingMutationCountForTable(table) > 0 &&
            delegate.requestsRollback(performOnline) != true
        ) {
            if (enqueue(capture, startedAtTick) is EnqueueOutcome.Queued) {
                sdkLogger.info { "$verbLabel $table queued behind pending mutations" }
                requestSync()
                return MutationOutcome.Queued
            }
            // The queue holds another identity's writes (or the identity cannot be told), so this
            // one cannot join them; it goes straight to the server under the current session.
        }

        val probe = PreferHeaderProbe(shortCircuit = false)
        val result = try {
            withContext(probe) { performOnline() }
        } catch (exception: Exception) {
            if (!exception.isNetworkOrConnectivityFailure()) throw exception
            return queueAfterConnectivityFailure(verbLabel, table, capture, exception, probe, startedAtTick)
        }
        sdkLogger.debug { "$verbLabel $table response: ${result.data}" }

        when {
            probe.requestedRollback ->
                sdkLogger.debug { "$verbLabel $table was a dry run; the cache is left as it was" }

            store.wasClearedSince(startedAtTick) ->
                sdkLogger.info { "$verbLabel $table returned after local data was cleared; the cache is left as it was" }

            else -> applyOnlineSuccessOverlay(result)
        }
        return MutationOutcome.Applied(result)
    }

    /**
     * Queues a write whose request failed for connectivity, or rethrows [failure] when it must not
     * be queued.
     */
    private suspend fun queueAfterConnectivityFailure(
        verbLabel: String,
        table: String,
        capture: QueueCapture,
        failure: Exception,
        probe: PreferHeaderProbe,
        startedAtTick: Long,
    ): MutationOutcome {
        if (probe.requestedRollback) {
            sdkLogger.info { "Refusing to queue offline $verbLabel for $table: a dry run is never queued" }
            throw failure
        }
        val queueable = when (capture) {
            is QueueCapture.Queueable -> capture
            is QueueCapture.Refused -> {
                // Replaying it would target a broader row set than the caller wrote. Fail the
                // original write instead so the caller sees it did not succeed.
                sdkLogger.error { "Refusing to queue offline $verbLabel for $table: ${capture.reason}" }
                throw failure
            }
        }
        when (enqueue(queueable, startedAtTick)) {
            is EnqueueOutcome.Queued -> Unit

            EnqueueOutcome.LocalDataCleared -> {
                sdkLogger.info { "Not queueing offline $verbLabel for $table: local data was cleared while it was in flight" }
                throw failure
            }

            EnqueueOutcome.QueueOwnedByAnotherIdentity -> {
                sdkLogger.warning {
                    "Not queueing offline $verbLabel for $table: the queue holds another identity's pending " +
                        "mutations, or the current identity cannot be told"
                }
                throw failure
            }
        }
        sdkLogger.info { "$verbLabel $table queued offline (network unavailable)" }
        return MutationOutcome.Queued
    }

    /**
     * Queues [capture] under the current identity, unless local data was cleared since
     * [startedAtTick].
     *
     * @return What happened; [EnqueueOutcome.QueueOwnedByAnotherIdentity] also when the current
     *   identity cannot be told.
     */
    private suspend fun enqueue(capture: QueueCapture.Queueable, startedAtTick: Long): EnqueueOutcome {
        val identity = queueIdentity() ?: return EnqueueOutcome.QueueOwnedByAnotherIdentity
        return store.enqueueOwnedBy(identity.storedValue, startedAtTick) { capture.enqueue(this) }
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
