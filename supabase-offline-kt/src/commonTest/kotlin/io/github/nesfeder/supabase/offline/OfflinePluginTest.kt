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
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeResumableCache
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.assertClientClosedError
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.openInMemory
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The library as a supabase-kt plugin: `install(Offline) { … }` and `supabase.offline`, with its
 * client opened over an in-memory store so no database file is involved. The platform tests open
 * the real database the same way.
 */
class OfflinePluginTest {

    @Serializable private data class AddArguments(val a: Int, val b: Int)

    /** A client on [server] with PostgREST and the plugin, its data in [store]. */
    private fun pluginClient(
        server: RecordingPostgrestServer,
        store: OfflineStore = newTestOfflineStore(),
        configure: Offline.Config.() -> Unit = {},
    ): SupabaseClient =
        newTestSupabaseClient(engine = server.engine) {
            install(Postgrest) { maxRetries = 0 }
            install(Offline) {
                openInMemory(store)
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
        val store = newTestOfflineStore()
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
        @Suppress("DEPRECATION") val byOldName = supabase.offline.storage("avatars")

        // Then
        assertEquals(
            listOf("avatars", "avatars", "avatars"),
            listOf(byFrom, byIndex, byOldName).map { it.bucketId },
        )
        supabase.close()
    }

    @Test
    fun the_old_names_reach_the_new_members() = runTest {
        // Given — a write queued offline
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val store = newTestOfflineStore()
        val supabase = pluginClient(server, store) { openInMemory(store, idleCoroutineScope()) }
        supabase.offline.from("todos").insert(buildJsonArray { add(buildJsonObject {}) })

        // When
        @Suppress("DEPRECATION") val wrapped = supabase.offline.supabase
        @Suppress("DEPRECATION") supabase.offline.clearAllCache()

        // Then — the old property is the client, and the old clear deleted the queued write
        assertSame(supabase, wrapped)
        assertEquals(0, store.pendingMutationCount())
        assertTrue(supabase.offline.config.primaryKeys.isEmpty())
    }
}
