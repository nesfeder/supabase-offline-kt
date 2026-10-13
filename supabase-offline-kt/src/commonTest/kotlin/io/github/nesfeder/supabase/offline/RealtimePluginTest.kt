// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_KEY
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_URL
import io.github.nesfeder.supabase.offline.testdoubles.openInMemory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The plugin as an app reaches it: tables named without the Realtime plugin fail the install, the
 * status reads `Off` without named tables and after `close()`, and the switch leaves the channel
 * and joins it again.
 */
class RealtimePluginTest {

    private val feed = FakeServerChangeFeed()

    @Test
    fun tables_named_without_the_realtime_plugin_fail_the_install() {
        // Given / When
        val failure =
            assertFailsWith<IllegalStateException> {
                createSupabaseClient(TEST_SUPABASE_URL, TEST_SUPABASE_KEY) {
                    httpEngine = MockEngine { respondOk("[]") }
                    install(Postgrest)
                    install(Offline) {
                        openInMemory()
                        realtime { table("tasks") }
                    }
                }
            }

        // Then — the message names the fix
        assertTrue("install(Realtime)" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun without_named_tables_the_status_reads_off_and_the_switch_changes_nothing() = runTest {
        // Given
        val supabase = pluginClient { openInMemory() }

        // When / Then
        assertEquals(RealtimeStatus.Off, supabase.offline.realtimeStatus.value)
        supabase.offline.disableRealtime()
        supabase.offline.enableRealtime()
        assertEquals(RealtimeStatus.Off, supabase.offline.realtimeStatus.value)
        assertTrue(feed.follows.value.isEmpty())
        supabase.close()
    }

    @Test
    fun the_switch_leaves_the_channel_and_joins_it_again() =
        runTest(timeout = 2.minutes) {
            // Given — a table named, followed through a scripted feed
            val supabase = pluginClient {
                openInMemory()
                serverChangeFeed = { feed }
                realtime { table("tasks") }
            }
            val first = feed.awaitFollow(0)
            first.join()
            supabase.offline.awaitStatus(RealtimeStatus.Subscribed)

            // When — switched off
            supabase.offline.disableRealtime()

            // Then — off at once, and the channel left
            assertEquals(RealtimeStatus.Off, supabase.offline.realtimeStatus.value)
            first.awaitLeft()

            // When — switched on
            supabase.offline.enableRealtime()

            // Then — joined anew, and off again at close
            feed.awaitFollow(1)
            supabase.offline.awaitStatus(RealtimeStatus.Subscribing)
            supabase.close()
            assertEquals(RealtimeStatus.Off, supabase.offline.realtimeStatus.value)
            assertFailsWith<IllegalStateException> { supabase.offline.disableRealtime() }
        }

    private fun pluginClient(configure: Offline.Config.() -> Unit): SupabaseClient =
        createSupabaseClient(TEST_SUPABASE_URL, TEST_SUPABASE_KEY) {
            httpEngine = MockEngine { respondOk("[]") }
            install(Postgrest)
            install(Offline) { configure() }
        }

    private suspend fun Offline.awaitStatus(expected: RealtimeStatus) {
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) { realtimeStatus.first { status -> status == expected } }
        }
    }
}
