// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineInternal
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
 * first. A [CachedFile.path] names a file that exists only in the client's memory: read it through
 * the offline bucket, not from disk. To take the client offline, fail the requests in the mock
 * engine. supabase-kt's own plugins keep their own state: in a test, give Storage a
 * `MemoryResumableCache` and Auth a session manager that keeps the session in memory.
 *
 * @param clock the time the client sees: when rows were fetched and writes queued, and so how old a
 *   cached read is against a `maxAge`.
 */
@OptIn(OfflineInternal::class)
public fun Offline.Config.inMemory(clock: Clock = Clock.System) {
    keepLocalDataInMemory(clock)
}
