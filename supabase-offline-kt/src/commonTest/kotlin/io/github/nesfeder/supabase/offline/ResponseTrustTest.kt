package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeHeldSessionManager
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * When a response may replace what the cache holds. A client with no Auth plugin, or with its own
 * access-token provider, trusts what the server says, an empty result included; so does a signed-out
 * session, for the anonymous identity. Only a session still restoring, or whose refresh failed, is
 * gated: its requests go out on the anon key, so nothing they return is written, and the reactive read
 * keeps trying until the session settles.
 */
class ResponseTrustTest {

    private val objectDao = FakeObjectDao()

    /** What every read of `todos` returns, as the scripted server's answer. */
    private var rows = "[]"

    /** One observed `selectAsFlow` of `todos`: what it showed, and its last refresh state. */
    private class Observed {
        val shown = mutableListOf<String?>()
        var refresh: RefreshState? = null
    }

    /**
     * Runs [block] with a client over [supabase] whose work, the reader and the server all run on this
     * test's scheduler, then stops them.
     */
    private suspend fun TestScope.observing(
        supabase: (TestDispatcher) -> Pair<SupabaseClient, RecordingPostgrestServer>,
        block: suspend TestScope.(server: RecordingPostgrestServer, observed: Observed) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val (client, server) = supabase(dispatcher)
        val offline = newTestOfflineClient(supabase = client, store = newTestOfflineStore(objectDao = objectDao), coordinatorScope = scope)
        val observed = Observed()
        scope.launch {
            offline.from(TABLE).selectAsFlow().collect { read ->
                observed.shown += read.result?.data
                observed.refresh = read.refresh
            }
        }
        try {
            block(server, observed)
        } finally {
            scope.cancel()
        }
    }

    private fun serverOn(dispatcher: TestDispatcher) = RecordingPostgrestServer(answer = { okJson(rows) }, engineDispatcher = dispatcher)

    private fun withoutAuth(dispatcher: TestDispatcher): Pair<SupabaseClient, RecordingPostgrestServer> {
        val server = serverOn(dispatcher)
        return newTestSupabaseClient(engine = server.engine) { install(Postgrest) { maxRetries = 0 } } to server
    }

    private fun withTokenProvider(dispatcher: TestDispatcher): Pair<SupabaseClient, RecordingPostgrestServer> {
        val server = serverOn(dispatcher)
        return newTestSupabaseClient(engine = server.engine) {
            accessToken = { testJwt("third-party-user") }
            install(Postgrest) { maxRetries = 0 }
        } to server
    }

    private fun withAuth(dispatcher: TestDispatcher, sessions: SessionManager, autoLoad: Boolean): Pair<SupabaseClient, RecordingPostgrestServer> {
        val server = serverOn(dispatcher)
        return newTestSupabaseClient(engine = server.engine) {
            coroutineDispatcher = dispatcher
            install(Postgrest) { maxRetries = 0 }
            install(Auth) {
                sessionManager = sessions
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = autoLoad
                alwaysAutoRefresh = false
                enableLifecycleCallbacks = false
            }
        } to server
    }

    private fun RecordingPostgrestServer.reads(): Int = requests.count { request -> request.method == "GET" }

    /** Lets the client's work run for longer than the refresh's longest retry wait, then stops the clock. */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_FOR)
        runCurrent()
    }

    @Test
    fun without_auth_an_empty_table_settles_to_an_empty_list_with_one_request() = runTest {
        observing(::withoutAuth) { server, observed ->
            // When
            settle()

            // Then
            assertEquals("[]", observed.shown.last())
            assertEquals(RefreshState.Idle, observed.refresh)
            assertEquals(1, server.reads())
        }
    }

    @Test
    fun without_auth_a_row_deleted_elsewhere_leaves_the_cached_list() = runTest {
        // Given — the cache holds a row the server no longer has
        objectDao.seedPayload(TABLE, TABLE, """[{"id":1}]""")

        observing(::withoutAuth) { server, observed ->
            // When
            settle()

            // Then
            assertEquals(listOf<String?>("""[{"id":1}]""", "[]"), observed.shown)
            assertEquals(RefreshState.Idle, observed.refresh)
            assertEquals(1, server.reads())
        }
    }

    @Test
    fun with_its_own_token_provider_an_empty_table_settles_to_an_empty_list_with_one_request() = runTest {
        observing(::withTokenProvider) { server, observed ->
            // When
            settle()

            // Then
            assertEquals("[]", observed.shown.last())
            assertEquals(RefreshState.Idle, observed.refresh)
            assertEquals(1, server.reads())
        }
    }

    @Test
    fun with_its_own_token_provider_a_row_deleted_elsewhere_leaves_the_cached_list() = runTest {
        objectDao.seedPayload(TABLE, TABLE, """[{"id":1}]""")

        observing(::withTokenProvider) { server, observed ->
            settle()

            assertEquals(listOf<String?>("""[{"id":1}]""", "[]"), observed.shown)
            assertEquals(RefreshState.Idle, observed.refresh)
            assertEquals(1, server.reads())
        }
    }

    @Test
    fun signed_out_an_empty_table_is_trusted_for_the_anonymous_identity() = runTest {
        // Given — Auth installed, nobody signed in
        observing({ dispatcher -> withAuth(dispatcher, MemorySessionManager(), autoLoad = false) }) { server, observed ->
            // When
            settle()

            // Then
            assertEquals("[]", observed.shown.last())
            assertEquals(RefreshState.Idle, observed.refresh)
            assertEquals(1, server.reads())
        }
    }

    @Test
    fun a_session_still_restoring_is_gated_then_the_read_settles_when_it_does() = runTest {
        // Given — the stored session is still loading when the read starts
        val sessions = FakeHeldSessionManager(signedInSession())
        observing({ dispatcher -> withAuth(dispatcher, sessions, autoLoad = true) }) { server, observed ->
            advanceTimeBy(RESTORING_FOR)
            runCurrent()

            // Then — nothing the anon key returned was taken as the user's data
            assertEquals(listOf<String?>(null), observed.shown.distinct())
            assertEquals(true, observed.refresh !is RefreshState.Idle, "the read settled while the session was restoring")
            assertEquals(true, server.reads() >= 1)

            // When — the session finishes restoring as a signed-in user
            sessions.release()
            settle()

            // Then — the read settles on what the server says for that user
            assertEquals("[]", observed.shown.last())
            assertEquals(RefreshState.Idle, observed.refresh)
        }
    }

    @Test
    fun a_narrowed_response_while_restoring_does_not_overwrite_the_users_cache() = runTest {
        // Given — the signed-in user's list, cached; the anon key sees only the public row
        objectDao.seedPayload(TABLE, TABLE, """[{"id":"public"},{"id":"my-draft"}]""")
        rows = """[{"id":"public"}]"""
        val sessions = FakeHeldSessionManager(signedInSession())
        observing({ dispatcher -> withAuth(dispatcher, sessions, autoLoad = true) }) { _, observed ->
            // When — the refresh goes out while the session restores
            advanceTimeBy(RESTORING_FOR)
            runCurrent()

            // Then — the cache still holds the user's rows, and the refresh has not settled
            assertEquals("""[{"id":"public"},{"id":"my-draft"}]""", objectDao.getRow(TABLE, TABLE)?.payload)
            assertEquals(listOf<String?>("""[{"id":"public"},{"id":"my-draft"}]"""), observed.shown.distinct())
            assertEquals(true, observed.refresh !is RefreshState.Idle, "the narrowed response ended the refresh")

            // When — the session restores, and the user's request sees both rows
            rows = """[{"id":"public"},{"id":"my-draft"}]"""
            sessions.release()
            settle()

            // Then
            assertEquals(RefreshState.Idle, observed.refresh)
            assertEquals("""[{"id":"public"},{"id":"my-draft"}]""", objectDao.getRow(TABLE, TABLE)?.payload)
        }
    }

    @Test
    fun a_one_shot_read_without_auth_caches_an_empty_result() = runTest {
        // Given
        val server = RecordingPostgrestServer(answer = { okJson("[]") })
        val client = newTestOfflineClient(supabase = server.supabaseClient(), store = newTestOfflineStore(objectDao = objectDao))

        // When
        val network = client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)

        // Then — the empty result is the table's answer, and a later offline read serves it
        assertEquals("[]", network.data)
        assertEquals("[]", client.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly()).data)
    }

    private fun signedInSession() = UserSession(accessToken = testJwt("u-1"), refreshToken = "refresh-token", expiresIn = 3600, tokenType = "bearer")

    private companion object {
        const val TABLE = "todos"

        /** How long the tests keep a session restoring: past the refresh's fast retries. */
        val RESTORING_FOR = 10.seconds

        /** Past the refresh's longest wait between retries, so a read that can settle has. */
        val SETTLE_FOR = 2.minutes
    }
}
