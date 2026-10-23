// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseInternal::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.plugins.SupabasePlugin
import io.github.jan.supabase.plugins.SupabasePluginProvider
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeMessage
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.ScriptedRealtimeWebsocket
import io.github.nesfeder.supabase.offline.testdoubles.ScriptedRealtimeWebsocketFactory
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.openInMemory
import kotlin.io.encoding.Base64
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * With the Realtime plugin installed before Offline, supabase-kt closes it first at `close()`, and
 * disconnects it on a sign-out: the channel ends from the plugin's side, while the follower is
 * being stopped or paused. The status still ends `Off` at close and stays there, `Paused` at
 * sign-out, and neither end is logged as a warning. A plugin installed between the two holds the
 * close after Realtime's, so the channel's end reaches the follower before Offline's close does,
 * the ordering that stalled the status; the close is repeated all the same, since how the end
 * travels is a matter of timing.
 */
class RealtimeCloseTest {

    /**
     * A plugin whose `close()` waits for [Config.release]. Installed between Realtime and Offline,
     * it holds supabase-kt's close between the two.
     */
    private class HoldingClosePlugin(
        override val supabaseClient: SupabaseClient,
        override val config: Config,
    ) : SupabasePlugin<HoldingClosePlugin.Config> {

        /** When the plugin's close starts, and what lets it finish. */
        class Config {
            val closing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
        }

        override suspend fun close() {
            config.closing.complete(Unit)
            config.release.await()
        }

        companion object : SupabasePluginProvider<Config, HoldingClosePlugin> {
            override val key: String = "holding-close-test-plugin"

            override fun createConfig(init: Config.() -> Unit): Config = Config().apply(init)

            override fun create(
                supabaseClient: SupabaseClient,
                config: Config,
            ): HoldingClosePlugin = HoldingClosePlugin(supabaseClient, config)
        }
    }

    @Test
    fun close_with_realtime_installed_first_ends_off_and_logs_no_warning() =
        runTest(timeout = 5.minutes) {
            withContext(Dispatchers.Default) {
                repeat(CLOSES) { iteration ->
                    // Given — the channel joined
                    val logs = RecordingLogSink()
                    val sockets = ScriptedRealtimeWebsocketFactory()
                    val hold = HoldingClosePlugin.Config()
                    val supabase = pluginClient(logs, sockets, hold = hold)
                    val socket = sockets.awaitSocket(0)
                    socket.takeJoin()
                    supabase.offline.awaitStatus(RealtimeStatus.Subscribed)

                    // When — the client closes: Realtime first, which ends the channel under the
                    // follower, and Offline only once the channel's end has reached it
                    val closing = launch { supabase.close() }
                    withTimeout(10.seconds) { hold.closing.await() }
                    supabase.offline.awaitStatus(RealtimeStatus.Stalled::class)
                    assertTrue(
                        logs.entriesAt(EngineLogLevel.WARNING).isEmpty(),
                        "close $iteration: ${logs.entriesAt(EngineLogLevel.WARNING)}",
                    )
                    hold.release.complete(Unit)
                    withTimeout(10.seconds) { closing.join() }

                    // Then — off, with nothing of the follower left to change it, and no warning
                    assertEquals(
                        RealtimeStatus.Off,
                        supabase.offline.realtimeStatus.value,
                        "close $iteration",
                    )
                    socket.awaitDisconnected()
                    assertEquals(
                        RealtimeStatus.Off,
                        supabase.offline.realtimeStatus.value,
                        "close $iteration, after the disconnect",
                    )
                    val warnings = logs.entriesAt(EngineLogLevel.WARNING)
                    assertTrue(warnings.isEmpty(), "close $iteration: $warnings")
                }
            }
        }

    @Test
    fun a_sign_out_that_disconnects_realtime_pauses_the_follow_and_logs_no_warning() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — the channel joined for a user
                val logs = RecordingLogSink()
                val sockets = ScriptedRealtimeWebsocketFactory()
                val supabase = pluginClient(logs, sockets, withAuth = true)
                supabase.auth.sessionStatus.first { status ->
                    status !is SessionStatus.Initializing
                }
                supabase.auth.importSession(
                    UserSession(
                        accessToken = jwt("user-a"),
                        refreshToken = "refresh-token",
                        expiresIn = 3600,
                        tokenType = "bearer",
                    ),
                    autoRefresh = false,
                )
                val socket = sockets.awaitSocket(0)
                socket.takeJoin()
                supabase.offline.awaitStatus(RealtimeStatus.Subscribed)

                // When — the user signs out: supabase-kt disconnects Realtime, which ends the
                // channel, as the follower pauses
                supabase.auth.clearSession()

                // Then — paused, the socket gone, and no warning
                supabase.offline.awaitStatus(RealtimeStatus.Paused)
                socket.awaitDisconnected()
                supabase.close()
                assertEquals(RealtimeStatus.Off, supabase.offline.realtimeStatus.value)
                val warnings = logs.entriesAt(EngineLogLevel.WARNING)
                assertTrue(warnings.isEmpty(), "$warnings")
            }
        }

    /**
     * A client with Realtime installed before Offline, over [sockets], whose logs [logs] records;
     * with the Auth plugin and nobody signed in when [withAuth]; with a plugin that holds the close
     * between Realtime's and Offline's when [hold] is given.
     */
    private fun pluginClient(
        logs: RecordingLogSink,
        sockets: ScriptedRealtimeWebsocketFactory,
        withAuth: Boolean = false,
        hold: HoldingClosePlugin.Config? = null,
    ): SupabaseClient =
        newTestSupabaseClient(logs = logs) {
            install(Postgrest)
            if (withAuth) {
                install(Auth) {
                    sessionManager = MemorySessionManager()
                    codeVerifierCache = MemoryCodeVerifierCache()
                    autoLoadFromStorage = false
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                }
            }
            install(Realtime) {
                websocketFactory = sockets
                heartbeatInterval = 1.hours
                reconnectDelay = 1.hours
            }
            if (hold != null) {
                install(HoldingClosePlugin) {
                    // The plugin reports on its own config and waits on it: the test's is wired
                    // to it each way
                    closing.invokeOnCompletion { hold.closing.complete(Unit) }
                    hold.release.invokeOnCompletion { release.complete(Unit) }
                }
            }
            install(Offline) {
                openInMemory()
                realtime { table(TABLE) }
            }
        }

    /** Takes the join supabase-kt sends on this socket, as the server would. */
    private suspend fun ScriptedRealtimeWebsocket.takeJoin() {
        val join = awaitSent("phx_join")
        val bindings =
            join.payload["config"]?.jsonObject?.get("postgres_changes")?.jsonArray.orEmpty()
        answer(
            RealtimeMessage(
                join.topic,
                "phx_reply",
                buildJsonObject {
                    put("status", "ok")
                    putJsonObject("response") {
                        putJsonArray("postgres_changes") {
                            bindings.forEachIndexed { index, binding ->
                                add(
                                    buildJsonObject {
                                        binding.jsonObject.forEach { (key, value) ->
                                            put(key, value)
                                        }
                                        put("id", index + 1)
                                    }
                                )
                            }
                        }
                    }
                },
                join.ref,
            )
        )
    }

    private suspend fun Offline.awaitStatus(expected: RealtimeStatus) {
        withTimeout(10.seconds) { realtimeStatus.first { status -> status == expected } }
    }

    /** Waits until the status is of [kind], as `Stalled` carries its cause. */
    private suspend fun Offline.awaitStatus(kind: KClass<out RealtimeStatus>) {
        withTimeout(10.seconds) { realtimeStatus.first { status -> kind.isInstance(status) } }
    }

    /** An unsigned JWT for [subject] whose every segment is base64, as supabase-kt decodes them. */
    private fun jwt(subject: String): String {
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = encoder.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        val payload =
            encoder.encode(
                """{"sub":"$subject","role":"authenticated","exp":9999999999}""".encodeToByteArray()
            )
        return "$header.$payload.${encoder.encode("signature".encodeToByteArray())}"
    }

    private companion object {
        const val TABLE = "tasks"

        /** How many times the close is repeated: which side ends first is a matter of timing. */
        const val CLOSES = 50
    }
}
