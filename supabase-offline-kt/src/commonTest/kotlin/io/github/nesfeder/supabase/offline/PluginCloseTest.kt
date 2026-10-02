// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.plugins.SupabasePlugin
import io.github.jan.supabase.plugins.SupabasePluginProvider
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.assertClientClosedError
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.openInMemory
import io.ktor.http.HttpStatusCode
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `SupabaseClient.close()` closes supabase-kt's HTTP client before it closes the plugin. A call
 * made in between, or one already running whose request had not gone out, ends as it would offline,
 * or with the closed error: never with a cancellation its caller did not cause.
 */
@OptIn(ExperimentalAtomicApi::class)
class PluginCloseTest {

    /**
     * A plugin whose `close()` waits for [Config.release]. Installed before Offline, it holds
     * supabase-kt's close between closing its HTTP client and closing the Offline plugin.
     */
    private class SlowClosingPlugin(
        override val supabaseClient: SupabaseClient,
        override val config: Config,
    ) : SupabasePlugin<SlowClosingPlugin.Config> {

        /** When the plugin's close starts, and what lets it finish. */
        class Config {
            val closing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
        }

        override suspend fun close() {
            config.closing.complete(Unit)
            config.release.await()
        }

        companion object : SupabasePluginProvider<Config, SlowClosingPlugin> {
            override val key: String = "slow-closing-test-plugin"

            override fun createConfig(init: Config.() -> Unit): Config = Config().apply(init)

            override fun create(supabaseClient: SupabaseClient, config: Config): SlowClosingPlugin =
                SlowClosingPlugin(supabaseClient, config)
        }
    }

    private fun oneRow() = buildJsonArray { add(buildJsonObject { put("id", 7) }) }

    @Test
    fun calls_made_while_supabase_close_runs_end_as_offline_or_closed_never_cancelled() = runTest {
        // Given — a warm cache, and a plugin installed before Offline whose close waits
        val server = RecordingPostgrestServer(answer = { okJson("""[{"id":1}]""") })
        val store = newTestOfflineStore()
        val supabase =
            newTestSupabaseClient(engine = server.engine) {
                install(Postgrest) { maxRetries = 0 }
                install(SlowClosingPlugin)
                install(Offline) { openInMemory(store, idleCoroutineScope()) }
            }
        val slow = supabase.pluginManager.getPlugin(SlowClosingPlugin).config
        val todos = supabase.offline.from("todos")
        todos.select()

        // When — calls while supabase-kt's HTTP client is closed and the plugin is not yet
        val closing = async { supabase.close() }
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { slow.closing.await() } }
        val read = runCatching { todos.select().data }
        val write = runCatching { todos.insert(oneRow()) }
        val call = runCatching { supabase.offline.rpc("add") }
        slow.release.complete(Unit)
        closing.await()
        val afterClose = runCatching { todos.insert(oneRow()) }

        // Then — none of them is a cancellation. Ktor may still send a request while its closing
        // client waits for earlier work, so each ends as sent or as offline: the read with the
        // rows, the write applied or queued for the next client, the call answered or refused
        assertEquals("""[{"id":1}]""", read.getOrThrow())
        val queued = if (write.getOrThrow() is MutationOutcome.Queued) 1 else 0
        assertEquals(queued, store.pendingMutationCount())
        call.exceptionOrNull()?.let { failure ->
            assertClientClosedError(failure, "rpc while supabase.close() runs")
        }
        assertClientClosedError(afterClose.exceptionOrNull(), "a write after close")
    }

    @Test
    fun a_select_left_to_finish_by_supabase_close_ends_with_rows_never_a_cancellation() = runTest {
        // Given — a warm cache, and a read whose request is held, then answered 503
        val outcome = heldSelectRetriedAcross(closeThroughSupabase = true)

        // Then — its retry, made once the HTTP client was closing, ended as offline with the
        // cached rows, or, when Ktor still sent it, with the server's
        val rows = outcome.rows.getOrThrow()
        if (outcome.requests == 2) assertEquals("""[{"id":1}]""", rows)
        else assertEquals("""[{"id":2}]""", rows)
    }

    @Test
    fun a_select_left_to_finish_by_the_plugins_own_close_sends_its_retry() = runTest {
        // Given — the same read, closed by the plugin's own close first
        val outcome = heldSelectRetriedAcross(closeThroughSupabase = false)

        // Then — the HTTP client was still open, so the retry was sent and answered
        assertEquals("""[{"id":2}]""", outcome.rows.getOrThrow())
        assertEquals(3, outcome.requests)
    }

    /** How a held read ended, and how many requests reached the server. */
    private class HeldSelectOutcome(val rows: Result<String>, val requests: Int)

    /**
     * Reads once to warm the cache, then reads again with the second request held until the client
     * is closing, then answered `503`, which supabase-kt retries once. The read is left to finish.
     */
    private suspend fun heldSelectRetriedAcross(closeThroughSupabase: Boolean): HeldSelectOutcome {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInt(0)
        val server =
            RecordingPostgrestServer(
                answer = {
                    when (calls.incrementAndFetch()) {
                        1 -> okJson("""[{"id":1}]""")
                        2 -> {
                            arrived.complete(Unit)
                            release.await()
                            postgrestError(HttpStatusCode.ServiceUnavailable, code = "PGRST000")
                        }
                        else -> okJson("""[{"id":2}]""")
                    }
                }
            )
        val supabase =
            newTestSupabaseClient(engine = server.engine) {
                install(Postgrest) { maxRetries = 1 }
                install(Offline) { openInMemory() }
            }
        supabase.offline.from("todos").select()
        return withContext(Dispatchers.Default) {
            val read = async { runCatching { supabase.offline.from("todos").select().data } }
            withTimeout(5.seconds) { arrived.await() }
            val closing = async {
                if (closeThroughSupabase) supabase.close() else supabase.offline.close()
            }
            // Released once the plugin refuses new calls, so the retry meets a closing client
            withTimeout(5.seconds) {
                while (runCatching { supabase.offline.from("todos") }.isSuccess) delay(1)
            }
            release.complete(Unit)
            val rows = read.await()
            closing.await()
            if (!closeThroughSupabase) supabase.close()
            HeldSelectOutcome(rows, server.requests.size)
        }
    }
}
