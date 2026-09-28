package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.SupabaseClientBuilder
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.SupabaseOfflineClientImpl
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.ObjectDao
import io.github.nesfeder.supabase.offline.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.StorageDao
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryDbPathProvider
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryStorageDao
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import okio.Path
import okio.Path.Companion.toPath

/*
 * The one place the engine's tests build the offline stack. Start here when writing a test:
 *
 * - newTestOfflineStore — a real OfflineStore over the in-memory DAOs (FakeObjectDao /
 *   FakeMutationDao add recording and scripting hooks; InMemoryStorageDao has none to add).
 * - newTestSupabaseClient / newTestPostgrest — a real supabase-kt client on a Ktor MockEngine.
 * - newTestOfflineClient — the real SupabaseOfflineClientImpl over the two.
 *
 * To script the PostgREST handle without HTTP, use FakePostgrestQueryBuilderWrapper.
 */

/** Project URL every test client points at. Never contacted: requests go to a MockEngine. */
internal const val TEST_SUPABASE_URL: String = "https://unit-test.supabase.co"

/** API key every test client sends. Not a real key. */
internal const val TEST_SUPABASE_KEY: String = "unit-test-anon-key"

/** Cache settings with size-based eviction off — what every test but the eviction tests uses. */
internal val UNLIMITED_TEST_CACHE_SETTINGS: OfflineCacheSettings = OfflineCacheSettings(
    maxDbSizeBytes = null,
    evictionBatchSize = 0,
    minRetainedBytes = 0,
)

/**
 * Builds a real [OfflineStore] over in-memory DAOs, so a test drives the store's real logic without
 * Room or a filesystem.
 *
 * @param objectDao object-cache DAO; pass a [FakeObjectDao] to seed or inspect cached payloads.
 * @param storageDao storage-metadata DAO.
 * @param mutationDao mutation-queue DAO; pass a [FakeMutationDao] to inspect what was queued.
 * @param settings eviction settings; eviction is off by default.
 * @param nowMillis clock for row timestamps; defaults to a fixed `0L`.
 * @param monotonicTick tick source for the stale-refresh watermark, or `null` for the store's own.
 * @return a store wired to the given DAOs.
 */
internal fun newTestOfflineStore(
    objectDao: ObjectDao = FakeObjectDao(),
    storageDao: StorageDao = InMemoryStorageDao(),
    mutationDao: MutationDao = FakeMutationDao(),
    settings: OfflineCacheSettings = UNLIMITED_TEST_CACHE_SETTINGS,
    nowMillis: () -> Long = { 0L },
    monotonicTick: (() -> Long)? = null,
): OfflineStore = if (monotonicTick == null) {
    OfflineStore(
        objectDao = objectDao,
        storageDao = storageDao,
        mutationDao = mutationDao,
        dbPathProvider = InMemoryDbPathProvider,
        settings = settings,
        nowMillis = nowMillis,
        dbSizeProvider = { null },
    )
} else {
    OfflineStore(
        objectDao = objectDao,
        storageDao = storageDao,
        mutationDao = mutationDao,
        dbPathProvider = InMemoryDbPathProvider,
        settings = settings,
        nowMillis = nowMillis,
        dbSizeProvider = { null },
        monotonicTick = monotonicTick,
    )
}

/**
 * Builds a real supabase-kt client whose HTTP goes to [engine] instead of the network.
 *
 * @param engine the Ktor engine answering every request; by default one that answers `200 []`.
 * @param plugins plugin installation; PostgREST by default. Pass `{}` for a client with none.
 * @return the client.
 */
internal fun newTestSupabaseClient(
    engine: HttpClientEngine = MockEngine { respondOk("[]") },
    plugins: SupabaseClientBuilder.() -> Unit = { install(Postgrest) },
): SupabaseClient = createSupabaseClient(
    supabaseUrl = TEST_SUPABASE_URL,
    supabaseKey = TEST_SUPABASE_KEY,
) {
    httpEngine = engine
    plugins()
}

/** The [Postgrest] plugin of a default [newTestSupabaseClient]. */
internal fun newTestPostgrest(): Postgrest = newTestSupabaseClient().postgrest

/**
 * Builds the real [SupabaseOfflineClientImpl] over a test store and client.
 *
 * @param supabase the wrapped client.
 * @param store the offline store.
 * @param baseDirectoryProvider base directory for downloaded storage files.
 * @param sdkLogger the SDK logger.
 * @return the offline client.
 */
internal fun newTestOfflineClient(
    supabase: SupabaseClient = newTestSupabaseClient(),
    store: OfflineStore = newTestOfflineStore(),
    baseDirectoryProvider: () -> Path = { "/tmp".toPath() },
    sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
): SupabaseOfflineClientImpl = SupabaseOfflineClientImpl(
    supabase = supabase,
    store = store,
    baseDirectoryProvider = baseDirectoryProvider,
    sdkLogger = sdkLogger,
)
