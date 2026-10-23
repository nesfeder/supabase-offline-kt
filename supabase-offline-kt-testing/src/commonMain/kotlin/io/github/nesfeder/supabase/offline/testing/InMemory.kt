// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.SyncStatus
import io.github.nesfeder.supabase.offline.internal.testing.followServerChangesThrough
import io.github.nesfeder.supabase.offline.internal.testing.keepLocalDataInMemory
import kotlin.time.Clock

/**
 * Keeps the plugin's local data in memory, for tests: the cache, the mutation queue and the Storage
 * file cache live in the client, and nothing is written to disk. Every other setting applies as it
 * does in an app, so a test builds its client exactly as production does:
 * ```kotlin
 * val supabase = createSupabaseClient(url, key) {
 *     httpEngine = MockEngine { request -> … }
 *     install(Postgrest)
 *     install(Offline) { inMemory(clock = testClock) }
 * }
 * ```
 *
 * Each client starts empty, and a second client over the same configuration shares nothing with the
 * first; to have a client find what an earlier one left, as an app relaunched on a device does,
 * build both over one [InMemoryStore]. A [CachedFile.path] names a file that exists only in the
 * client's memory: read it through the offline bucket, not from disk. supabase-kt's own plugins
 * keep their own state: in a test, give Storage a `MemoryResumableCache` and Auth a session manager
 * that keeps the session in memory.
 *
 * The client watches no connectivity monitor: [SyncStatus.network] stays [NetworkStatus.UNKNOWN],
 * which counts as online, unless the test sets [Offline.Config.networkAvailability] or calls
 * [Offline.disableNetwork]. To take the client offline, call `disableNetwork()`: reads are answered
 * from the cache and writes are queued, as on a device in airplane mode, until `enableNetwork()`.
 * To see how the app meets a server that fails or a connection that drops, fail the requests in the
 * mock engine instead.
 *
 * The client runs on real time, not a test's virtual clock. supabase-kt retries a request that
 * fails at the transport, so a read the cache cannot answer waits out its retries (about seven
 * seconds) unless the test installs `Postgrest { maxRetries = 0 }`. A refresh or a replay that
 * failed waits a few seconds, then up to a minute, before it tries again by itself: a
 * `networkAvailability` flow turning `true` wakes the refresh at once, and the replay too with
 * `autoSync` on, and `syncPendingMutations()` sends without waiting.
 *
 * Use it under Robolectric too. The platform's own database needs a SQLite binary for the host, and
 * it records the application context once per process, so a later test's client would open an
 * earlier test's database.
 *
 * @param clock the time the client sees: when rows were fetched and writes queued, and so how old a
 *   cached read is against a `maxAge`.
 */
@OptIn(OfflineInternal::class)
public fun Offline.Config.inMemory(clock: Clock = Clock.System) {
    keepLocalDataInMemory(clock)
}

/**
 * [inMemory], with the server's row changes reported by [realtime] in place of a Realtime channel,
 * so a test of an app that names tables in `realtime { }` needs no `install(Realtime)`:
 * ```kotlin
 * install(Offline) {
 *     inMemory(realtime = fakeRealtime)
 *     realtime { table("tasks") }
 * }
 * ```
 *
 * @param clock the time the client sees, as for [inMemory].
 * @param realtime where the client's channel is joined and the test reports changes.
 */
@OptIn(OfflineInternal::class)
public fun Offline.Config.inMemory(clock: Clock = Clock.System, realtime: FakeRealtime) {
    keepLocalDataInMemory(clock)
    followServerChangesThrough(realtime.channelHook)
}

/**
 * [inMemory], over [store]: the client starts with what an earlier client over [store] left there
 * (the queued writes, which it replays, the rejected and conflicted lists, the cached queries and
 * files) and leaves its own there when it closes. A test closes one client and builds the next to
 * see what the app does after it is killed and relaunched:
 * ```kotlin
 * install(Offline) { inMemory(store = store) }
 * ```
 *
 * Building the client fails with an [IllegalStateException] while another client holds [store],
 * open or still closing: close that one first.
 *
 * @param clock the time the client sees, as for [inMemory].
 * @param store the local data the client keeps, shared with the clients before and after it.
 */
@OptIn(OfflineInternal::class)
public fun Offline.Config.inMemory(clock: Clock = Clock.System, store: InMemoryStore) {
    keepLocalDataInMemory(clock, store.localData)
}

/**
 * [inMemory] over [store], as the overload that takes a store alone, with the server's row changes
 * reported by [realtime] in place of a Realtime channel, as the overload that takes a
 * [FakeRealtime] alone.
 *
 * @param clock the time the client sees, as for [inMemory].
 * @param realtime where the client's channel is joined and the test reports changes.
 * @param store the local data the client keeps, shared with the clients before and after it.
 */
@OptIn(OfflineInternal::class)
public fun Offline.Config.inMemory(
    clock: Clock = Clock.System,
    realtime: FakeRealtime,
    store: InMemoryStore,
) {
    keepLocalDataInMemory(clock, store.localData)
    followServerChangesThrough(realtime.channelHook)
}
