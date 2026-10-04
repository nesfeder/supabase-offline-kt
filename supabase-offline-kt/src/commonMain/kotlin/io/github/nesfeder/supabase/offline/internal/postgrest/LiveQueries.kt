// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.newEngineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
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
internal const val CLIENT_CLOSED_MESSAGE: String = "This offline client is closed"

/**
 * The client's single-flight refresh per `(table, cacheKey)`: overlapping `selectAsFlow` collectors
 * share one upstream and one refresh loop, torn down when the last leaves. It also tracks the live
 * signatures, so the store can materialize a write into a query that is observed but not cached.
 *
 * @param scope hosts the shared upstreams: the client's own scope, which its close ends.
 * @param engineLogger where a failure no collector receives is logged.
 */
internal class LiveQueries(
    private val scope: CoroutineScope,
    private val engineLogger: EngineLogger? = null,
) {

    private data class Key(val table: String, val cacheKey: String)

    /** What the shared upstream hands its collectors: a value, or the failure that ended it. */
    private sealed interface Emission {
        class Value(val result: QuerySnapshot) : Emission

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
    private val liveCacheKeys = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())

    /** How many refreshes each table, and each live query, was asked for; see [requestRefresh]. */
    private val refreshRequests = MutableStateFlow(RefreshRequestCounts())

    /**
     * Counts of refresh requests: [byTable] for every query of a table, [byQuery] for one live
     * query of it. Only a live query is counted, and its count goes when its last reader leaves.
     */
    private data class RefreshRequestCounts(
        val byTable: Map<String, Long> = emptyMap(),
        val byQuery: Map<Key, Long> = emptyMap(),
    ) {
        /** The requests that reach the query of [table] cached under [cacheKey]. */
        fun of(table: String, cacheKey: String): Long =
            (byTable[table] ?: 0L) + (byQuery[Key(table, cacheKey)] ?: 0L)
    }

    /**
     * Asks every live query of [table] that refreshes to fetch again, or only those cached under
     * [cacheKeys] when given. A refresh running when it is asked runs again once it is done.
     */
    fun requestRefresh(table: String, cacheKeys: Set<String>? = null) {
        refreshRequests.update { counts ->
            if (cacheKeys == null) {
                counts.copy(byTable = counts.byTable + (table to (counts.byTable[table] ?: 0L) + 1))
            } else {
                val live = liveCacheKeysOf(table)
                counts.copy(
                    byQuery =
                        counts.byQuery +
                            cacheKeys
                                .filter { cacheKey -> cacheKey in live }
                                .associate { cacheKey ->
                                    val key = Key(table, cacheKey)
                                    key to (counts.byQuery[key] ?: 0L) + 1
                                }
                )
            }
        }
    }

    /**
     * The count of refresh requests that reach the query of [table] cached under [cacheKey], at
     * once and as it moves.
     */
    fun refreshRequestsFor(table: String, cacheKey: String): Flow<Long> =
        refreshRequests.map { counts -> counts.of(table, cacheKey) }.distinctUntilChanged()

    /**
     * The shared read flow for [table]/[cacheKey], built by [upstream] once per live entry. A
     * joining collector gets the latest value. A slow one is handed only the latest, the first
     * included when it is slow to take that, so it never holds back the others. The upstream's
     * failure reaches every collector in band, after the latest value; the last to leave ends the
     * entry; once [scope] ends every collection fails with the client's closed error.
     */
    fun sharedReadFlow(
        table: String,
        cacheKey: String,
        upstream: () -> Flow<QuerySnapshot>,
    ): Flow<QuerySnapshot> = channelFlow {
        val key = Key(table, cacheKey)
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

    private suspend fun acquire(key: Key, upstream: () -> Flow<QuerySnapshot>): SharedEntry =
        sharedFlowsMutex.withLock {
            val entry =
                sharedFlows.getOrPut(key) {
                    val entryScope = newEngineScope(engineLogger, parent = scope)
                    val shared =
                        upstream()
                            .map<QuerySnapshot, Emission> { result -> Emission.Value(result) }
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
     * Records that a `selectAsFlow` collector for [table]/[cacheKey] has started. Balanced by
     * [unregisterLiveQuery]; the pair is refcounted so overlapping collectors on one signature keep
     * it live until the last one leaves.
     *
     * @param table Table of the query.
     * @param cacheKey Canonical signature of the query.
     */
    fun registerLiveQuery(table: String, cacheKey: String) {
        liveCacheKeys.update { current -> current.adjustCount(table, cacheKey, delta = 1) }
    }

    /**
     * Records that a `selectAsFlow` collector for [table]/[cacheKey] has ended.
     *
     * @param table Table of the query.
     * @param cacheKey Canonical signature of the query.
     */
    fun unregisterLiveQuery(table: String, cacheKey: String) {
        liveCacheKeys.update { current -> current.adjustCount(table, cacheKey, delta = -1) }
        if (cacheKey !in liveCacheKeysOf(table)) {
            refreshRequests.update { counts ->
                counts.copy(byQuery = counts.byQuery - Key(table, cacheKey))
            }
        }
    }

    /**
     * Signatures of [table] that currently have at least one live collector. Consumed by
     * `patchCachedQueries` to decide which cache-less signatures to materialize a server-successful
     * mutation into.
     *
     * @param table Table to look up.
     * @return The set of live signatures for [table] (empty when none).
     */
    fun liveCacheKeysOf(table: String): Set<String> = liveCacheKeys.value[table]?.keys ?: emptySet()

    /** The signatures of every table that have a live collector: what eviction keeps. */
    fun allLiveCacheKeys(): Set<String> =
        liveCacheKeys.value.values.flatMapTo(mutableSetOf()) { perTable -> perTable.keys }

    /** The tables with a live collector. */
    fun liveTables(): Set<String> = liveCacheKeys.value.keys

    private fun Map<String, Map<String, Int>>.adjustCount(
        table: String,
        cacheKey: String,
        delta: Int,
    ): Map<String, Map<String, Int>> {
        val perTable = this[table].orEmpty()
        val nextCount = (perTable[cacheKey] ?: 0) + delta
        val nextPerTable =
            if (nextCount <= 0) perTable - cacheKey else perTable + (cacheKey to nextCount)
        return if (nextPerTable.isEmpty()) this - table else this + (table to nextPerTable)
    }
}
