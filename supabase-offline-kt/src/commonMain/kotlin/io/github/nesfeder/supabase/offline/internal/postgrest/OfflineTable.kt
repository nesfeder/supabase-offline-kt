// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

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
import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.internal.ClientOperationGate
import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.TransportClosedException
import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.cache.CachePatcher
import io.github.nesfeder.supabase.offline.internal.cache.QueryCache
import io.github.nesfeder.supabase.offline.internal.cache.QueryOverlay
import io.github.nesfeder.supabase.offline.internal.cache.returnedRowsOf
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.httpStatusOrNull
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.EnqueueOutcome
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.currentQueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import io.github.nesfeder.supabase.offline.internal.mutationqueue.toPendingMutation
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
import io.github.nesfeder.supabase.offline.internal.newEngineScope
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * One table as the engine reads and writes it, behind the public builder: reads through the cache
 * ([QueryReader]), writes through the mutation queue or sent directly, and the cache patched with
 * what the server applied. It sends its requests through [RemoteTable], which tests script.
 */
internal class OfflineTable(
    private val delegate: RemoteTable,
    /** The cached queries a read is answered from and written to. */
    private val queryCache: QueryCache,
    /** The reads served with the queued mutations laid over them. */
    private val queryOverlay: QueryOverlay,
    /** Where a write is queued, and whose head decides whether it is sent at once. */
    private val mutationQueue: MutationQueue,
    /** Patches a write the server applied into the cached queries. */
    private val cachePatcher: CachePatcher,
    /** The tick a write starts at, and whether a clear has passed since. */
    private val freshness: CacheFreshness,
    /** Each table's key columns, which locate a write's rows. */
    private val primaryKeys: PrimaryKeys,
    private val engineLogger: EngineLogger = EngineLogger(null, EngineLogLevel.INFO),
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
     * over a standalone scope purely so tests and direct construction need not wire one up; the
     * scope hosts `WhileSubscribed(0, 0)` shares that tear down when collection stops, so nothing
     * runs once a short-lived subscription ends.
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
     * it is queued; see [MutationQueue.enqueueOwnedBy]. `null` from the provider means it cannot be
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
            mutationQueue = mutationQueue,
            freshness = freshness,
            supabase = delegate.postgrest.supabaseClient,
            engineLogger = engineLogger,
            currentIdentity = queueIdentity,
        ),
    /** The client's gate: every read and write passes it, so the client's close waits for them. */
    private val operationGate: ClientOperationGate = ClientOperationGate(),
    /** The time a read compares a cached row's age with; see [QueryReader]. */
    private val clock: Clock = Clock.System,
    /** Told when a write sent at once left its identity's queue empty. */
    private val queueEmptied: suspend () -> Unit = {},
    /** Whether the network is known down, for a reactive read's refresh; see [QueryReader]. */
    private val networkAccess: NetworkAccess = NetworkAccess(),
) {

    constructor(
        delegate: PostgrestQueryBuilder,
        queryCache: QueryCache,
        queryOverlay: QueryOverlay,
        mutationQueue: MutationQueue,
        cachePatcher: CachePatcher,
        freshness: CacheFreshness,
        primaryKeys: PrimaryKeys,
        engineLogger: EngineLogger = EngineLogger(null, EngineLogLevel.INFO),
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
                mutationQueue = mutationQueue,
                freshness = freshness,
                supabase = delegate.postgrest.supabaseClient,
                engineLogger = engineLogger,
                currentIdentity = queueIdentity,
            ),
        operationGate: ClientOperationGate = ClientOperationGate(),
        readShapePostgrest: Postgrest = readShapePostgrestOf(delegate.postgrest),
        clock: Clock = Clock.System,
        queueEmptied: suspend () -> Unit = {},
        networkAccess: NetworkAccess = NetworkAccess(),
    ) : this(
        delegate = SupabaseRemoteTable(delegate, readShapePostgrest, networkAccess),
        queryCache = queryCache,
        queryOverlay = queryOverlay,
        mutationQueue = mutationQueue,
        cachePatcher = cachePatcher,
        freshness = freshness,
        primaryKeys = primaryKeys,
        engineLogger = engineLogger,
        requestSession = requestSession,
        trustPolicy = trustPolicy,
        liveQueries = liveQueries,
        wakeUps = wakeUps,
        queueIdentity = queueIdentity,
        requestSync = requestSync,
        headSender = headSender,
        operationGate = operationGate,
        clock = clock,
        queueEmptied = queueEmptied,
        networkAccess = networkAccess,
    )

    /** The table the requests target. */
    val table: String
        get() = delegate.table

    /** The schema the requests target. */
    val schema: String
        get() = delegate.schema

    /** The PostgREST plugin the requests go through. */
    val postgrest: Postgrest
        get() = delegate.postgrest

    private val json: Json = EngineJson
    private val queryReader =
        QueryReader(
            delegate = delegate,
            queryCache = queryCache,
            queryOverlay = queryOverlay,
            mutationQueue = mutationQueue,
            freshness = freshness,
            engineLogger = engineLogger,
            nowMillis = { clock.now().toEpochMilliseconds() },
            requestSession = requestSession,
            trustPolicy = trustPolicy,
            liveQueries = liveQueries,
            wakeUps = wakeUps,
            networkAccess = networkAccess,
        )

    /** A one-shot read, through the gate; see `OfflinePostgrestQueryBuilder.select`. */
    suspend fun select(
        columns: Columns = Columns.ALL,
        readStrategy: ReadStrategy = ReadStrategy.NetworkFirst(),
        request: SelectRequestBuilder.() -> Unit = {},
    ): PostgrestResult = operationGate.call { queryReader.select(columns, readStrategy, request) }

    /** A reactive read, counted by the gate while collected; see `OfflinePostgrestQueryBuilder`. */
    fun selectAsFlow(
        columns: Columns = Columns.ALL,
        readStrategy: FlowReadStrategy = FlowReadStrategy.CacheFirstThenNetwork,
        request: SelectRequestBuilder.() -> Unit = {},
    ): Flow<QuerySnapshot> {
        // Every strategy one way: counted while collected, refused in band once closed, and ended
        // by close() however its collector is stuck
        return operationGate.callCollecting(
            queryReader.selectAsFlow(columns, readStrategy, request)
        )
    }

    // INSERT -------------------------------------------------------------------

    /** Inserts the rows of [body], sent or queued as [mutationStrategy] says. */
    suspend fun insert(
        body: JsonArray,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: InsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        engineLogger.debug { "INSERT $table request body: $body" }
        // One build of the request serves its schema, its queue capture and the cache patch
        val built = lazy { InsertRequestBuilder(delegate.schema, conversionMethod).apply(request) }
        return executeMutation(
            operationLabel = "INSERT",
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
                cachePatcher.patchCachedQueries(
                    table = table,
                    operation = MutationOperation.INSERT,
                    bodyJson = preferResponseForCache(body, result.data, primaryKeys.of(table)),
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

    // UPSERT -------------------------------------------------------------------

    /** Upserts the rows of [body], sent or queued as [mutationStrategy] says. */
    suspend fun upsert(
        body: JsonArray,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: UpsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        engineLogger.debug { "UPSERT $table request body: $body" }
        // One build of the request serves its schema, its queue capture and the cache patch
        val built = lazy { UpsertRequestBuilder(delegate.schema, conversionMethod).apply(request) }
        return executeMutation(
            operationLabel = "UPSERT",
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
                        ?.takeIf { it.isNotEmpty() } ?: primaryKeys.of(table)
                cachePatcher.patchCachedQueries(
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

    // UPDATE -------------------------------------------------------------------

    /** Sets [body] on the rows [request] filters, sent or queued as [mutationStrategy] says. */
    suspend fun update(
        body: JsonElement,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: PostgrestRequestBuilder.() -> Unit = {},
    ): MutationOutcome {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        engineLogger.debug { "UPDATE $table request body: $body" }
        // One build of the request serves its schema, its queue capture and the cache patch
        val built = lazy { SelectRequestBuilder(delegate.schema, conversionMethod).apply(request) }
        return executeMutation(
            operationLabel = "UPDATE",
            table = table,
            mutationStrategy = mutationStrategy,
            writeSchema = { built.value.schema },
            perform = { handle -> handle.update(body, request) },
            patchCache = { result, startedAtTick ->
                // Asked for rows back, the response names every row the update changed, none when
                // it is empty; each carrying its key is patched by it alone, in one pass
                val returnsRows = built.value.returning is Returning.Representation
                val keyColumns = primaryKeys.of(table)
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
                cachePatcher.patchReturnedRows(table, body, result.data, startedAtTick)
            },
            captureForQueue = {
                captureFilteredWrite(built.value) { descriptor ->
                    enqueueUpdate(table = table, body = body, filter = descriptor)
                }
            },
        )
    }

    // DELETE -------------------------------------------------------------------

    /** Deletes the rows [request] filters, sent or queued as [mutationStrategy] says. */
    suspend fun delete(
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: PostgrestRequestBuilder.() -> Unit = {},
    ): MutationOutcome {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        engineLogger.debug { "DELETE $table attempting network" }
        // One build of the request serves its schema, its queue capture and the cache patch
        val built = lazy { SelectRequestBuilder(delegate.schema, conversionMethod).apply(request) }
        return executeMutation(
            operationLabel = "DELETE",
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
            cachePatcher.markQueriesAWriteMayReach(
                table,
                operation,
                bodyJson,
                narrowing,
                startedAtTick,
            )
            return
        }
        cachePatcher.patchCachedQueries(
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
        class Queueable(val enqueue: suspend MutationQueue.MutationWriter.() -> MutationRow) :
            QueueCapture

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
        enqueue: suspend MutationQueue.MutationWriter.(FilterDescriptor) -> MutationRow,
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
        operationLabel: String,
        table: String,
        mutationStrategy: MutationStrategy,
        writeSchema: () -> String,
        perform: suspend (RemoteTable) -> PostgrestResult,
        patchCache: suspend (result: PostgrestResult, startedAtTick: Long) -> Unit,
        captureForQueue: () -> QueueCapture,
    ): MutationOutcome = operationGate.call {
        executeMutationNow(
            operationLabel,
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
     * @throws MutationNotQueueableException for a `QueueIfOffline` write the queue cannot hold
     *   (another schema, a dry run, a filter it cannot record, an unknown identity) that could not
     *   be sent for want of a connection.
     */
    private suspend fun executeMutationNow(
        operationLabel: String,
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
            engineLogger.debug { "$operationLabel $table response: ${result.data}" }
            return MutationOutcome.Applied(result)
        }
        val schema = writeSchema()
        if (schema.isNotBlank() && schema != delegate.postgrest.config.defaultSchema) {
            return sendToAnotherSchema(operationLabel, schema, table, performOnline)
        }

        val startedAtTick = freshness.tickNow()
        // Taken as the write is issued: a session switch while it is in flight must not re-own it.
        val identity = queueIdentity()
        val direct = DirectSend(operationLabel, table, performOnline, patchCache, startedAtTick)

        val queueable =
            when (val capture = captureForQueue()) {
                is QueueCapture.Queueable -> capture
                // Replaying it would target a broader row set than the caller wrote.
                is QueueCapture.Refused -> {
                    capture.detail?.let { detail ->
                        engineLogger.debug {
                            "Offline $operationLabel for $table would not be queued: $detail"
                        }
                    }
                    return direct.send(EngineLogLevel.ERROR) {
                        "Refusing to queue offline $operationLabel for $table: ${capture.reason}"
                    }
                }
            }
        if (identity == null) {
            return direct.send(EngineLogLevel.WARNING) {
                "Not queueing offline $operationLabel for $table: the session's identity cannot be told"
            }
        }
        // Read from the request's shape, on a handle that needs no session token: a session whose
        // token cannot be refreshed offline does not stop a write from queueing
        when (val verdict = delegate.classifyTokenFree(perform)) {
            DryRunVerdict.NotDryRun -> Unit
            DryRunVerdict.DryRun ->
                return direct.send(EngineLogLevel.INFO) {
                    "Not queueing offline $operationLabel for $table: a dry run is never queued"
                }
            // The write's own failure, before any request: sending it directly reports it as it is.
            is DryRunVerdict.NotBuilt ->
                return direct.send(EngineLogLevel.INFO) {
                    "Not queueing offline $operationLabel for $table: its request could not be built"
                }
            DryRunVerdict.Unavailable -> {
                reportUnclassifiableWriteOnce()
                return direct.send(EngineLogLevel.WARNING) {
                    "Not queueing offline $operationLabel for $table: it may be a dry run"
                }
            }
            is DryRunVerdict.Sent -> {
                reportUnclassifiableWriteOnce()
                return direct.applied(verdict.result)
            }
        }

        val queued =
            when (
                val enqueued =
                    mutationQueue.enqueueOwnedBy(identity.storedValue, startedAtTick) {
                        queueable.enqueue(this)
                    }
            ) {
                is EnqueueOutcome.Queued -> enqueued
                EnqueueOutcome.LocalDataCleared ->
                    return direct.send(EngineLogLevel.INFO) {
                        "Not queueing offline $operationLabel for $table: local data was cleared while it was in flight"
                    }
            }

        val sent =
            try {
                headSender.sendAtHead(queued.id, identity, performOnline) { result ->
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
                engineLogger.info { "$operationLabel $table queued behind pending mutations" }
                requestSync()
                MutationOutcome.Queued(pendingMutationOf(queued.row))
            }

            is MutationQueueReplayer.HeadSendOutcome.Sent -> {
                engineLogger.debug { "$operationLabel $table response: ${sent.result.data}" }
                if (!requestPassIfPending(identity)) queueEmptied()
                MutationOutcome.Applied(sent.result)
            }

            is MutationQueueReplayer.HeadSendOutcome.StaysQueued -> {
                engineLogger.info {
                    val status = sent.cause.httpStatusOrNull()
                    when {
                        sent.cause.isNetworkOrConnectivityFailure() ->
                            "$operationLabel $table queued offline (network unavailable)"
                        status == null ->
                            "$operationLabel $table queued (it could not be sent: " +
                                "${sent.cause::class.simpleName})"
                        else ->
                            "$operationLabel $table queued (the server answered $status; " +
                                "it may not have run)"
                    }
                }
                MutationOutcome.Queued(pendingMutationOf(queued.row))
            }

            is MutationQueueReplayer.HeadSendOutcome.Refused -> {
                requestPassIfPending(identity)
                throw sent.cause
            }

            is MutationQueueReplayer.HeadSendOutcome.Cleared -> {
                engineLogger.info {
                    "$operationLabel $table returned after local data was cleared; the cache is left as it was"
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
     * @throws MutationNotQueueableException when it cannot be sent for want of a connection, naming
     *   [schema].
     */
    private suspend fun sendToAnotherSchema(
        operationLabel: String,
        schema: String,
        table: String,
        performOnline: suspend () -> PostgrestResult,
    ): MutationOutcome {
        val startedAtTick = freshness.tickNow()
        val result =
            try {
                performOnline()
            } catch (failure: Exception) {
                // The client is closing, not offline: the caller gets the closed error
                if (failure is TransportClosedException) throw failure
                if (!failure.isNetworkOrConnectivityFailure()) throw failure
                throw MutationNotQueueableException(
                    "$operationLabel $table in the \"$schema\" schema could not be sent, and a write to a schema " +
                        "other than the client's default is never queued: the queue replays to the " +
                        "default schema only",
                    failure,
                )
            }
        engineLogger.debug { "$operationLabel $schema.$table response: ${result.data}" }
        if (!result.wasRolledBack) cachePatcher.markEveryQueryStale("$schema.$table", startedAtTick)
        return MutationOutcome.Applied(result)
    }

    /**
     * Asks for a pass when [identity] has mutations queued, which the head it just left held back.
     * Reads the owner index only, whatever other identities have queued.
     *
     * @return whether [identity] had mutations queued.
     */
    private suspend fun requestPassIfPending(identity: QueueIdentity): Boolean {
        val pending = mutationQueue.hasPendingMutationsOf(identity.storedValue)
        if (pending) requestSync()
        return pending
    }

    /**
     * Logs, once per mutation queue, that the dry-run check cannot tell: such writes lose their
     * offline safety net.
     */
    private fun reportUnclassifiableWriteOnce() {
        if (mutationQueue.claimUnclassifiableWriteReport()) {
            engineLogger.error {
                "Could not tell whether a write is a dry run; such writes are sent without being queued, " +
                    "so they fail rather than queue when offline"
            }
        }
    }

    /** A write sent directly, outside the queue, and the cache patch that follows its success. */
    private inner class DirectSend(
        private val operationLabel: String,
        private val table: String,
        private val performOnline: suspend () -> PostgrestResult,
        private val patchCache: suspend (result: PostgrestResult, startedAtTick: Long) -> Unit,
        private val startedAtTick: Long,
    ) {

        /**
         * Sends the write. A connectivity failure is logged with [whyNotQueued] and thrown as a
         * [MutationNotQueueableException] carrying it; every other failure is thrown as it is.
         *
         * @param level The level to log a connectivity failure at.
         * @param whyNotQueued Why the write was not queued, for the log line.
         * @return [MutationOutcome.Applied].
         */
        suspend fun send(level: EngineLogLevel, whyNotQueued: () -> String): MutationOutcome {
            val result =
                try {
                    performOnline()
                } catch (failure: Exception) {
                    // The client is closing, not offline: the caller gets the closed error
                    if (failure is TransportClosedException) throw failure
                    if (!failure.isNetworkOrConnectivityFailure()) throw failure
                    engineLogger.log(level, whyNotQueued)
                    throw MutationNotQueueableException(
                        "${whyNotQueued()}, and it could not be sent",
                        failure,
                    )
                }
            return applied(result)
        }

        /** The outcome of a write the server applied with [result], its cache patch done. */
        suspend fun applied(result: PostgrestResult): MutationOutcome {
            engineLogger.debug { "$operationLabel $table response: ${result.data}" }
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
                        "$operationLabel $table was rolled back (a dry run); the cache is left as it was"
                    }

                freshness.wasClearedSince(startedAtTick) ->
                    engineLogger.info {
                        "$operationLabel $table returned after local data was cleared; the cache is left as it was"
                    }

                else -> patchCache(result, startedAtTick)
            }
        }
    }

    /** The queued write [row] as the caller sees it in [MutationOutcome.Queued]. */
    private fun pendingMutationOf(row: MutationRow): PendingMutation =
        checkNotNull(row.toPendingMutation(json, primaryKeys)) {
            "the queue stored an operation it cannot name"
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
