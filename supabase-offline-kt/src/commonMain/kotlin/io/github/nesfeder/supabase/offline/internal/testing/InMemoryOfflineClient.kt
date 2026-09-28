@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.nesfeder.supabase.offline.InternalOfflineTestingApi
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.SupabaseOfflineClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineClientImpl
import io.github.nesfeder.supabase.offline.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import okio.Path.Companion.toPath

/**
 * Builds a fully in-memory [SupabaseOfflineClient] backed by non-persistent DAOs — no Room, no
 * platform database driver, no filesystem — so it runs deterministically in `commonTest` across
 * every target.
 *
 * The client is wired exactly like the production one (real [SupabaseOfflineClientImpl] over a real
 * [OfflineStore]); only the storage substrate is swapped for in-memory maps that mirror Room's
 * per-table invalidation semantics. Size-based eviction is disabled (unlimited cache).
 *
 * This is the hook behind `buildInMemorySupabaseOfflineClient` in the `supabase-offline-kt-testing`
 * artifact. It lives here because the in-memory DAOs implement the SDK's internal storage
 * interfaces, which stay internal; the testing artifact is the supported entry point.
 *
 * @param supabase the (typically mock-engine) Supabase client the offline client wraps.
 * @param coordinatorScope scope hosting the client-scoped refresh loops; pass a test-dispatcher
 *   scope so refresh hops are driven by the test scheduler.
 * @param nowMillis clock used for cache-row timestamps; defaults to a fixed `0L` for determinism.
 * @return an offline client whose cache, storage metadata and mutation queue live in memory.
 */
@InternalOfflineTestingApi
public fun createInMemorySupabaseOfflineClient(
    supabase: SupabaseClient,
    coordinatorScope: CoroutineScope,
    nowMillis: () -> Long = { 0L },
): SupabaseOfflineClient = SupabaseOfflineClientImpl(
    supabase = supabase,
    store = OfflineStore(
        objectDao = InMemoryObjectDao(),
        storageDao = InMemoryStorageDao(),
        mutationDao = InMemoryMutationDao(),
        dbPathProvider = InMemoryDbPathProvider,
        settings = OfflineCacheSettings(
            maxDbSizeBytes = null,
            evictionBatchSize = 0,
            minRetainedBytes = 0,
        ),
        nowMillis = nowMillis,
        dbSizeProvider = { null },
    ),
    baseDirectoryProvider = { IN_MEMORY_BASE_DIRECTORY.toPath() },
    sdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    coordinatorScope = coordinatorScope,
)
