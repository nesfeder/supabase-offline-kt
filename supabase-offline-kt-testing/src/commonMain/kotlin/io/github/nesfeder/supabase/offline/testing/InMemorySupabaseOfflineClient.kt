// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.internal.testing.createInMemorySupabaseOfflineClient
import kotlinx.coroutines.CoroutineScope

/**
 * Builds a fully in-memory [Offline] client backed by non-persistent storage — no Room, no platform
 * database driver, no filesystem — so it runs deterministically in `commonTest` across every
 * target.
 *
 * The client is wired exactly like the production one; only the storage substrate is swapped for
 * in-memory maps that mirror Room's per-table invalidation semantics. Size-based eviction is
 * disabled (unlimited cache). Use it to exercise your own repositories and APIs against the real
 * offline stack — cache-first reads, queued writes, replay — without reaching into the SDK's
 * internal construction details.
 *
 * Pair it with a Ktor `MockEngine` on the wrapped [supabase] client to script the server.
 *
 * @param supabase the (typically mock-engine) Supabase client the offline client wraps.
 * @param coordinatorScope scope hosting the client-scoped refresh loops; pass a test-dispatcher
 *   scope (e.g. `runTest`'s `backgroundScope`) so refresh hops are driven by the test scheduler.
 * @param nowMillis clock used for cache-row timestamps; defaults to a fixed `0L` for determinism.
 * @return an offline client whose cache, storage metadata and mutation queue live in memory.
 */
@OptIn(OfflineInternal::class)
public fun buildInMemorySupabaseOfflineClient(
    supabase: SupabaseClient,
    coordinatorScope: CoroutineScope,
    nowMillis: () -> Long = { 0L },
): Offline =
    createInMemorySupabaseOfflineClient(
        supabase = supabase,
        coordinatorScope = coordinatorScope,
        nowMillis = nowMillis,
    )
