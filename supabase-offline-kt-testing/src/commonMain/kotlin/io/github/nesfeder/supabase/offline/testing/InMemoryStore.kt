// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryLocalData

/**
 * The local data of in-memory clients, kept from one client to the next, for a test of what an app
 * does after a restart: the cache, the mutation queue with the rejected and conflicted lists, and
 * the Storage file cache. Hand the same store to each client's `inMemory`:
 * ```kotlin
 * val store = InMemoryStore()
 * val first = createSupabaseClient(url, key) {
 *     httpEngine = MockEngine { request -> … }
 *     install(Postgrest) { maxRetries = 0 }
 *     install(Offline) { inMemory(store = store) }
 * }
 * first.offline.disableNetwork()
 * first.offline.from("tasks").insert(buildJsonObject { put("title", "Milk") })
 * first.close() // the app is killed
 *
 * val second = createSupabaseClient(url, key) { … install(Offline) { inMemory(store = store) } }
 * second.offline.syncPendingMutations() // the relaunched app sends the write queued before
 * ```
 *
 * A client built over the store after an earlier one's `close()` finds what that one left: the
 * writes still queued, which it replays, the rejected and conflicted writes, the cached queries,
 * and the cached files. Nothing a close does clears the store; [Offline.clearLocalData] does, as it
 * clears a database. What the library keeps only in a client's memory starts over, as after a
 * relaunch: the status's `lastSyncedAt` and `lastSyncError`, the switches `disableNetwork()` and
 * `disableRealtime()`, and the waits between failed passes.
 *
 * One client at a time: building a client over a store that another holds, open or still closing,
 * fails with an [IllegalStateException], as two clients over one database do, since both would
 * replay the same queued writes. Close the first client before building the next.
 */
public class InMemoryStore {

    /** The engine's in-memory tables and files this store keeps. */
    @OptIn(OfflineInternal::class) internal val localData: InMemoryLocalData = InMemoryLocalData()
}
