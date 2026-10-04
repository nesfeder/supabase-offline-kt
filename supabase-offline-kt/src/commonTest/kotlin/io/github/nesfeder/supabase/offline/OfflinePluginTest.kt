// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.nesfeder.supabase.offline.internal.storage.markFileUsed
import io.github.nesfeder.supabase.offline.testdoubles.FakeResumableCache
import io.github.nesfeder.supabase.offline.testdoubles.RecordingBackendServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.assertClientClosedError
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.openInMemory
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * The library as a supabase-kt plugin: `install(Offline) { … }` and `supabase.offline`, with its
 * client opened over an in-memory store so no database file is involved. The platform tests open
 * the real database the same way.
 */
class OfflinePluginTest {

    @Serializable private data class AddArguments(val a: Int, val b: Int)

    /** A client on [server] with PostgREST and the plugin, its queue in [store]'s table. */
    private fun pluginClient(
        server: RecordingPostgrestServer,
        store: TestStore = newTestStore(),
        configure: Offline.Config.() -> Unit = {},
    ): SupabaseClient =
        newTestSupabaseClient(engine = server.engine) {
            install(Postgrest) { maxRetries = 0 }
            install(Offline) {
                openInMemory(mutationDao = store.mutationDao)
                configure()
            }
        }

    @Test
    fun the_plugin_is_reached_as_offline_and_serves_its_cache_offline() = runTest {
        // Given — a client with the plugin installed, online
        val server = RecordingPostgrestServer(answer = { okJson("""[{"id":1}]""") })
        val supabase = pluginClient(server)

        // When — a read online, then the same read once the server cannot be reached
        val online = supabase.offline.from("todos").select()
        server.answer = { connectivityFailure() }
        val offline = supabase.offline.from("todos").select()

        // Then — the second read is the first one's rows, from the cache
        assertEquals("""[{"id":1}]""", online.data)
        assertEquals(online.data, offline.data)
        supabase.close()
    }

    @Test
    fun the_plugin_is_installed_under_its_key_with_the_configuration_install_wrote() = runTest {
        // Given
        val supabase = pluginClient(RecordingPostgrestServer()) { cacheSizeBytes = 8_192L }

        // When
        val plugin = supabase.pluginManager.getPluginOrNull(Offline)

        // Then
        assertEquals("offline", Offline.key)
        assertSame(supabase.offline, plugin)
        assertSame(supabase, supabase.offline.supabaseClient)
        assertEquals(8_192L, supabase.offline.config.cacheSizeBytes)
        supabase.close()
    }

    @Test
    fun offline_on_a_client_without_the_plugin_fails() = runTest {
        // Given
        val supabase = newTestSupabaseClient()

        // When / Then
        assertFailsWith<IllegalStateException> { supabase.offline }
    }

    @Test
    fun the_plugin_finds_auth_installed_after_it() = runTest {
        // Given — Offline installed before Auth, signed out, offline
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val supabase =
            newTestSupabaseClient(engine = server.engine) {
                install(Postgrest) { maxRetries = 0 }
                install(Offline) { openInMemory(coordinatorScope = idleCoroutineScope()) }
                install(Auth) {
                    sessionManager = MemorySessionManager()
                    codeVerifierCache = MemoryCodeVerifierCache()
                    autoLoadFromStorage = false
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                }
            }
        supabase.auth.sessionStatus.first { status -> status !is SessionStatus.Initializing }
        supabase.offline.from("todos").insert(buildJsonArray { add(buildJsonObject {}) })

        // When — a user signs in
        supabase.signInForTest("A")

        // Then — the queue is per identity, which only Auth can tell: the signed-out write is not
        // the user's
        assertEquals(0, supabase.offline.pendingMutationCount())
    }

    @Test
    fun closing_the_client_closes_the_plugin() = runTest {
        // Given
        val supabase = pluginClient(RecordingPostgrestServer())
        val offline = supabase.offline

        // When
        supabase.close()

        // Then — every call on the plugin is refused, and closing it again is harmless
        assertClientClosedError(runCatching { offline.from("todos") }.exceptionOrNull())
        offline.close()
    }

    @Test
    fun a_write_on_the_wire_when_the_client_closes_is_applied_and_not_queued() = runTest {
        // Given — a write the server holds on to
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val server =
            RecordingPostgrestServer(
                answer = {
                    arrived.complete(Unit)
                    release.await()
                    okJson("[]")
                }
            )
        val store = newTestStore()
        val supabase = pluginClient(server, store)
        val write = async {
            supabase.offline.from("todos").insert(buildJsonArray { add(buildJsonObject {}) })
        }
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { arrived.await() } }

        // When — the client closes, and once the plugin refuses new calls, the server answers
        val closing = async { supabase.close() }
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (runCatching { supabase.offline.from("todos") }.isSuccess) delay(1)
            }
        }
        release.complete(Unit)

        // Then — the write was applied, and nothing is left for the next client to send again
        assertIs<MutationOutcome.Applied>(write.await())
        closing.await()
        assertEquals(0, store.pendingMutationCount())
        assertEquals(1, server.requests.size)
    }

    @Test
    fun a_configured_primary_key_names_the_rows_a_queued_write_touches() = runTest {
        // Given — a table keyed by two columns, offline
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val supabase = pluginClient(server) { primaryKey("memberships", "team_id", "user_id") }

        // When
        val outcome =
            supabase.offline
                .from("memberships")
                .insert(
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("team_id", "t")
                                put("user_id", "u")
                                put("role", "owner")
                            }
                        )
                    }
                )

        // Then
        val queued = assertIs<MutationOutcome.Queued>(outcome).pendingMutation
        assertEquals(
            listOf(
                buildJsonObject {
                    put("team_id", "t")
                    put("user_id", "u")
                }
            ),
            queued.rowKeys,
        )
        supabase.close()
    }

    @Test
    fun a_primary_key_without_columns_is_refused_at_install() {
        // When / Then — by the helper, and set directly
        assertFailsWith<IllegalArgumentException> {
            pluginClient(RecordingPostgrestServer()) { primaryKey("memberships") }
        }
        assertFailsWith<IllegalArgumentException> {
            pluginClient(RecordingPostgrestServer()) { primaryKeys["memberships"] = emptyList() }
        }
    }

    @Test
    fun a_server_record_without_keys_is_matched_by_the_configured_key() = runTest {
        // Given — a cached list of a table keyed by two columns
        val server =
            RecordingPostgrestServer(
                answer = { okJson("""[{"team_id":"t","user_id":"u","role":"member"}]""") }
            )
        val supabase = pluginClient(server) { primaryKey("memberships", "team_id", "user_id") }
        supabase.offline.from("memberships").select()

        // When — the server reports the row changed, naming no key
        supabase.offline.applyServerRecord(
            "memberships",
            buildJsonObject {
                put("team_id", "t")
                put("user_id", "u")
                put("role", "owner")
            },
        )
        server.answer = { connectivityFailure() }

        // Then — the row is replaced, not added
        assertEquals(
            """[{"team_id":"t","user_id":"u","role":"owner"}]""",
            supabase.offline.from("memberships").select().data,
        )
        supabase.close()
    }

    @Test
    fun rpc_calls_the_function_on_the_server() = runTest {
        // Given
        val server = RecordingPostgrestServer(answer = { okJson("3") })
        val supabase = pluginClient(server)

        // When — with parameters as JSON, then as an object the client's serializer encodes
        val result = supabase.offline.rpc("add", buildJsonObject { put("a", 1) })
        supabase.offline.rpc("add", AddArguments(a = 1, b = 2))

        // Then
        assertEquals("3", result.data)
        val (json, typed) = server.requests
        assertEquals("POST", json.method)
        assertEquals("add", json.table)
        assertEquals("""{"a":1}""", json.body)
        assertEquals("""{"a":1,"b":2}""", typed.body)
        supabase.close()
    }

    @Test
    fun storage_buckets_are_reached_as_supabase_kt_reaches_them() = runTest {
        // Given — Storage installed beside the plugin
        val supabase =
            newTestSupabaseClient(engine = RecordingPostgrestServer().engine) {
                install(Postgrest)
                install(Storage) { resumable { cache = FakeResumableCache() } }
                install(Offline) { openInMemory() }
            }

        // When
        val byFrom = supabase.offline.storage.from("avatars")
        val byIndex = supabase.offline.storage["avatars"]

        // Then
        assertEquals(listOf("avatars", "avatars"), listOf(byFrom, byIndex).map { it.bucketId })
        supabase.close()
    }

    @Test
    fun the_response_trust_install_sets_decides_whether_an_empty_answer_replaces_the_cache() =
        runTest {
            // When — rows read, then an empty answer to the same read, then the network goes
            val underSettledSession = cachedAfterAnEmptyAnswer(ResponseTrust.SettledSession)
            val underAuthenticatedOnly =
                cachedAfterAnEmptyAnswer(ResponseTrust.AuthenticatedSessionOnly)

            // Then — without Auth, only the earlier rule refuses to let the empty answer in
            assertEquals("[]", underSettledSession)
            assertEquals("""[{"id":1}]""", underAuthenticatedOnly)
        }

    /** What the cache answers offline, after rows and then an empty answer, under [trust]. */
    private suspend fun cachedAfterAnEmptyAnswer(trust: ResponseTrust): String {
        var rows = """[{"id":1}]"""
        val server = RecordingPostgrestServer(answer = { okJson(rows) })
        val supabase = pluginClient(server) { responseTrust = trust }
        supabase.offline.from("todos").select()
        rows = "[]"
        supabase.offline.from("todos").select()
        server.answer = { connectivityFailure() }
        val cached = supabase.offline.from("todos").select().data
        supabase.close()
        return cached
    }

    @Test
    fun the_cache_size_install_sets_bounds_what_the_cache_keeps() = runTest {
        // When — one read larger than the smallest bound there is, under it and under the default
        val keptUnderDefault = servedOfflineAfterOneLargeRead(cacheSizeBytes = null)
        val keptUnderTheBound = servedOfflineAfterOneLargeRead(cacheSizeBytes = 4_096L)

        // Then — the bound evicted it
        assertTrue(keptUnderDefault)
        assertFalse(keptUnderTheBound)
    }

    /** Whether a read of more than 4 KiB is served from the cache once the network goes. */
    private suspend fun servedOfflineAfterOneLargeRead(cacheSizeBytes: Long?): Boolean {
        val server =
            RecordingPostgrestServer(
                answer = { okJson("""[{"id":1,"pad":"${"x".repeat(5_000)}"}]""") }
            )
        val supabase =
            newTestSupabaseClient(engine = server.engine) {
                install(Postgrest) { maxRetries = 0 }
                install(Offline) {
                    openInMemory()
                    if (cacheSizeBytes != null) this.cacheSizeBytes = cacheSizeBytes
                }
            }
        supabase.offline.from("todos").select()
        server.answer = { connectivityFailure() }
        val served = runCatching {
            supabase.offline.from("todos").select(readStrategy = ReadStrategy.CacheOnly())
        }
            .isSuccess
        supabase.close()
        return served
    }

    @Test
    fun the_file_cache_size_install_sets_bounds_the_downloaded_files() = runTest {
        // When — a file used long ago, then another, past a bound that holds one
        val keptUnderDefault = olderFileKeptAfterAnotherDownload(fileCacheSizeBytes = null)
        val keptUnderTheBound = olderFileKeptAfterAnotherDownload(fileCacheSizeBytes = 40L)

        // Then — the bound evicted the older file
        assertTrue(keptUnderDefault)
        assertFalse(keptUnderTheBound)
    }

    /**
     * Whether a file downloaded and last used long ago is still cached once a second one is
     * downloaded; each file is about 30 bytes.
     */
    private suspend fun olderFileKeptAfterAnotherDownload(fileCacheSizeBytes: Long?): Boolean {
        val directory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "offline-plugin-file-cache-${Random.nextLong().toULong()}"
        val server = RecordingBackendServer(marker = "file-cache")
        val supabase =
            newTestSupabaseClient(engine = server.engine) {
                install(Postgrest) { maxRetries = 0 }
                install(Storage) { resumable { cache = FakeResumableCache() } }
                install(Offline) {
                    openInMemory(dataDirectory = directory)
                    if (fileCacheSizeBytes != null) this.fileCacheSizeBytes = fileCacheSizeBytes
                }
            }
        try {
            val bucket = supabase.offline.storage.from("avatars")
            bucket.downloadAuthenticated("a.png")
            val older = checkNotNull(bucket.cachedFile("a.png")) { "the first file was not cached" }
            markFileUsed(older.path.toPath(), epochMillis = 0L)
            bucket.downloadAuthenticated("b.png")
            return bucket.cachedFile("a.png") != null
        } finally {
            supabase.close()
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun the_connectivity_signal_install_sets_wakes_a_waiting_refresh() = runTest {
        // Given — a live read whose first refresh failed, everything on this test's clock
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var online = false
        val server =
            RecordingPostgrestServer(
                answer = { if (online) okJson("""[{"id":1}]""") else connectivityFailure() },
                engineDispatcher = dispatcher,
            )
        val signal = MutableStateFlow(false)
        val supabase =
            newTestSupabaseClient(engine = server.engine) {
                install(Postgrest) { maxRetries = 0 }
                install(Offline) {
                    openInMemory(coordinatorScope = scope)
                    networkAvailability = signal
                }
            }
        val shown = mutableListOf<String?>()
        scope.launch {
            supabase.offline.from("todos").selectAsFlow().collect { read ->
                shown += read.result?.data
            }
        }
        runCurrent()
        val readsBefore = server.requests.size

        // When — the device is back online, well within the refresh's backoff
        online = true
        signal.value = true
        runCurrent()

        // Then — the read was refreshed at once
        assertEquals(readsBefore + 1, server.requests.size)
        assertEquals("""[{"id":1}]""", shown.last())
        scope.cancel()
        supabase.close()
    }
}
