package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.OfflineReadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Client-scoped coordinator for `CacheFirstThenNetwork` subscriptions, keyed by
 * `(table, signature)`. Owns two responsibilities that both need a single client-wide instance:
 *
 * 1. **Single-flight refresh.** When N subscriptions share a signature, exactly one refresh loop
 *    runs and every subscriber observes its outcome. Implemented by a hot [shareIn]-backed flow per
 *    live key: the first collector starts the shared upstream (and its refresh loop), later
 *    collectors attach to it, and the entry — sharing coroutine included — is torn down once the
 *    last collector leaves. N collectors would otherwise each run their own refresh ladder —
 *    wasteful and stampede-prone.
 *
 * 2. **Live-signature registry.** Tracks which `(table, signature)` pairs currently have a live
 *    `selectAsFlow` collector, so [io.github.nesfeder.supabase.offline.internal.db.OfflineStore] can materialize
 *    an online-success mutation into a real cache row for a cache-less-but-observed signature
 *    (the fix for the "save then stale UI" bug), and self-GC when no subscriber is present.
 *
 * @param scope Long-lived scope hosting shared refresh upstreams; the client provides a
 *   process-lifetime scope. Injectable so tests can supply a controlled scope.
 */
internal class RefreshCoordinator(
    private val scope: CoroutineScope,
) {

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
     * Returns the shared, single-flight read flow for [table]/[signature]. Collectors that overlap
     * share one upstream — started from [upstream] by the first of them — and one refresh loop.
     *
     * - A joining collector receives the latest value (`replay = 1`).
     * - When the last collector leaves, the entry is removed and its sharing coroutine cancelled,
     *   so nothing keeps running, and nothing keeps the first collector's [upstream] (and whatever
     *   its request captured) alive. A later collector starts a genuinely fresh refresh instead of
     *   replaying a stale, possibly cross-session, row.
     * - A failure in the upstream — a Room read failing on a malformed database, say — reaches
     *   every collector of that entry as their own exception, instead of escaping from the sharing
     *   coroutine to the process. The entry ends with it; the next collector starts afresh.
     * - When [scope] ends (the client was closed), every collection completes.
     *
     * @param table Table of the query.
     * @param signature Canonical signature of the query.
     * @param upstream Factory for the per-signature read flow; invoked once per live entry.
     * @return The shared read flow.
     */
    fun sharedReadFlow(
        table: String,
        signature: String,
        upstream: () -> Flow<OfflineReadResult>,
    ): Flow<OfflineReadResult> = channelFlow {
        val key = Key(table, signature)
        val entry = acquire(key, upstream)
        // When the client closes, its scope — and with it this entry's sharing coroutine — is
        // cancelled. End the collection then, instead of leaving it waiting on a flow that will
        // never emit again.
        val closedWatcher = launch {
            scope.coroutineContext.job.join()
            this@channelFlow.close()
        }
        try {
            entry.flow.collect { emission ->
                when (emission) {
                    is Emission.Value -> send(emission.result)
                    is Emission.Failure -> throw emission.cause
                }
            }
        } finally {
            closedWatcher.cancel()
            withContext(NonCancellable) { release(key, entry) }
        }
    }.buffer(Channel.RENDEZVOUS)

    private suspend fun acquire(key: Key, upstream: () -> Flow<OfflineReadResult>): SharedEntry =
        sharedFlowsMutex.withLock {
            val entry = sharedFlows.getOrPut(key) {
                val entryScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
                val shared = upstream()
                    .map<OfflineReadResult, Emission> { result -> Emission.Value(result) }
                    .catch { failure -> emit(Emission.Failure(failure)) }
                    .shareIn(
                        scope = entryScope,
                        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
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
     * `applyLocalMutationToCache` to decide which cache-less signatures to materialize a
     * server-successful mutation into.
     *
     * @param table Table to look up.
     * @return The set of live signatures for [table] (empty when none).
     */
    fun liveSignaturesFor(table: String): Set<String> =
        liveSignatures.value[table]?.keys ?: emptySet()

    private fun Map<String, Map<String, Int>>.adjustCount(
        table: String,
        signature: String,
        delta: Int,
    ): Map<String, Map<String, Int>> {
        val perTable = this[table].orEmpty()
        val nextCount = (perTable[signature] ?: 0) + delta
        val nextPerTable = if (nextCount <= 0) perTable - signature else perTable + (signature to nextCount)
        return if (nextPerTable.isEmpty()) this - table else this + (table to nextPerTable)
    }
}
