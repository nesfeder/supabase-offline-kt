// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.bridge

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilder
import io.github.nesfeder.supabase.offline.bridge.internal.InvalidDescriptionException
import io.github.nesfeder.supabase.offline.bridge.internal.OperationRunner
import io.github.nesfeder.supabase.offline.bridge.internal.UnhandledFailureReporter
import io.github.nesfeder.supabase.offline.bridge.internal.assembleSupabaseClient
import io.github.nesfeder.supabase.offline.bridge.internal.bridgeErrorOf
import io.github.nesfeder.supabase.offline.bridge.internal.mutationDescription
import io.github.nesfeder.supabase.offline.bridge.internal.parseJsonText
import io.github.nesfeder.supabase.offline.bridge.internal.readDescription
import io.github.nesfeder.supabase.offline.bridge.internal.resolutionOf
import io.github.nesfeder.supabase.offline.bridge.internal.rpcDescription
import io.github.nesfeder.supabase.offline.bridge.internal.toBridge
import io.github.nesfeder.supabase.offline.bridge.internal.toBridgeTableStatuses
import io.github.nesfeder.supabase.offline.offline
import io.ktor.client.engine.darwin.Darwin
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The offline client, as Swift sees it: every operation of the library, each taking a completion
 * block and returning a [BridgeCancellable].
 *
 * Reads and writes are plain JSON descriptions the bridge applies onto supabase-kt's own builders
 * (the format is in the contract note beside this module); bodies and records cross as JSON text. A
 * completion fires exactly once, on the bridge's background threads and never inside the call that
 * takes it; it may run before that call returns. A live read's values arrive in order and never
 * block it. Nothing here throws: a failure arrives as a [BridgeError].
 *
 * Build one with [open]; [close] it when done, which frees its database for another client.
 */
public class BridgeClient
internal constructor(
    internal val supabase: SupabaseClient,
    private val networkReports: MutableStateFlow<Boolean?>,
    logSink: BridgeLogSink?,
) {
    /** Whether [close] has begun; once set, every new call fails with the closed error. */
    private val closing = AtomicBoolean(false)

    /** Completed when the first [close] is done, for the calls of [close] that follow it. */
    private val closed = CompletableDeferred<Unit>()

    /** Runs every call but [close]. */
    internal val runner = OperationRunner(logSink) { closing.load() }

    /** Runs [close], which must outlive the calls [runner] cancels. */
    private val lifecycleScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, failure -> runner.report(failure, "Closing") }
        )

    /** The library's plugin in [supabase]. */
    private val offline: Offline
        get() = supabase.offline

    /** Storage buckets, with downloads kept on the device. */
    public val storage: BridgeStorage = BridgeStorage({ offline }, runner)

    /**
     * Reads rows once, as [query] describes them (table, columns, filters, modifiers and the read
     * strategy).
     *
     * @param query the read's description, as JSON text.
     * @param completion the rows and where they came from, or the failure.
     */
    public fun select(
        query: String,
        completion: (BridgeQueryResult?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("select", completion) {
            val read = readDescription(query)
            val strategy = read.readStrategy()
            tableOf(read.schema, read.table)
                .select(read.columns, strategy) { read.applyTo(this) }
                .toBridge()
        }

    /**
     * Reads rows as they change: [onSnapshot] gets every emission in order, then [onCompletion]
     * fires once, with `null` when the read ends (the client closed) or with the failure.
     *
     * @param query the read's description, as JSON text; its strategy is a live one.
     */
    public fun observe(
        query: String,
        onSnapshot: (BridgeQuerySnapshot) -> Unit,
        onCompletion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.subscribe("observe", onSnapshot, onCompletion) {
            val read = readDescription(query)
            tableOf(read.schema, read.table)
                .selectAsFlow(read.columns, read.flowReadStrategy()) { read.applyTo(this) }
                .map { it.toBridge(closing.load()) }
        }

    /**
     * Writes rows, as [mutation] describes the write.
     *
     * @param mutation the write's description, as JSON text.
     * @param body the rows of an insert or upsert (an array of objects, or one object) or the
     *   columns of an update (an object), as JSON text; `null` for a delete.
     * @param completion the outcome (applied, with the server's response, or queued), or the
     *   failure.
     */
    public fun mutate(
        mutation: String,
        body: String?,
        completion: (BridgeMutationResult?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("mutate", completion) {
            val write = mutationDescription(mutation)
            val table = tableOf(write.schema, write.table)
            val outcome =
                when (write.operation) {
                    MutationOperation.INSERT ->
                        table.insert(rowsOf(body), write.strategy) { write.applyTo(this) }
                    MutationOperation.UPSERT ->
                        table.upsert(rowsOf(body), write.strategy) { write.applyTo(this) }
                    MutationOperation.UPDATE ->
                        table.update(columnsOf(body), write.strategy) { write.applyTo(this) }
                    MutationOperation.DELETE -> {
                        if (body != null) {
                            throw InvalidDescriptionException("A delete takes no body")
                        }
                        table.delete(write.strategy) { write.applyTo(this) }
                    }
                }
            outcome.toBridge()
        }

    /**
     * Calls a database function on the server. Network only: never cached, never queued.
     *
     * @param call the call's description (function, method, filters), as JSON text.
     * @param arguments the function's arguments, a JSON object as text, or `null` for none. A call
     *   by GET or HEAD takes none: it fails with [BridgeErrorKind.INVALID_REQUEST].
     */
    public fun rpc(
        call: String,
        arguments: String?,
        completion: (BridgeQueryResult?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("rpc", completion) {
            val rpc = rpcDescription(call)
            rpc.refuseArgumentsUnlessPost(arguments)
            val result =
                if (arguments == null) {
                    offline.rpc(rpc.function) { rpc.applyTo(this) }
                } else {
                    offline.rpc(rpc.function, objectOf(arguments, "arguments")) {
                        rpc.applyTo(this)
                    }
                }
            result.toBridge()
        }

    /**
     * The current user's queued writes, of [table] or of every table, in the order they will be
     * sent.
     */
    public fun pendingMutations(
        table: String?,
        completion: (List<BridgePendingMutation>?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("pendingMutations", completion) {
            offline.pendingMutations(table).first().map { it.toBridge() }
        }

    /** [pendingMutations] as the queue changes; [onCompletion] fires once, when it ends. */
    public fun observePendingMutations(
        table: String?,
        onMutations: (List<BridgePendingMutation>) -> Unit,
        onCompletion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.subscribe("observePendingMutations", onMutations, onCompletion) {
            offline.pendingMutations(table).map { mutations -> mutations.map { it.toBridge() } }
        }

    /** How many of the current user's writes wait, of [table] or of every table. */
    public fun pendingMutationCount(
        table: String?,
        completion: (Int?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("pendingMutationCount", completion) { offline.pendingMutationCount(table) }

    /** [pendingMutationCount] as it changes; a value only when it differs from the last. */
    public fun observePendingMutationCount(
        table: String?,
        onCount: (Int) -> Unit,
        onCompletion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.subscribe("observePendingMutationCount", onCount, onCompletion) {
            offline.pendingMutationCountFlow(table)
        }

    /** Removes one of the current user's queued writes without sending it: destructive. */
    public fun discardPendingMutation(
        id: Long,
        completion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runToCompletion("discardPendingMutation", completion) {
            offline.discardPendingMutation(id)
        }

    /** The current user's writes the server refused for good on replay, oldest first. */
    public fun rejectedMutations(
        completion: (List<BridgeRejectedMutation>?, BridgeError?) -> Unit
    ): BridgeCancellable =
        runner.runOnce("rejectedMutations", completion) {
            offline.rejectedMutations().first().map { it.toBridge() }
        }

    /** [rejectedMutations] as the list changes; [onCompletion] fires once, when it ends. */
    public fun observeRejectedMutations(
        onMutations: (List<BridgeRejectedMutation>) -> Unit,
        onCompletion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.subscribe("observeRejectedMutations", onMutations, onCompletion) {
            offline.rejectedMutations().map { mutations -> mutations.map { it.toBridge() } }
        }

    /** Removes an entry from the rejected list for good. */
    public fun dismissRejectedMutation(
        id: Long,
        completion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runToCompletion("dismissRejectedMutation", completion) {
            offline.dismissRejectedMutation(id)
        }

    /**
     * Queues a rejected write again, at the end of the queue under a new id; an entry that cannot
     * be retried fails with [BridgeErrorKind.INVALID_ARGUMENT].
     */
    public fun retryRejectedMutation(
        id: Long,
        completion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runToCompletion("retryRejectedMutation", completion) {
            offline.retryRejectedMutation(id)
        }

    /** The current user's writes parked on the conflicted list, oldest first. */
    public fun conflictedMutations(
        completion: (List<BridgeConflictedMutation>?, BridgeError?) -> Unit
    ): BridgeCancellable =
        runner.runOnce("conflictedMutations", completion) {
            offline.conflictedMutations().first().map { it.toBridge() }
        }

    /** [conflictedMutations] as the list changes; [onCompletion] fires once, when it ends. */
    public fun observeConflictedMutations(
        onMutations: (List<BridgeConflictedMutation>) -> Unit,
        onCompletion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.subscribe("observeConflictedMutations", onMutations, onCompletion) {
            offline.conflictedMutations().map { mutations -> mutations.map { it.toBridge() } }
        }

    /**
     * Settles an entry of the conflicted list: KEEP_LOCAL and APPLY queue it again under a new id,
     * KEEP_SERVER drops it. A decision the library refuses fails with
     * [BridgeErrorKind.INVALID_ARGUMENT]; APPLY with [columnsJson] that is not a JSON object fails
     * with [BridgeErrorKind.INVALID_REQUEST].
     *
     * @param columnsJson the columns of APPLY, a JSON object as text; `null` for the others.
     */
    public fun resolveConflictedMutation(
        id: Long,
        decision: BridgeConflictDecision,
        columnsJson: String?,
        completion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runToCompletion("resolveConflictedMutation", completion) {
            offline.resolveConflictedMutation(id, resolutionOf(decision, columnsJson))
        }

    /** Removes an entry from the conflicted list for good, without sending it. */
    public fun dismissConflictedMutation(
        id: Long,
        completion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runToCompletion("dismissConflictedMutation", completion) {
            offline.dismissConflictedMutation(id)
        }

    /** Sends the current user's queued writes now, of [table] or of every table. */
    public fun syncPendingMutations(
        table: String?,
        completion: (BridgeSyncReport?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("syncPendingMutations", completion) {
            offline.syncPendingMutations(table).toBridge()
        }

    /**
     * The sync status now, without waiting. Reading it cannot fail in the library; should it fail
     * here, the failure arrives as the returned status's [BridgeSyncStatus.lastSyncError].
     */
    public fun currentSyncStatus(): BridgeSyncStatus =
        runner.answerNow(
            "currentSyncStatus",
            onFailure = { error ->
                BridgeSyncStatus(
                    network = BridgeNetworkStatus.UNKNOWN,
                    pendingMutationCount = 0,
                    rejectedMutationCount = 0,
                    isSyncing = false,
                    lastSyncedAtEpochMillis = null,
                    lastSyncError = error,
                    conflictedMutationCount = 0,
                )
            },
        ) {
            offline.syncStatus.value.toBridge()
        }

    /**
     * The sync status as it changes, starting with the current one; [onCompletion] fires once, with
     * `null`, when the client closes.
     */
    public fun observeSyncStatus(
        onStatus: (BridgeSyncStatus) -> Unit,
        onCompletion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.subscribe("observeSyncStatus", onStatus, onCompletion) {
            offline.syncStatus.map { it.toBridge() }
        }

    /** Turns the library's network access off, as airplane mode does, until [enableNetwork]. */
    public fun disableNetwork(completion: (BridgeError?) -> Unit): BridgeCancellable =
        runner.runToCompletion("disableNetwork", completion) { offline.disableNetwork() }

    /** Turns the library's network access back on. */
    public fun enableNetwork(completion: (BridgeError?) -> Unit): BridgeCancellable =
        runner.runToCompletion("enableNetwork", completion) { offline.enableNetwork() }

    /**
     * Where the channel for [BridgeClientOptions.realtimeTables] stands now; off without named
     * tables and after [close]. A failure to read it is reported as a stalled status carrying the
     * failure.
     */
    public fun currentRealtimeStatus(): BridgeRealtimeStatus =
        runner.answerNow(
            "currentRealtimeStatus",
            onFailure = { error -> BridgeRealtimeStatus(BridgeRealtimeStatusKind.STALLED, error) },
        ) {
            offline.realtimeStatus.value.toBridge()
        }

    /**
     * The channel's status as it changes, starting with the current one; [onCompletion] fires once,
     * with `null`, when the client closes.
     */
    public fun observeRealtimeStatus(
        onStatus: (BridgeRealtimeStatus) -> Unit,
        onCompletion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.subscribe("observeRealtimeStatus", onStatus, onCompletion) {
            offline.realtimeStatus.map { it.toBridge() }
        }

    /**
     * Where each table [BridgeClientOptions.realtimeTables] names stands now, on its own channel,
     * in the order they were named; empty without named tables. A failure to read it is reported as
     * no table, which the package reads as every table off.
     */
    public fun currentRealtimeTableStatuses(): List<BridgeRealtimeTableStatus> =
        runner.answerNow("currentRealtimeTableStatuses", onFailure = { emptyList() }) {
            offline.realtimeTableStatus.value.toBridgeTableStatuses()
        }

    /**
     * Each table's status as it changes, starting with the current ones; [onCompletion] fires once,
     * with `null`, when the client closes.
     */
    public fun observeRealtimeTableStatuses(
        onStatuses: (List<BridgeRealtimeTableStatus>) -> Unit,
        onCompletion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.subscribe("observeRealtimeTableStatuses", onStatuses, onCompletion) {
            offline.realtimeTableStatus.map { it.toBridgeTableStatuses() }
        }

    /**
     * Leaves the Realtime channel and joins none until [enableRealtime]; the status reads off once
     * this completes. Not kept across clients.
     */
    public fun disableRealtime(completion: (BridgeError?) -> Unit): BridgeCancellable =
        runner.runToCompletion("disableRealtime", completion) { offline.disableRealtime() }

    /** Joins the channel again after [disableRealtime], as soon as the network allows. */
    public fun enableRealtime(completion: (BridgeError?) -> Unit): BridgeCancellable =
        runner.runToCompletion("enableRealtime", completion) { offline.enableRealtime() }

    /**
     * Reports whether the device is online, from the app's own monitor; only read when the client
     * was opened with [BridgeClientOptions.reportsNetworkAvailability]. A hint, never a gate.
     */
    public fun setNetworkAvailable(available: Boolean) {
        runner.answerNow(
            "setNetworkAvailable",
            onFailure = { error -> runner.logFailure(error, "Reporting the network") },
        ) {
            networkReports.value = available
        }
    }

    /**
     * Deletes everything the library keeps on this device: cached rows, downloaded files, and every
     * queued write, which is never sent. The client stays usable.
     */
    public fun clearLocalData(completion: (BridgeError?) -> Unit): BridgeCancellable =
        runner.runToCompletion("clearLocalData", completion) { offline.clearLocalData() }

    /**
     * Applies a row the server reports, such as a Realtime record, to every cached query of
     * [table].
     *
     * @param record the row, a JSON object as text.
     * @param primaryKeys the row's key columns, or `null` for the table's configured key.
     * @param schema the table's schema, or `null` for the client's default.
     */
    public fun applyServerRecord(
        table: String,
        record: String,
        primaryKeys: List<String>?,
        schema: String?,
        completion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runToCompletion("applyServerRecord", completion) {
            offline.applyServerRecord(table, objectOf(record, "record"), primaryKeys, schema)
        }

    /**
     * Removes a row the server reports deleted from every cached query of [table].
     *
     * @param oldRecord the deleted row's values, at least its key, a JSON object as text.
     */
    public fun applyServerDelete(
        table: String,
        oldRecord: String,
        primaryKeys: List<String>?,
        schema: String?,
        completion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runToCompletion("applyServerDelete", completion) {
            offline.applyServerDelete(table, objectOf(oldRecord, "record"), primaryKeys, schema)
        }

    /**
     * Closes the client and frees its database for another client; nothing is deleted. Calls not
     * yet sent go out first; every call still running afterwards ends with
     * [BridgeErrorKind.CLIENT_CLOSED], and every new call fails with it. Live reads end with
     * `null`. The close itself cannot be cancelled: its handle's [BridgeCancellable.cancel] does
     * nothing. Safe to call more than once; later calls answer once the first is done.
     */
    public fun close(completion: (BridgeError?) -> Unit): BridgeCancellable {
        val first = closing.compareAndSet(expectedValue = false, newValue = true)
        // The handle holds no job: a close canceled before it started would never close at all.
        lifecycleScope.launch {
            val error =
                if (first) {
                    closeEverything()
                } else {
                    closed.await()
                    null
                }
            try {
                completion(error)
            } catch (failure: Throwable) {
                runner.report(failure, "The completion of close")
            }
        }
        return BridgeCancellable(null) { runner.report(it, "Canceling close") }
    }

    /**
     * Closes the plugin, then supabase-kt's client, then ends the bridge's own work.
     *
     * @return the first failure among those steps, as an error, or `null`.
     */
    private suspend fun closeEverything(): BridgeError? {
        var firstFailure: Throwable? = null
        val steps =
            listOf<suspend () -> Unit>(
                { runner.faultInjector?.invoke("close") },
                { offline.close() },
                { supabase.close() },
            )
        for (step in steps) {
            try {
                step()
            } catch (failure: Throwable) {
                runner.report(failure, "A close step")
                if (firstFailure == null) firstFailure = failure
            }
        }
        runner.cancelAll()
        closed.complete(Unit)
        return firstFailure?.let { bridgeErrorOf(it, clientClosing = false) }
    }

    /** The table builder for [table], in [schema] or the client's default schema. */
    private fun tableOf(schema: String?, table: String): OfflinePostgrestQueryBuilder =
        if (schema == null) offline.from(table) else offline.from(schema, table)

    /** Opens clients. */
    public companion object {

        /**
         * Opens a client over the network, with its database and file cache in
         * [BridgeClientOptions.dataDirectory]. It claims the database on the calling thread; call
         * it off the main thread when that matters.
         *
         * @return the client, or the failure: another client holds the database, or the options
         *   were refused.
         */
        public fun open(options: BridgeClientOptions): BridgeOpenResult =
            openBridgeClient(options) { networkAvailability ->
                assembleSupabaseClient(options, Darwin.create(), networkAvailability)
            }
    }
}

/**
 * A client opened by [BridgeClient.open] or [BridgeTesting.openInMemory]: the client or the
 * failure, never both.
 */
public class BridgeOpenResult
internal constructor(public val client: BridgeClient?, public val error: BridgeError?)

/**
 * Opens a bridge client over the supabase-kt client [assemble] builds, given the app's connectivity
 * reports (or `null` when the options leave connectivity to the library).
 */
internal fun openBridgeClient(
    options: BridgeClientOptions,
    assemble: (networkAvailability: Flow<Boolean>?) -> SupabaseClient,
): BridgeOpenResult {
    UnhandledFailureReporter.reportTo(options.logSink)
    return try {
        val networkReports = MutableStateFlow<Boolean?>(null)
        val supabase =
            assemble(networkReports.filterNotNull().takeIf { options.reportsNetworkAvailability })
        BridgeOpenResult(BridgeClient(supabase, networkReports, options.logSink), null)
    } catch (failure: Throwable) {
        BridgeOpenResult(null, bridgeErrorOf(failure, clientClosing = false))
    }
}

/** [text] as the rows of an insert or upsert: an array of objects, or one object. */
private fun rowsOf(text: String?): JsonArray {
    if (text == null) throw InvalidDescriptionException("An insert or upsert needs a body")
    return when (val body = parseJsonText(text, "body")) {
        is JsonArray ->
            body.also { rows ->
                if (rows.any { it !is JsonObject }) {
                    throw InvalidDescriptionException("The body's rows must be JSON objects")
                }
            }
        is JsonObject -> JsonArray(listOf(body))
        else ->
            throw InvalidDescriptionException("The body must be an array of objects or an object")
    }
}

/** [text] as an update's columns: one object. */
private fun columnsOf(text: String?): JsonObject {
    if (text == null) throw InvalidDescriptionException("An update needs a body")
    return objectOf(text, "body")
}

/** [text] as a JSON object, naming it [what] in an error. */
private fun objectOf(text: String, what: String): JsonObject =
    parseJsonText(text, what) as? JsonObject
        ?: throw InvalidDescriptionException("The $what must be a JSON object")
