@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.jan.supabase.exceptions.UnauthorizedRestException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.time.ExperimentalTime

/**
 * Behavioral tests for [OfflineBucketApi]'s download-with-disk-cache path: successful caching,
 * transport-failure cache fallback, object-missing eviction (byte-array and byte-channel
 * overloads), transient-auth-failure cache retention, and cancellation propagation.
 *
 * The object-missing cases construct the exception the way Supabase Storage actually surfaces a
 * deleted object — a [NotFoundRestException] carried on an **HTTP 400** whose body reports 404, so
 * its [io.github.jan.supabase.exceptions.RestException.statusCode] reads 400. That is the case only
 * the type-based branch of `indicatesServerObjectMissing` catches; a status-only `== 404` check
 * (the original plan) would miss it, so these tests pin the deviation rather than passing by
 * accident on a literal-404 response.
 *
 * Backed by the real okio [FileSystem.SYSTEM] against a per-test temp directory (okio's
 * `FakeFileSystem` isn't a dependency and `java.io.tmpdir` is JVM-only), plus the in-memory
 * offline-store DAO fakes. Each test gets a fresh base dir that is wiped in [tearDown].
 */
class OfflineBucketApiTest {

    private val createdDirs = mutableListOf<Path>()
    private lateinit var baseDir: Path
    private lateinit var store: OfflineStore
    private lateinit var fake: FakeBucketApi
    private lateinit var offlineApi: OfflineBucketApi

    private val localFile: Path get() = resolveLocalPath(baseDir, BUCKET, OBJECT_PATH)

    @BeforeTest
    fun setUp() {
        baseDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-bucket-test-${Random.nextLong()}"
        createdDirs += baseDir
        store = newTestOfflineStore()
        fake = FakeBucketApi(bucketId = BUCKET)
        offlineApi = OfflineBucketApi(
            delegate = fake,
            offlineStore = store,
            baseDirectoryProvider = { baseDir },
        )
    }

    @AfterTest
    fun tearDown() {
        createdDirs.forEach { FileSystem.SYSTEM.deleteRecursively(it, mustExist = false) }
    }

    @Test
    fun successful_download_caches_bytes_then_serves_cache_on_network_failure() = runTest {
        // Given a first successful network download
        val payload = byteArrayOf(1, 2, 3, 4)
        fake.onDownload = { payload }
        val first = offlineApi.downloadAuthenticated(OBJECT_PATH)

        // Then the bytes are returned and both the disk file and the Room row are written
        assertContentEquals(payload, first, "network bytes returned")
        assertTrue(exists(localFile), "object cached on disk")
        assertNotNull(store.getStorageByKey(BUCKET, OBJECT_PATH), "cache metadata row tracked")
        assertEquals(1, fake.downloadInvocations, "network attempted on the first read")

        // When a later download fails at the transport layer
        fake.onDownload = { throw IOException("offline") }
        val second = offlineApi.downloadAuthenticated(OBJECT_PATH)

        // Then the network is re-attempted (not blindly short-circuited to cache) and, only after
        // it fails, the cached bytes are served instead
        assertEquals(2, fake.downloadInvocations, "network re-attempted before falling back to cache")
        assertContentEquals(payload, second, "cached bytes served on transport failure")
    }

    @Test
    fun not_found_download_evicts_cache_and_rethrows() = runTest {
        // Given an object cached from a prior successful download
        val payload = byteArrayOf(9)
        fake.onDownload = { payload }
        offlineApi.downloadAuthenticated(OBJECT_PATH)
        assertTrue(exists(localFile), "precondition: object cached on disk")
        assertNotNull(store.getStorageByKey(BUCKET, OBJECT_PATH), "precondition: cache row tracked")

        // When the object is now missing on the server. Supabase Storage surfaces this as a
        // NotFoundRestException carried on an HTTP 400 (body reports 404), so statusCode reads 400 —
        // only the exception TYPE marks it as object-missing, which is what this asserts.
        val notFound = notFoundRestException()
        assertEquals(400, notFound.statusCode, "production carrier is HTTP 400, not a literal 404")
        fake.onDownload = { throw notFound }

        // Then the failure is rethrown (cache is NOT served) and the stale cache is evicted
        val thrown = assertFailsWith<NotFoundRestException> {
            offlineApi.downloadAuthenticated(OBJECT_PATH)
        }
        assertSame(notFound, thrown, "original not-found error rethrown, not swallowed")
        assertFalse(exists(localFile), "stale cache file evicted when object missing")
        assertNull(store.getStorageByKey(BUCKET, OBJECT_PATH), "stale cache row evicted when object missing")
    }

    @Test
    fun cancellation_during_download_propagates_and_does_not_serve_cache() = runTest {
        // Given a warm disk cache from a prior successful download
        val payload = byteArrayOf(4, 2)
        fake.onDownload = { payload }
        offlineApi.downloadAuthenticated(OBJECT_PATH)
        assertTrue(exists(localFile), "precondition: object cached on disk")

        // ... and a download that blocks in-flight until its coroutine is cancelled
        fake.onDownload = { awaitCancellation() }
        var served: ByteArray? = null
        var caught: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                served = offlineApi.downloadAuthenticated(OBJECT_PATH)
            } catch (error: Throwable) {
                caught = error
            }
        }

        // When the in-flight download is cancelled
        job.cancelAndJoin()

        // Then cancellation propagates and the warm cache is NOT served
        assertNull(served, "cancelled download must not serve cache bytes")
        assertTrue(caught is CancellationException, "expected CancellationException, got $caught")
    }

    @Test
    fun transient_auth_failure_serves_cache_and_does_not_evict() = runTest {
        // Given an object cached from a prior successful download
        val payload = byteArrayOf(7, 7, 7)
        fake.onDownload = { payload }
        offlineApi.downloadAuthenticated(OBJECT_PATH)
        assertTrue(exists(localFile), "precondition: object cached on disk")
        assertNotNull(store.getStorageByKey(BUCKET, OBJECT_PATH), "precondition: cache row tracked")

        // When a later read fails with a transient auth error (expired token / RLS blip → 403),
        // which must NOT be mistaken for a deleted object
        val forbidden = UnauthorizedRestException("Forbidden", httpResponse(HttpStatusCode.Forbidden), "Forbidden")
        fake.onDownload = { throw forbidden }
        val served = offlineApi.downloadAuthenticated(OBJECT_PATH)

        // Then the warm cache is served through the blip and is NOT evicted (a 403 is not an
        // object-missing signal — evicting here would nuke good cache on a token refresh)
        assertContentEquals(payload, served, "cache served through a transient auth failure")
        assertTrue(exists(localFile), "cache file retained on 403 (not evicted)")
        assertNotNull(store.getStorageByKey(BUCKET, OBJECT_PATH), "cache row retained on 403 (not evicted)")
    }

    @Test
    fun not_found_on_channel_download_propagates_without_serving_cache() = runTest {
        // Given a warm disk cache from a prior successful download
        val payload = byteArrayOf(5, 5)
        fake.onDownload = { payload }
        offlineApi.downloadAuthenticated(OBJECT_PATH)
        assertTrue(exists(localFile), "precondition: object cached on disk")

        // When the byte-channel download finds the object missing on the server
        val notFound = notFoundRestException()
        fake.onDownload = { throw notFound }
        val channel = ByteChannel(autoFlush = true)

        // Then the not-found error propagates (no cache bytes are ever streamed to the channel,
        // because the wrapper resolves the full result before touching the channel) and the stale
        // cache is evicted — the byte-channel overload funnels through the same guard.
        val thrown = assertFailsWith<NotFoundRestException> {
            offlineApi.downloadAuthenticated(OBJECT_PATH, channel)
        }
        assertSame(notFound, thrown, "channel overload rethrows the original not-found error")
        assertFalse(exists(localFile), "stale cache file evicted on channel object-missing")
    }

    /**
     * Builds a real [NotFoundRestException] the way Supabase Storage surfaces a missing object: an
     * HTTP **400** response whose JSON body reports 404. `RestException.statusCode` therefore reads
     * 400, so only the exception TYPE (not a `statusCode == 404` check) identifies it as missing.
     */
    private suspend fun notFoundRestException(): NotFoundRestException =
        NotFoundRestException("Object not found", httpResponse(HttpStatusCode.BadRequest), "Object not found")

    /**
     * Produces a real ktor [HttpResponse] carrying [status] via a [MockEngine], since supabase-kt's
     * `RestException` subclasses require a live response and cannot be constructed standalone.
     */
    private suspend fun httpResponse(status: HttpStatusCode): HttpResponse {
        val client = HttpClient(MockEngine) {
            engine { addHandler { respondError(status) } }
            expectSuccess = true
        }
        val responseException = assertFailsWith<ResponseException> {
            client.get("https://unit-test.supabase.co/storage/v1/object/authenticated/$BUCKET/$OBJECT_PATH")
        }
        return responseException.response
    }

    private companion object {
        const val BUCKET = "avatars"
        const val OBJECT_PATH = "me.png"
    }
}
