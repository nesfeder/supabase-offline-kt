// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * How a reactive `selectAsFlow` starts, and whether it refreshes the cache from the server.
 *
 * Every strategy keeps emitting as the cached rows change: a write through the library, a refresh,
 * or a server record applied with [Offline.applyServerRecord].
 */
public sealed interface FlowReadStrategy {

    /**
     * Emits the cached rows at once, and refreshes them from the server in parallel.
     *
     * A refresh that fails leaves the cached rows on screen and is retried for as long as the flow
     * is collected, backing off to one attempt a minute; it is tried again at once when the session
     * settles or the network comes back. Each emission's [RefreshState] says how the refresh
     * stands.
     */
    public data object CacheFirstThenNetwork : FlowReadStrategy

    /**
     * Asks the server first, then emits the cached rows as they change.
     *
     * When the server cannot be reached and the query is cached, it emits the cached rows at once,
     * as [ReadStrategy.NetworkFirst] falls back to them, marked [RefreshState.Stalled] until a
     * later attempt confirms them; it then fetches nothing more. With nothing cached, or when the
     * server answers with an error, the flow fails.
     */
    public data object NetworkFirstThenCache : FlowReadStrategy

    /**
     * Emits the cached rows and never sends a request. A read of a shape the cache does not hold
     * fails with [CacheMissException].
     */
    public data object CacheOnly : FlowReadStrategy
}
