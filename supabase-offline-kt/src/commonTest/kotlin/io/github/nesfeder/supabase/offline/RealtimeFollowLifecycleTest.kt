// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import io.github.nesfeder.supabase.offline.testdoubles.FakeServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The client joins the channel for its watched tables while a user is signed in (or there is no
 * Auth plugin), the network is not known down and the subscription is on; it leaves on sign-out,
 * when the network is known down or switched off, when the app switches it off, and at close. Each
 * test awaits what the feed saw, within a bound.
 */
class RealtimeFollowLifecycleTest {

    private val feed = FakeServerChangeFeed()
    private val server = RecordingPostgrestServer(answer = { okJson("[]") })
    private val monitor = MutableStateFlow(true)

    @Test
    fun with_auth_the_channel_is_joined_for_a_user_and_left_at_sign_out() =
        runTest(timeout = 2.minutes) {
            // Given — nobody signed in
            val supabase = newTestSupabaseClientWithAuth(server.engine)
            val client = newWatchingClient(supabase)
            assertEquals(RealtimeStatus.Paused, client.realtimeStatus.value)
            assertTrue(feed.follows.value.isEmpty())

            // When — a user signs in
            supabase.signInForTest("user-a")

            // Then — joined for the user
            val first = feed.awaitFollow(0)
            client.awaitState(RealtimeStatus.Subscribing)
            first.join()
            client.awaitState(RealtimeStatus.Subscribed)

            // When — the session is forgotten
            supabase.auth.clearSession()

            // Then — left, and paused until the next user
            first.awaitLeft()
            client.awaitState(RealtimeStatus.Paused)

            // When — another user signs in
            supabase.signInForTest("user-b")

            // Then — joined anew
            val second = feed.awaitFollow(1)
            assertTrue(!second.isLeft)
            client.close()
        }

    @Test
    fun a_new_session_of_the_same_user_does_not_restart_the_channel() =
        runTest(timeout = 2.minutes) {
            // Given — joined for a user
            val supabase = newTestSupabaseClientWithAuth(server.engine)
            val client = newWatchingClient(supabase)
            supabase.signInForTest("user-a")
            val first = feed.awaitFollow(0)
            first.join()
            client.awaitState(RealtimeStatus.Subscribed)

            // When — the session is refreshed: a new session of the same user
            supabase.signInForTest("user-a")
            withContext(Dispatchers.Default) { delay(300.milliseconds) }

            // Then — the same channel
            assertEquals(1, feed.follows.value.size)
            assertTrue(!first.isLeft)
            assertEquals(RealtimeStatus.Subscribed, client.realtimeStatus.value)
            client.close()
        }

    @Test
    fun without_auth_the_channel_is_left_while_the_monitor_says_offline_and_joined_when_back() =
        runTest(timeout = 2.minutes) {
            // Given — joined, no Auth
            val client = newWatchingClient(newTestSupabaseClient(engine = server.engine))
            val first = feed.awaitFollow(0)
            first.join()
            client.awaitState(RealtimeStatus.Subscribed)

            // When — the monitor says the network is down
            monitor.value = false

            // Then — left
            first.awaitLeft()
            client.awaitState(RealtimeStatus.Paused)

            // When — it is back
            monitor.value = true

            // Then — joined anew
            feed.awaitFollow(1)
            client.awaitState(RealtimeStatus.Subscribing)
            client.close()
        }

    @Test
    fun network_access_switched_off_leaves_and_switched_on_joins_again() =
        runTest(timeout = 2.minutes) {
            // Given
            val client = newWatchingClient(newTestSupabaseClient(engine = server.engine))
            val first = feed.awaitFollow(0)

            // When / Then
            client.disableNetwork()
            first.awaitLeft()
            client.awaitState(RealtimeStatus.Paused)
            client.enableNetwork()
            feed.awaitFollow(1)
            client.close()
        }

    @Test
    fun the_subscription_switched_off_leaves_and_switched_on_joins_again() =
        runTest(timeout = 2.minutes) {
            // Given
            val client = newWatchingClient(newTestSupabaseClient(engine = server.engine))
            val first = feed.awaitFollow(0)

            // When / Then
            client.disableRealtime()
            first.awaitLeft()
            client.awaitState(RealtimeStatus.Off)
            client.enableRealtime()
            feed.awaitFollow(1)
            client.awaitState(RealtimeStatus.Subscribing)
            client.close()
        }

    @Test
    fun close_leaves_the_channel_and_the_state_reads_off_for_good() =
        runTest(timeout = 2.minutes) {
            // Given — joined
            val client = newWatchingClient(newTestSupabaseClient(engine = server.engine))
            val first = feed.awaitFollow(0)
            first.join()
            client.awaitState(RealtimeStatus.Subscribed)

            // When
            client.close()

            // Then
            first.awaitLeft()
            assertEquals(RealtimeStatus.Off, client.realtimeStatus.value)
            assertEquals(1, feed.follows.value.size)
        }

    @Test
    fun without_watched_tables_nothing_is_followed_and_the_state_is_off() =
        runTest(timeout = 2.minutes) {
            // Given / When
            val client =
                newTestOfflineClient(
                    supabase = newTestSupabaseClient(engine = server.engine),
                    serverChangeFeed = feed,
                    networkAvailability = monitor,
                )
            withContext(Dispatchers.Default) { delay(200.milliseconds) }

            // Then
            assertEquals(RealtimeStatus.Off, client.realtimeStatus.value)
            assertTrue(feed.follows.value.isEmpty())
            client.close()
        }

    private fun newWatchingClient(supabase: SupabaseClient): OfflineClient =
        newTestOfflineClient(
            supabase = supabase,
            watchedTables = listOf(WatchedTable(TABLE)),
            serverChangeFeed = feed,
            networkAvailability = monitor,
        )

    /** Waits, on real threads, until the follow state reads [expected]. */
    private suspend fun OfflineClient.awaitState(expected: RealtimeStatus) {
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) { realtimeStatus.first { state -> state == expected } }
        }
    }

    private companion object {
        const val TABLE = "tasks"
    }
}
