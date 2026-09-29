package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.SupabaseClientBuilder
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.SupabaseOfflineClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineClientImpl
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.postgrest.CLIENT_CLOSED_MESSAGE
import io.github.nesfeder.supabase.offline.internal.newEngineScope
import io.github.nesfeder.supabase.offline.internal.db.ObjectDao
import io.github.nesfeder.supabase.offline.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.StorageDao
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryDbPathProvider
import io.github.nesfeder.supabase.offline.internal.storage.CachingBucketApi
import io.github.nesfeder.supabase.offline.internal.storage.StorageFileCache
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryStorageDao
import io.github.jan.supabase.storage.BucketApi
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration

/*
 * The one place the engine's tests build the offline stack. Start here when writing a test:
 *
 * - newTestOfflineStore — a real OfflineStore over the in-memory DAOs (FakeObjectDao /
 *   FakeMutationDao add recording and scripting hooks; InMemoryStorageDao has none to add).
 * - newTestSupabaseClient / newTestPostgrest — a real supabase-kt client on a Ktor MockEngine.
 * - newTestOfflineClient — the real SupabaseOfflineClientImpl over the two.
 *
 * To observe what goes on the wire, build the client from a RecordingPostgrestServer. To script
 * the PostgREST handle without HTTP, use FakePostgrestQueryBuilderWrapper.
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
 * @param onClose what the client's `close()` releases, as the factories wire the database.
 * @param coordinatorScope the parent of the client's own scope, whose dispatcher runs its background
 *   work (refresh loops, replay passes it starts itself); pass `runTest`'s `backgroundScope` to drive
 *   it from the test scheduler.
 * @param scrubDatabase what `clearAllCache` runs after the store's wipe, as the factories wire the
 *   database's scrub.
 * @param closeWait how long `close()` waits for calls in flight. Shorten it for a client whose
 *   background work can never run (see [idleCoroutineScope]): its `close()` always waits it out.
 * @return the offline client.
 */
internal fun newTestOfflineClient(
    supabase: SupabaseClient = newTestSupabaseClient(),
    store: OfflineStore = newTestOfflineStore(),
    baseDirectoryProvider: () -> Path = { "/tmp".toPath() },
    sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    onClose: () -> Unit = {},
    coordinatorScope: CoroutineScope = newEngineScope(sdkLogger),
    scrubDatabase: suspend () -> Unit = {},
    closeWait: Duration = SupabaseOfflineClientImpl.CLOSE_WAIT,
): SupabaseOfflineClientImpl = SupabaseOfflineClientImpl(
    supabase = supabase,
    store = store,
    baseDirectoryProvider = baseDirectoryProvider,
    sdkLogger = sdkLogger,
    coordinatorScope = coordinatorScope,
    scrubDatabase = scrubDatabase,
    onClose = onClose,
    closeWait = closeWait,
)

/**
 * Builds the Storage file cache over [store], under [baseDirectory] (a real directory: the storage
 * tests run against the real file system).
 *
 * @param baseDirectory the offline data directory; files land under `<baseDirectory>/storage`.
 * @param store the offline store holding the cache rows.
 * @param sdkLogger the SDK logger.
 * @param fileSystem the file system; pass a [FakeFailingFileSystem] to make it fail.
 * @param maxSizeBytes the size bound, or `null` for none.
 * @param nowMillis the clock use times are stamped from.
 * @return the cache.
 */
internal fun newTestFileCache(
    baseDirectory: Path,
    store: OfflineStore = newTestOfflineStore(),
    sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    fileSystem: FileSystem = FileSystem.SYSTEM,
    maxSizeBytes: Long? = null,
    nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
): StorageFileCache = StorageFileCache(
    store = store,
    dataDirectoryProvider = { baseDirectory },
    maxSizeBytes = maxSizeBytes,
    sdkLogger = sdkLogger,
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
 * @param sdkLogger the SDK logger.
 * @param cache the file cache; by default one over [baseDirectory], [store] and [sdkLogger].
 * @param backgroundScope where the bucket waits for resumable uploads to finish; by default an
 *   engine scope of its own, as the client's is.
 * @return the offline bucket.
 */
internal fun newTestBucketApi(
    delegate: BucketApi,
    baseDirectory: Path,
    store: OfflineStore = newTestOfflineStore(),
    sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    cache: StorageFileCache = newTestFileCache(baseDirectory = baseDirectory, store = store, sdkLogger = sdkLogger),
    backgroundScope: CoroutineScope = newEngineScope(sdkLogger),
): CachingBucketApi = CachingBucketApi(
    delegate = delegate,
    cache = cache,
    sdkLogger = sdkLogger,
    backgroundScope = backgroundScope,
)

/**
 * Runs [block] with this client, then closes it, whatever [block] does — the suspending `close()`'s
 * counterpart of `use`.
 *
 * @param block what to do with the client.
 * @return what [block] returns.
 */
internal suspend fun <T> SupabaseOfflineClient.closingAfter(block: suspend (SupabaseOfflineClient) -> T): T =
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
internal fun idleCoroutineScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()))

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
