package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.OfflineReadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client-scoped coordinator for `CacheFirstThenNetwork` subscriptions, keyed by
 * `(table, signature)`. Owns two responsibilities that both need a single client-wide instance:
 *
 * 1. **Single-flight refresh.** When N subscriptions share a signature, exactly one refresh loop
 *    runs and every subscriber observes its outcome. Implemented by memoizing a hot [shareIn]-backed
 *    flow per key: the first collector starts the shared upstream (and its refresh loop), later
 *    collectors attach to it, and the upstream is torn down once the last collector leaves. Today
 *    N collectors would otherwise each run their own refresh ladder — wasteful and stampede-prone.
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

    private val sharedFlowsMutex = Mutex()
    private val sharedFlows = mutableMapOf<Key, Flow<OfflineReadResult>>()

    /** Table → (signature → live-collector count). A signature is "live" while its count is > 0. */
    private val liveSignatures = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())

    /**
     * Returns the shared, single-flight read flow for [table]/[signature], starting it from
     * [upstream] on first use and memoizing it so concurrent subscribers share one refresh loop.
     *
     * The shared flow uses [SharingStarted.WhileSubscribed] with zero stop timeout and zero replay
     * expiration: the upstream (and its single refresh loop) runs only while there is at least one
     * collector, a concurrently-joining collector still receives the latest value via `replay = 1`,
     * and once every collector has left the replayed value is dropped so a later re-subscription
     * starts a genuinely fresh refresh instead of replaying a stale (possibly cross-session) row.
     *
     * @param table Table of the query.
     * @param signature Canonical signature of the query.
     * @param upstream Factory for the per-signature read flow; invoked at most once per live key.
     * @return The memoized shared read flow.
     */
    suspend fun sharedReadFlow(
        table: String,
        signature: String,
        upstream: () -> Flow<OfflineReadResult>,
    ): Flow<OfflineReadResult> = sharedFlowsMutex.withLock {
        sharedFlows.getOrPut(Key(table, signature)) {
            upstream().shareIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(
                    stopTimeoutMillis = 0,
                    replayExpirationMillis = 0,
                ),
                replay = 1,
            )
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
