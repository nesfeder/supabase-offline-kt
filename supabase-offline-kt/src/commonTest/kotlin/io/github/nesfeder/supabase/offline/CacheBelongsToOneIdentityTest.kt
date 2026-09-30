// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.SupabaseClientBuilder
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.nesfeder.supabase.offline.internal.db.MUTATION_OWNERS_SIGNATURE
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.postgrest.ResponseTrustPolicy
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryStorageDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeHeldSessionManager
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeResumableCache
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * The query cache and the Storage file cache belong to one identity at a time: a signed-in user, or
 * nobody signed in. Once the session settles into another identity, what was cached for the one
 * before is dropped; it is never served to the next identity, nor written into its cache. The
 * mutation queue is kept whole: each identity's writes wait for it.
 */
class CacheBelongsToOneIdentityTest {

    private val objectDao = FakeObjectDao()
    private val mutationDao = FakeMutationDao()
    private val storageDao = InMemoryStorageDao()
    private val dataDirectory =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-identity-${Random.nextLong().toULong()}"

    /** Whether the server can be reached. */
    private var online = true

    /** Held by the next read the server answers, until completed. */
    private var heldRead: CompletableDeferred<Unit>? = null

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    /**
     * A server behind RLS: each user's token reads that user's rows and object bytes, the anon key
     * reads nothing.
     */
    private fun TestScope.newServer(): RecordingPostgrestServer =
        RecordingPostgrestServer(
            answer = { request ->
                if (!online) connectivityFailure()
                heldRead?.let { held ->
                    heldRead = null
                    held.await()
                }
                val token = request.authorization.orEmpty()
                val user = USERS.firstOrNull { user -> token.contains(testJwt(user)) }
                when {
                    request.table == OBJECT_PATH -> okJson("bytes of ${user ?: "nobody"}")
                    request.method != "GET" -> okJson("")
                    user == null -> okJson("[]")
                    else -> okJson(rowsOf(user))
                }
            },
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )

    /**
     * A client with the Auth plugin over this test's database, signed in as [signedInAs] before the
     * offline client starts, or signed out.
     */
    private suspend fun TestScope.launchClient(
        server: RecordingPostgrestServer,
        signedInAs: String? = null,
        sessions: SessionManager = MemorySessionManager(),
        restoresStoredSession: Boolean = false,
        responseTrust: ResponseTrustPolicy = ResponseTrustPolicy.SETTLED_SESSION,
        scrubDatabase: suspend () -> Unit = {},
        plugins: SupabaseClientBuilder.() -> Unit = {
            install(Auth) {
                sessionManager = sessions
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = restoresStoredSession
                alwaysAutoRefresh = false
                enableLifecycleCallbacks = false
            }
        },
    ): SupabaseOfflineClientImpl {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val supabase: SupabaseClient =
            newTestSupabaseClient(engine = server.engine) {
                coroutineDispatcher = dispatcher
                install(Postgrest) { maxRetries = 0 }
                install(Storage) { resumable { cache = FakeResumableCache() } }
                plugins()
            }
        runCurrent()
        if (signedInAs != null) {
            supabase.signInForTest(signedInAs)
            runCurrent()
        }
        return newTestOfflineClient(
                supabase = supabase,
                store =
                    newTestOfflineStore(
                        objectDao = objectDao,
                        storageDao = storageDao,
                        mutationDao = mutationDao,
                    ),
                baseDirectoryProvider = { dataDirectory },
                coordinatorScope = backgroundScope,
                scrubDatabase = scrubDatabase,
                responseTrust = responseTrust,
            )
            .also { runCurrent() }
    }

    /**
     * Runs the test's scheduler until the cache is recorded as [owner]'s, for at most five seconds
     * of real time: dropping it runs partly on real threads, the file cache's.
     */
    private suspend fun TestScope.awaitCacheOwner(owner: String) {
        awaitUntil { recordedOwner() == ownerRecordOf(owner) }
    }

    /** Runs the test's scheduler until [condition] holds, for at most five seconds of real time. */
    private suspend fun TestScope.awaitUntil(condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
        while (!condition() && deadline.hasNotPassedNow()) {
            runCurrent()
            withContext(Dispatchers.Default) { delay(10) }
        }
        runCurrent()
    }

    /** The record of whose the cache is, as the engine wrote it. */
    private fun recordedOwner(): String? =
        objectDao.getRow(RESERVED_TABLE_NAME, CACHE_OWNER_SIGNATURE)?.payload

    /** The record of the cache belonging to [owner]. */
    private fun ownerRecordOf(owner: String): String = """{"version":1,"owner":"$owner"}"""

    /** What [read] returned, or the name of what it threw. */
    private suspend fun outcomeOf(read: suspend () -> String): String =
        try {
            read()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            "failed: ${failure::class.simpleName}"
        }

    @Test
    fun a_signed_out_answer_is_never_served_to_the_user_who_signs_in_next() = runTest {
        // Given — signed out, with the anon key's empty answer cached
        val server = newServer()
        val client = launchClient(server)
        val items = client.from(TABLE)
        assertEquals("[]", items.select(readStrategy = ReadStrategy.CacheFirst()).data)

        // When — B signs in
        client.supabase.signInForTest("B")

        // Then — at once, and once the session has settled, B never gets the anonymous answer
        assertFailsWith<NoSuchElementException> {
            items.select(readStrategy = ReadStrategy.CacheOnly())
        }
        awaitCacheOwner("user:B")
        assertEquals(rowsOf("B"), items.select(readStrategy = ReadStrategy.CacheFirst()).data)
        assertEquals(rowsOf("B"), items.select(readStrategy = ReadStrategy.CacheOnly()).data)
        online = false
        assertEquals(rowsOf("B"), items.select(readStrategy = ReadStrategy.NetworkFirst()).data)
        online = true
        assertEquals(rowsOf("B"), items.selectAsFlow().first().result?.data)
    }

    @Test
    fun a_session_that_ends_on_its_own_never_shows_or_overwrites_the_users_rows() = runTest {
        // Given — A's rows cached
        val server = newServer()
        val client = launchClient(server, signedInAs = "A")
        val items = client.from(TABLE)
        items.select(readStrategy = ReadStrategy.NetworkOnly)

        // When — the session ends without the app clearing local data (a revoked refresh token),
        // and a reactive read refreshes while signed out
        client.supabase.auth.clearSession()
        runCurrent()
        val shown = mutableListOf<String?>()
        val job = backgroundScope.launch {
            items.selectAsFlow().collect { emission -> shown += emission.result?.data }
        }
        advanceTimeBy(2.minutes)
        runCurrent()
        job.cancel()

        // Then — signed out never saw A's rows
        assertFalse(rowsOf("A") in shown, "$shown")

        // When — A signs back in, offline
        client.supabase.signInForTest("A")
        online = false

        // Then — A is never served the anonymous answer
        assertNotEquals(
            "[]",
            outcomeOf { items.select(readStrategy = ReadStrategy.CacheFirst()).data },
        )
        assertNotEquals(
            "[]",
            outcomeOf { items.select(readStrategy = ReadStrategy.NetworkFirst()).data },
        )
    }

    @Test
    fun a_restore_that_settles_signed_out_never_shows_what_was_cached_before() = runTest {
        // Given — rows cached by an earlier build, and no stored session to restore
        objectDao.seedPayload(TABLE, TABLE, rowsOf("A"))
        val sessions = FakeHeldSessionManager(stored = null)
        val server = newServer()
        val client = launchClient(server, sessions = sessions, restoresStoredSession = true)
        val shown = mutableListOf<String?>()
        val job = backgroundScope.launch {
            client.from(TABLE).selectAsFlow().collect { emission -> shown += emission.result?.data }
        }
        advanceTimeBy(1.seconds)
        runCurrent()

        // When — the restore settles signed out
        sessions.release()
        awaitCacheOwner("anonymous")
        advanceTimeBy(2.minutes)
        runCurrent()
        job.cancel()

        // Then — the rows cached before were never shown, and the anonymous answer is kept for
        // nobody signed in only
        assertFalse(rowsOf("A") in shown, "$shown")
        assertEquals("[]", shown.last())
        client.supabase.signInForTest("A")
        online = false
        assertNotEquals(
            "[]",
            outcomeOf { client.from(TABLE).select(readStrategy = ReadStrategy.CacheFirst()).data },
        )
    }

    @Test
    fun a_read_waiting_on_a_restore_that_settles_signed_out_gets_the_anonymous_answer() = runTest {
        listOf(ReadStrategy.NetworkOnly, ReadStrategy.NetworkFirst(), ReadStrategy.CacheFirst())
            .forEach { strategy ->
                // Given — A's rows cached as A's, and a restore that will find no session
                objectDao.deleteAll()
                objectDao.seedPayload(TABLE, TABLE, rowsOf("A"))
                objectDao.seedPayload(
                    RESERVED_TABLE_NAME,
                    CACHE_OWNER_SIGNATURE,
                    ownerRecordOf("user:A"),
                )
                val sessions = FakeHeldSessionManager(stored = null)
                val client =
                    launchClient(newServer(), sessions = sessions, restoresStoredSession = true)
                val read = async { client.from(TABLE).select(readStrategy = strategy).data }
                advanceTimeBy(1.seconds)
                runCurrent()

                // When — the restore settles signed out
                sessions.release()
                awaitCacheOwner("anonymous")
                advanceTimeBy(1.minutes)
                runCurrent()

                // Then — the anonymous answer; A's rows are dropped, and what is kept, if the
                // answer landed after the drop, is that answer, for nobody signed in
                assertEquals("[]", read.await(), "$strategy")
                val stored = objectDao.rowsOf(TABLE).map { row -> row.payload }
                assertTrue(stored == emptyList<String>() || stored == listOf("[]"), "$stored")
                assertEquals(ownerRecordOf("anonymous"), recordedOwner(), "$strategy")
                client.close()
            }
    }

    @Test
    fun signing_in_over_another_user_drops_the_cache_and_keeps_every_queued_write() = runTest {
        // Given — A's rows cached, and a write of A's queued offline
        val server = newServer()
        val client = launchClient(server, signedInAs = "A")
        val items = client.from(TABLE)
        items.select(readStrategy = ReadStrategy.NetworkOnly)
        online = false
        items.insertJson(Json.parseToJsonElement("""[{"id":"a2","owner":"A"}]""") as JsonArray)
        runCurrent()
        online = true
        assertEquals(1, client.pendingMutationCount())

        // When — B signs in, with no sign-out between
        client.supabase.signInForTest("B")

        // Then — B is never served A's rows; once settled, A's rows are gone and A's write is not
        assertFailsWith<NoSuchElementException> {
            items.select(readStrategy = ReadStrategy.CacheOnly())
        }
        awaitCacheOwner("user:B")
        assertEquals(emptyList(), objectDao.rowsOf(TABLE).map { row -> row.payload })
        assertEquals(1, mutationDao.inserted.count { row -> row.syncedAtMs == null })
        assertNotNull(objectDao.getRow(RESERVED_TABLE_NAME, MUTATION_OWNERS_SIGNATURE))
        assertEquals(rowsOf("B"), items.select(readStrategy = ReadStrategy.CacheFirst()).data)

        // When — A signs back in, offline
        online = false
        client.supabase.signInForTest("A")

        // Then — A's write still waits for A, and A is not served B's rows
        assertFailsWith<NoSuchElementException> {
            items.select(readStrategy = ReadStrategy.CacheOnly())
        }
        awaitCacheOwner("user:A")
        assertEquals(1, client.pendingMutationCount())
    }

    @Test
    fun an_upgraded_install_signed_in_adopts_what_is_cached() = runTest {
        // Given — rows cached by an earlier build, which recorded no owner
        objectDao.seedPayload(TABLE, TABLE, rowsOf("A"))

        // When — the app starts signed in
        val client = launchClient(newServer(), signedInAs = "A")

        // Then — the cache is the user's, and nothing was dropped
        assertEquals(
            rowsOf("A"),
            client.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly()).data,
        )
        awaitCacheOwner("user:A")
        assertEquals(listOf(rowsOf("A")), objectDao.rowsOf(TABLE).map { row -> row.payload })
        assertEquals(ownerRecordOf("user:A"), recordedOwner())
    }

    @Test
    fun a_new_install_signed_out_drops_nothing_and_rewrites_nothing() = runTest {
        // Given — nothing cached, and nobody signed in
        var scrubs = 0

        // When — the client starts and a list is read
        val client = launchClient(newServer(), scrubDatabase = { scrubs++ })
        assertEquals("[]", client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly).data)
        awaitCacheOwner("anonymous")

        // Then — the empty cache is nobody's without a drop, and the answer is kept
        assertEquals(0, scrubs)
        assertEquals(listOf("[]"), objectDao.rowsOf(TABLE).map { row -> row.payload })
    }

    @Test
    fun an_upgraded_install_signed_out_drops_what_is_cached() = runTest {
        // Given — rows cached by an earlier build, which recorded no owner
        objectDao.seedPayload(TABLE, TABLE, rowsOf("A"))

        // When — the app starts signed out
        val client = launchClient(newServer())

        // Then — nobody signed in cannot claim a cache an earlier user may have left: it is dropped
        assertFailsWith<NoSuchElementException> {
            client.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly())
        }
        awaitCacheOwner("anonymous")
        assertEquals(emptyList(), objectDao.rowsOf(TABLE).map { row -> row.payload })
    }

    @Test
    fun without_the_auth_plugin_the_cache_is_one_anonymous_identitys_for_good() = runTest {
        listOf<Pair<String, SupabaseClientBuilder.() -> Unit>>(
                "no Auth plugin" to {},
                "the app's own token provider" to { accessToken = { testJwt("third-party") } },
            )
            .forEach { (label, plugins) ->
                // Given — rows cached by an earlier build, which recorded no owner
                objectDao.deleteAll()
                objectDao.seedPayload(TABLE, TABLE, rowsOf("A"))

                // When
                val client = launchClient(newServer(), plugins = plugins)

                // Then — served as it is, recorded as nobody's by the first write, never dropped
                val items = client.from(TABLE)
                assertEquals(
                    rowsOf("A"),
                    items.select(readStrategy = ReadStrategy.CacheOnly()).data,
                    label,
                )
                client.from(OTHER_TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
                awaitCacheOwner("anonymous")
                advanceTimeBy(1.minutes)
                runCurrent()
                assertEquals(
                    rowsOf("A"),
                    items.select(readStrategy = ReadStrategy.CacheOnly()).data,
                    label,
                )
                assertEquals(ownerRecordOf("anonymous"), recordedOwner(), label)
                client.close()
            }
    }

    @Test
    fun a_restart_between_the_change_of_user_and_the_drop_never_serves_the_last_users_rows() =
        runTest {
            // Given — A's rows cached; then B signed in and the process ended before the drop
            val server = newServer()
            val first = launchClient(server, signedInAs = "A")
            first.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
            awaitCacheOwner("user:A")
            first.close()
            val sessions = FakeHeldSessionManager(storedSessionOf("B"))

            // When — the next launch restores B's session
            val client = launchClient(server, sessions = sessions, restoresStoredSession = true)
            val items = client.from(TABLE)
            val shown = mutableListOf<String?>()
            val job = backgroundScope.launch {
                items.selectAsFlow().collect { emission -> shown += emission.result?.data }
            }
            advanceTimeBy(1.seconds)
            runCurrent()

            // Then — while it restores, A's rows are not served
            assertFailsWith<NoSuchElementException> {
                items.select(readStrategy = ReadStrategy.CacheOnly())
            }

            // When — it settles as B
            sessions.release()
            awaitCacheOwner("user:B")
            advanceTimeBy(1.minutes)
            runCurrent()
            job.cancel()

            // Then — never A's rows, and B's in the end
            assertFalse(rowsOf("A") in shown, "$shown")
            assertEquals(rowsOf("B"), shown.last())
            assertEquals(listOf(rowsOf("B")), objectDao.rowsOf(TABLE).map { row -> row.payload })
        }

    @Test
    fun a_reactive_read_live_across_a_change_of_user_ends_on_the_new_users_rows_alone() = runTest {
        // Given — A signed in, with a reactive read showing A's rows
        val server = newServer()
        val client = launchClient(server, signedInAs = "A")
        val shown = mutableListOf<String?>()
        val job = backgroundScope.launch {
            client.from(TABLE).selectAsFlow().collect { emission -> shown += emission.result?.data }
        }
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(rowsOf("A"), shown.last())
        val shownBefore = shown.size

        // When — B signs in over A; the drop and the fetch after it finish partly on real threads
        client.supabase.signInForTest("B")
        awaitCacheOwner("user:B")
        advanceTimeBy(1.minutes)
        awaitUntil { shown.lastOrNull() == rowsOf("B") }
        job.cancel()

        // Then — nothing of A's after the change, never a mix, and B's rows in the end
        val after = shown.drop(shownBefore)
        assertTrue(after.none { payload -> payload != null && "\"A\"" in payload }, "$after")
        assertEquals(rowsOf("B"), after.lastOrNull())
    }

    @Test
    fun a_change_of_user_drops_the_file_cache_and_its_rows() = runTest {
        // Given — A's copy of an object cached
        val server = newServer()
        val client = launchClient(server, signedInAs = "A")
        val bucket = client.storage(BUCKET)
        val cached = bucket.ensureCachedFile(OBJECT_PATH)
        assertEquals("bytes of A", FileSystem.SYSTEM.read(cached.path.toPath()) { readUtf8() })

        // When — B signs in
        client.supabase.signInForTest("B")

        // Then — B never gets A's copy; once settled, the file and its row are gone
        assertNull(bucket.cachedFile(OBJECT_PATH))
        awaitCacheOwner("user:B")
        awaitUntil { !FileSystem.SYSTEM.exists(cached.path.toPath()) }
        assertFalse(FileSystem.SYSTEM.exists(cached.path.toPath()))
        assertEquals(emptyList(), storageDao.all().map { row -> row.path })
        val fetched = bucket.ensureCachedFile(OBJECT_PATH)
        assertEquals("bytes of B", FileSystem.SYSTEM.read(fetched.path.toPath()) { readUtf8() })
    }

    @Test
    fun under_the_old_rule_a_read_in_flight_across_a_change_of_user_is_not_kept_for_the_next() =
        runTest {
            // Given — A signed in, under the old trust rule, and A's read held on the wire
            val server = newServer()
            val client =
                launchClient(
                    server,
                    signedInAs = "A",
                    responseTrust = ResponseTrustPolicy.AUTHENTICATED_SESSION_ONLY,
                )
            val items = client.from(TABLE)
            val held = CompletableDeferred<Unit>()
            heldRead = held
            val read = async { items.select(readStrategy = ReadStrategy.NetworkOnly).data }
            runCurrent()

            // When — B signs in, then A's answer arrives
            client.supabase.signInForTest("B")
            runCurrent()
            held.complete(Unit)
            read.await()
            awaitCacheOwner("user:B")

            // Then — A's rows were not kept for B
            assertNotEquals(
                rowsOf("A"),
                outcomeOf { items.select(readStrategy = ReadStrategy.CacheOnly()).data },
            )
        }

    private companion object {
        const val TABLE = "notes"
        const val OTHER_TABLE = "tags"
        const val BUCKET = "avatars"
        const val OBJECT_PATH = "me.png"

        /** Where the engine records whose the cache is, as pinned in `PersistedNamesTest`. */
        const val CACHE_OWNER_SIGNATURE = "cache_owner"

        val USERS = listOf("A", "B")

        /** The one row [user] can read. */
        fun rowsOf(user: String): String = """[{"id":"${user.lowercase()}1","owner":"$user"}]"""

        /** A stored session of [user], as a session store would hand it back. */
        fun storedSessionOf(user: String): UserSession =
            UserSession(
                accessToken = testJwt(user),
                refreshToken = "refresh-token",
                expiresIn = 3600,
                tokenType = "bearer",
            )
    }
}
