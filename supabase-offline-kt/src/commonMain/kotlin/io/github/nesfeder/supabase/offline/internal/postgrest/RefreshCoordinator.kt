// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.OfflineReadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a call on a closed client — or a reactive read it was serving — fails with. */
internal const val CLIENT_CLOSED_MESSAGE: String = "This SupabaseOfflineClient is closed"

/**
 * The client's single-flight refresh per `(table, signature)`: overlapping `selectAsFlow`
 * collectors share one upstream and one refresh loop, torn down when the last leaves. It also
 * tracks the live signatures, so the store can materialize a write into a query that is observed
 * but not cached.
 *
 * @param scope hosts the shared upstreams: the client's own scope, which its close ends.
 */
internal class RefreshCoordinator(private val scope: CoroutineScope) {

    private data class Key(val table: String, val signature: String)

    /** What the shared upstream hands its collectors: a value, or the failure that ended it. */
    private sealed interface Emission {
        class Value(val result: OfflineReadResult) : Emission

        class Failure(val cause: Throwable) : Emission
    }

    /**
     * One live shared refresh: the flow, the child scope its sharing coroutine runs in, and how
     * many collectors hold it. Guarded by [sharedFlowsMutex].
     */
    private class SharedEntry(val scope: CoroutineScope, val flow: Flow<Emission>) {
        var collectors: Int = 0
    }

    private val sharedFlowsMutex = Mutex()
    private val sharedFlows = mutableMapOf<Key, SharedEntry>()

    /** Table → (signature → live-collector count). A signature is "live" while its count is > 0. */
    private val liveSignatures = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())

    /**
     * Queries that should fetch again, by table, `null` for all of it; only readers already waiting
     * see a request.
     */
    private val refreshRequests =
        MutableSharedFlow<RefreshRequest>(
            extraBufferCapacity = REFRESH_REQUEST_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * A request that the live queries of [table] fetch again: those cached under [signatures], or
     * every one when it is `null`.
     */
    private data class RefreshRequest(val table: String, val signatures: Set<String>?)

    /**
     * Asks every live `CacheFirstThenNetwork` query of [table] whose refresh has finished to run it
     * again, or only those cached under [signatures] when given.
     */
    fun requestRefresh(table: String, signatures: Set<String>? = null) {
        refreshRequests.tryEmit(RefreshRequest(table, signatures))
    }

    /** The refresh requests for the query of [table] cached under [signature], one [Unit] each. */
    fun refreshRequestsFor(table: String, signature: String): Flow<Unit> =
        refreshRequests
            .filter { request ->
                request.table == table && (request.signatures?.contains(signature) ?: true)
            }
            .map {}

    /**
     * The shared read flow for [table]/[signature], built by [upstream] once per live entry. A
     * joining collector gets the latest value. A slow one is handed only the latest, the first
     * included when it is slow to take that, so it never holds back the others. The upstream's
     * failure reaches every collector in band, after the latest value; the last to leave ends the
     * entry; once [scope] ends every collection fails with the client's closed error.
     */
    fun sharedReadFlow(
        table: String,
        signature: String,
        upstream: () -> Flow<OfflineReadResult>,
    ): Flow<OfflineReadResult> = channelFlow {
        val key = Key(table, signature)
        val entry = acquire(key, upstream)
        // When the client closes, its scope — and with it this entry's sharing coroutine — is
        // cancelled. Fail the collection then, instead of leaving it waiting on a flow that will
        // never emit again, or ending it as though the read were done.
        val closedWatcher = launch {
            scope.coroutineContext.job.join()
            this@channelFlow.close(IllegalStateException(CLIENT_CLOSED_MESSAGE))
        }
        try {
            entry.flow
                .takeWhile { emission ->
                    when (emission) {
                        is Emission.Value -> {
                            send(emission.result)
                            true
                        }
                        is Emission.Failure -> {
                            // Closed, not thrown: a throw cancels the collector, which can drop the
                            // value just sent
                            this@channelFlow.close(emission.cause)
                            false
                        }
                    }
                }
                .collect()
        } finally {
            closedWatcher.cancel()
            withContext(NonCancellable) { release(key, entry) }
        }
    }
        .conflate()

    private suspend fun acquire(key: Key, upstream: () -> Flow<OfflineReadResult>): SharedEntry =
        sharedFlowsMutex.withLock {
            val entry =
                sharedFlows.getOrPut(key) {
                    val entryScope =
                        CoroutineScope(
                            scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])
                        )
                    val shared =
                        upstream()
                            .map<OfflineReadResult, Emission> { result -> Emission.Value(result) }
                            .catch { failure -> emit(Emission.Failure(failure)) }
                            .shareIn(
                                scope = entryScope,
                                started =
                                    SharingStarted.WhileSubscribed(
                                        stopTimeoutMillis = 0,
                                        replayExpirationMillis = 0,
                                    ),
                                replay = 1,
                            )
                    SharedEntry(entryScope, shared)
                }
            entry.collectors++
            entry
        }

    private suspend fun release(key: Key, entry: SharedEntry) {
        sharedFlowsMutex.withLock {
            entry.collectors--
            if (entry.collectors > 0) return@withLock
            if (sharedFlows[key] === entry) sharedFlows.remove(key)
            entry.scope.cancel()
        }
    }

    /**
     * Records that a `selectAsFlow` collector for [table]/[signature] has started. Balanced by
     * [unregisterLiveSignature]; the pair is refcounted so overlapping collectors on one signature
     * keep it live until the last one leaves.
     *
     * @param table Table of the query.
     * @param signature Canonical signature of the query.
     */
    fun registerLiveSignature(table: String, signature: String) {
        liveSignatures.update { current -> current.adjustCount(table, signature, delta = 1) }
    }

    /**
     * Records that a `selectAsFlow` collector for [table]/[signature] has ended.
     *
     * @param table Table of the query.
     * @param signature Canonical signature of the query.
     */
    fun unregisterLiveSignature(table: String, signature: String) {
        liveSignatures.update { current -> current.adjustCount(table, signature, delta = -1) }
    }

    /**
     * Signatures of [table] that currently have at least one live collector. Consumed by
     * `patchCachedQueries` to decide which cache-less signatures to materialize a server-successful
     * mutation into.
     *
     * @param table Table to look up.
     * @return The set of live signatures for [table] (empty when none).
     */
    fun liveSignaturesFor(table: String): Set<String> =
        liveSignatures.value[table]?.keys ?: emptySet()

    /** The signatures of every table that have a live collector: what eviction keeps. */
    fun allLiveSignatures(): Set<String> =
        liveSignatures.value.values.flatMapTo(mutableSetOf()) { perTable -> perTable.keys }

    /** The tables with a live collector. */
    fun liveTables(): Set<String> = liveSignatures.value.keys

    private companion object {
        /**
         * Refresh requests kept for a slow reader; the oldest go first, which a conflated reader
         * never misses.
         */
        const val REFRESH_REQUEST_BUFFER = 16
    }

    private fun Map<String, Map<String, Int>>.adjustCount(
        table: String,
        signature: String,
        delta: Int,
    ): Map<String, Map<String, Int>> {
        val perTable = this[table].orEmpty()
        val nextCount = (perTable[signature] ?: 0) + delta
        val nextPerTable =
            if (nextCount <= 0) perTable - signature else perTable + (signature to nextCount)
        return if (nextPerTable.isEmpty()) this - table else this + (table to nextPerTable)
    }
}
