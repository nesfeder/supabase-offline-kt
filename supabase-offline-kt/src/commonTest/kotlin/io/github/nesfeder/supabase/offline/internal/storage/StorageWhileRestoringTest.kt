package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.FakeHeldSessionManager
import io.github.nesfeder.supabase.offline.testdoubles.FakeResumableCache
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_KEY
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Storage does not wait for a stored session to finish restoring: a download or a write goes out at
 * once, on the anon key supabase-kt sends meanwhile, and a download's answer is cached as any other.
 */
class StorageWhileRestoringTest {

    private val dataDirectory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-restoring-${Random.nextLong().toULong()}"

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    /** Whose token each request carried, in order. */
    private val tokens = mutableListOf<String?>()

    /** A bucket of a client whose stored session restores and never settles, on this test's scheduler. */
    private fun TestScope.restoringBucket(): OfflineBucketApi {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = MockEngine(
            MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { request ->
                    tokens += request.headers["Authorization"]
                    if (request.method == HttpMethod.Get) {
                        respond(content = "bytes", status = HttpStatusCode.OK)
                    } else {
                        respond(
                            content = """{"Key":"$BUCKET/$PATH","Id":"id-1"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }
                }
            },
        )
        val supabase = newTestSupabaseClient(engine = engine) {
            coroutineDispatcher = dispatcher
            install(Storage) { resumable { cache = FakeResumableCache() } }
            install(Auth) {
                sessionManager = FakeHeldSessionManager(UserSession(accessToken = testJwt("u-1"), refreshToken = "refresh-token", expiresIn = 3600, tokenType = "bearer"))
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = true
                alwaysAutoRefresh = false
                enableLifecycleCallbacks = false
            }
        }
        // The plugin starts loading the stored session, the load the held store keeps waiting
        runCurrent()
        return newTestBucketApi(delegate = supabase.storage.from(BUCKET), baseDirectory = dataDirectory)
    }

    @Test
    fun a_download_is_sent_at_once_on_the_anon_key_and_cached() = runTest {
        // Given — a session still restoring
        val bucket = restoringBucket()

        // When
        val bytes = bucket.downloadAuthenticated(PATH)

        // Then — sent on the anon key, and the copy is served from the cache from now on
        assertEquals("bytes", bytes.decodeToString())
        assertEquals(listOf<String?>("Bearer $TEST_SUPABASE_KEY"), tokens)
        assertEquals(true, bucket.cachedFile(PATH) != null)
    }

    @Test
    fun an_upload_is_sent_at_once_on_the_anon_key() = runTest {
        // Given — a session still restoring
        val bucket = restoringBucket()

        // When
        bucket.upload(PATH, "new".encodeToByteArray())

        // Then
        assertEquals(listOf<String?>("Bearer $TEST_SUPABASE_KEY"), tokens)
    }

    private companion object {
        const val BUCKET = "photos"
        const val PATH = "a.txt"
    }
}
