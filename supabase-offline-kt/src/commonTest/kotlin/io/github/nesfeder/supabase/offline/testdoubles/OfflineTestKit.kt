// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.SupabaseClientBuilder
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.storage.BucketApi
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.ClientOperationGate
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.internal.db.CachedFileDao
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
import io.github.nesfeder.supabase.offline.internal.newEngineScope
import io.github.nesfeder.supabase.offline.internal.postgrest.CLIENT_CLOSED_MESSAGE
import io.github.nesfeder.supabase.offline.internal.postgrest.ResponseTrustPolicy
import io.github.nesfeder.supabase.offline.internal.postgrest.queryShapeOf
import io.github.nesfeder.supabase.offline.internal.storage.CachingBucketApi
import io.github.nesfeder.supabase.offline.internal.storage.StorageFileCache
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/*
 * The one place the engine's tests build the offline stack. Start here when writing a test:
 *
 * - newTestOfflineStore — a real OfflineStore over the in-memory DAOs (FakeQueryCacheDao /
 *   FakeMutationDao add recording and scripting hooks; InMemoryCachedFileDao has none to add).
 * - newTestSupabaseClient / newTestPostgrest — a real supabase-kt client on a Ktor MockEngine.
 * - newTestOfflineClient — the real OfflineClient over the two.
 *
 * To observe what goes on the wire, build the client from a RecordingPostgrestServer. To script
 * the PostgREST handle without HTTP, use FakeRemoteTable.
 */

/** Project URL every test client points at. Never contacted: requests go to a MockEngine. */
internal const val TEST_SUPABASE_URL: String = "https://unit-test.supabase.co"

/** API key every test client sends. Not a real key. */
internal const val TEST_SUPABASE_KEY: String = "unit-test-anon-key"

/** Cache settings with size-based eviction off — what every test but the eviction tests uses. */
internal val UNLIMITED_TEST_CACHE_SETTINGS: QueryCacheLimits =
    QueryCacheLimits(
        maxPayloadBytes = null,
        evictionBatchSize = 0,
        evictionHeadroomBytes = 0,
    )

/**
 * Builds a real [OfflineStore] over in-memory DAOs, so a test drives the store's real logic without
 * Room or a filesystem.
 *
 * @param queryCacheDao object-cache DAO; pass a [FakeQueryCacheDao] to seed or inspect cached
 *   payloads.
 * @param cachedFileDao storage-metadata DAO.
 * @param mutationDao mutation-queue DAO; pass a [FakeMutationDao] to inspect what was queued.
 * @param queryCacheLimits eviction settings; eviction is off by default.
 * @param nowMillis clock for row timestamps; defaults to a fixed `0L`.
 * @param monotonicTick tick source for the stale-refresh watermark, or `null` for the store's own.
 * @return a store wired to the given DAOs.
 */
internal fun newTestOfflineStore(
    queryCacheDao: QueryCacheDao = FakeQueryCacheDao(),
    cachedFileDao: CachedFileDao = InMemoryCachedFileDao(),
    mutationDao: MutationDao = FakeMutationDao(),
    queryCacheLimits: QueryCacheLimits = UNLIMITED_TEST_CACHE_SETTINGS,
    nowMillis: () -> Long = { 0L },
    monotonicTick: (() -> Long)? = null,
): OfflineStore =
    if (monotonicTick == null) {
        OfflineStore(
            queryCacheDao = queryCacheDao,
            cachedFileDao = cachedFileDao,
            mutationDao = mutationDao,
            queryCacheLimits = queryCacheLimits,
            nowMillis = nowMillis,
        )
    } else {
        OfflineStore(
            queryCacheDao = queryCacheDao,
            cachedFileDao = cachedFileDao,
            mutationDao = mutationDao,
            queryCacheLimits = queryCacheLimits,
            nowMillis = nowMillis,
            monotonicTick = monotonicTick,
        )
    }

/**
 * Builds a real supabase-kt client whose HTTP goes to [engine] instead of the network.
 *
 * @param engine the Ktor engine answering every request; by default one that answers `200 []`.
 * @param logs records what the offline plugin logs through the client's logging, at [logLevel];
 *   `null` leaves supabase-kt's default logging in place.
 * @param logLevel the client's default log level while [logs] records.
 * @param plugins plugin installation; PostgREST by default. Pass `{}` for a client with none.
 * @return the client.
 */
internal fun newTestSupabaseClient(
    engine: HttpClientEngine = MockEngine { respondOk("[]") },
    logs: RecordingLogSink? = null,
    logLevel: LogLevel = LogLevel.INFO,
    plugins: SupabaseClientBuilder.() -> Unit = { install(Postgrest) },
): SupabaseClient =
    createSupabaseClient(
        supabaseUrl = TEST_SUPABASE_URL,
        supabaseKey = TEST_SUPABASE_KEY,
    ) {
        httpEngine = engine
        if (logs != null) {
            defaultLogLevel = logLevel
            defaultLoggingFactory = logs.supabaseLoggingFactory
        }
        plugins()
    }

/** The [Postgrest] plugin of a default [newTestSupabaseClient]. */
internal fun newTestPostgrest(): Postgrest = newTestSupabaseClient().postgrest

/**
 * Records that the query cached under [cacheKey] has the shape of a SELECT of [columns] that
 * [request] configures, as a read through the client would.
 *
 * @param persist whether to write the record to the object cache as well; by default it is kept in
 *   memory only, so a test counting cache writes is not disturbed.
 */
internal suspend fun OfflineStore.recordQueryShape(
    table: String,
    cacheKey: String,
    columns: Columns = Columns.ALL,
    persist: Boolean = false,
    request: SelectRequestBuilder.() -> Unit,
) {
    val shape =
        queryShapeOf(
            columns,
            SelectRequestBuilder("public", PropertyConversionMethod.NONE).apply(request),
        )
    queryShapes.remember(table, cacheKey, shape, persist)
}

/**
 * Builds the real [OfflineClient] over a test store and client.
 *
 * @param supabase the wrapped client.
 * @param store the offline store.
 * @param baseDirectoryProvider base directory for downloaded storage files.
 * @param engineLogger the SDK logger.
 * @param onClose what the client's `close()` releases, as the platforms' open paths wire the
 *   database.
 * @param coordinatorScope the parent of the client's own scope, whose dispatcher runs its
 *   background work (refresh loops, replay passes it starts itself); pass `runTest`'s
 *   `backgroundScope` to drive it from the test scheduler. The default's handler logs a background
 *   failure to [engineLogger] instead of failing the test; build that logger from a
 *   [RecordingLogSink] to see one.
 * @param scrubDatabase what `clearLocalData` runs after the store's wipe, as the platforms' open
 *   paths wire the database's scrub.
 * @param closeWait how long `close()` waits for calls in flight. Shorten it for a client whose
 *   background work can never run (see [idleCoroutineScope]): its `close()` always waits it out.
 * @param onStillClosing what the client reports when `close()` outlives [closeWait], as the
 *   factories tell the registry; it is handed the count of calls still in flight.
 * @param primaryKeys each table's primary-key columns; `id` for every table by default.
 * @param responseTrust which read responses may replace what the cache holds.
 * @param config the plugin configuration the client reports, and reads `autoSync` from.
 * @param networkAvailability the connectivity signal the client watches; `null` for none.
 * @return the offline client.
 */
internal fun newTestOfflineClient(
    supabase: SupabaseClient = newTestSupabaseClient(),
    store: OfflineStore = newTestOfflineStore(),
    baseDirectoryProvider: () -> Path = { "/tmp".toPath() },
    engineLogger: EngineLogger = EngineLogger(null, EngineLogLevel.INFO),
    onClose: () -> Unit = {},
    coordinatorScope: CoroutineScope = newEngineScope(engineLogger),
    scrubDatabase: suspend () -> Unit = {},
    closeWait: Duration = OfflineClient.CLOSE_WAIT,
    onStillClosing: (callsInFlight: () -> Int) -> Unit = {},
    primaryKeys: PrimaryKeys = DefaultPrimaryKeys,
    responseTrust: ResponseTrustPolicy = ResponseTrustPolicy.SETTLED_SESSION,
    config: Offline.Config = Offline.Config(),
    networkAvailability: Flow<Boolean>? = null,
): OfflineClient =
    OfflineClient(
        supabaseClient = supabase,
        store = store,
        baseDirectoryProvider = baseDirectoryProvider,
        engineLogger = engineLogger,
        coordinatorScope = coordinatorScope,
        scrubDatabase = scrubDatabase,
        onClose = onClose,
        closeWait = closeWait,
        onStillClosing = onStillClosing,
        primaryKeys = primaryKeys,
        responseTrust = responseTrust,
        config = config,
        networkAvailability = networkAvailability,
    )

/**
 * Builds the Storage file cache over [store], under [baseDirectory] (a real directory: the storage
 * tests run against the real file system).
 *
 * @param baseDirectory the offline data directory; files land under `<baseDirectory>/storage`.
 * @param store the offline store holding the cache rows.
 * @param engineLogger the SDK logger.
 * @param fileSystem the file system; pass a [FakeFailingFileSystem] to make it fail.
 * @param maxSizeBytes the size bound, or `null` for none.
 * @param nowMillis the clock use times are stamped from.
 * @return the cache.
 */
internal fun newTestFileCache(
    baseDirectory: Path,
    store: OfflineStore = newTestOfflineStore(),
    engineLogger: EngineLogger = EngineLogger(null, EngineLogLevel.INFO),
    fileSystem: FileSystem = FileSystem.SYSTEM,
    maxSizeBytes: Long? = null,
    nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
): StorageFileCache =
    StorageFileCache(
        store = store,
        dataDirectoryProvider = { baseDirectory },
        maxSizeBytes = maxSizeBytes,
        engineLogger = engineLogger,
        fileSystem = fileSystem,
        nowMillis = nowMillis,
    )

/**
 * Builds the offline bucket over [delegate], caching under [baseDirectory] (a real directory: the
 * storage tests run against the real file system).
 *
 * @param delegate the bucket the offline one forwards to: a [FakeBucketApi], or a real one from a
 *   [RecordingStorageServer] client.
 * @param baseDirectory the offline data directory; files land under `<baseDirectory>/storage`.
 * @param store the offline store holding the cache rows.
 * @param engineLogger the SDK logger.
 * @param cache the file cache; by default one over [baseDirectory], [store] and [engineLogger].
 * @param backgroundScope where the bucket waits for resumable uploads to finish; by default an
 *   engine scope of its own, as the client's is.
 * @param operationGate the gate every member passes; pass one to close it as the client's close
 *   does.
 * @param networkAccess the network switch the bucket honours; on by default.
 * @return the offline bucket.
 */
internal fun newTestBucketApi(
    delegate: BucketApi,
    baseDirectory: Path,
    store: OfflineStore = newTestOfflineStore(),
    engineLogger: EngineLogger = EngineLogger(null, EngineLogLevel.INFO),
    cache: StorageFileCache =
        newTestFileCache(baseDirectory = baseDirectory, store = store, engineLogger = engineLogger),
    backgroundScope: CoroutineScope = newEngineScope(engineLogger),
    operationGate: ClientOperationGate = ClientOperationGate(),
    networkAccess: NetworkAccess = NetworkAccess(),
): CachingBucketApi =
    CachingBucketApi(
        delegate = delegate,
        cache = cache,
        engineLogger = engineLogger,
        backgroundScope = backgroundScope,
        operationGate = operationGate,
        networkAccess = networkAccess,
    )

/**
 * Runs [block] with this client, then closes it, whatever [block] does — the suspending `close()`'s
 * counterpart of `use`.
 *
 * @param block what to do with the client.
 * @return what [block] returns.
 */
internal suspend fun <T> Offline.closingAfter(block: suspend (Offline) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }

/**
 * A scope whose coroutines never run: nothing ever advances its scheduler. Pass it as a client's
 * `coordinatorScope` in a test that drives every replay pass itself, so a pass the engine starts on
 * its own cannot race the one under test.
 *
 * @return the scope.
 */
internal fun idleCoroutineScope(): CoroutineScope =
    CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()))

/**
 * Asserts [failure] is the client's closed error: an [IllegalStateException] saying so, and never a
 * cancellation, which is an [IllegalStateException] too on every platform.
 *
 * @param failure what a call on a closed client threw.
 * @param context what the call was, for the failure message.
 */
internal fun assertClientClosedError(failure: Throwable?, context: String = "the call") {
    assertTrue(failure !is CancellationException, "$context gave a cancellation: $failure")
    assertIs<IllegalStateException>(failure, "$context gave $failure")
    assertEquals(CLIENT_CLOSED_MESSAGE, failure.message, "$context gave $failure")
}

/**
 * Asserts [block] throws [MutationNotQueueableException] whose cause is a [T]: a write the queue
 * could not hold was sent at once, and that failure stopped it.
 *
 * @param block the write.
 * @return the cause.
 */
internal inline fun <reified T : Throwable> assertNotQueueable(block: () -> Unit): T {
    val failure = assertFailsWith<MutationNotQueueableException> { block() }
    return assertIs<T>(failure.cause, "the cause was ${failure.cause}")
}
