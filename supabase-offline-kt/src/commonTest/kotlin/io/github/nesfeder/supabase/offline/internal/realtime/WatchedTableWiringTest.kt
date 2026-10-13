// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.buildOfflineClient
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

/**
 * The configuration's watched tables reach the client, and the feed they are followed through is
 * the test's script when one is set, Realtime's when the plugin is installed, and a failure to open
 * when it is not.
 */
class WatchedTableWiringTest {

    @Test
    fun tables_watched_without_the_realtime_plugin_fail_the_open() = runTest {
        // Given
        val config = Offline.Config().apply { realtime { table("tasks") } }

        // When
        val failure =
            assertFailsWith<IllegalStateException> {
                config.open(newTestSupabaseClient { install(Postgrest) })
            }

        // Then
        assertTrue("install(Realtime)" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun a_scripted_feed_stands_in_for_the_plugin() =
        runTest(timeout = 2.minutes) {
            // Given
            val feed = FakeServerChangeFeed()
            val config =
                Offline.Config().apply {
                    realtime { table("tasks") }
                    serverChangeFeed = { feed }
                }

            // When
            val client = config.open(newTestSupabaseClient { install(Postgrest) })

            // Then — followed through the script, in the client's default schema
            val follow = feed.awaitFollow(0)
            assertEquals(listOf(WatchedTable("tasks")), follow.tables)
            assertEquals("public", follow.defaultSchema)
            client.close()
        }

    @Test
    fun with_the_plugin_installed_the_client_opens_and_closes() =
        runTest(timeout = 2.minutes) {
            // Given — the Realtime plugin installed, and a feed the client never reaches
            val config = Offline.Config().apply { realtime { table("tasks") } }
            val supabase = newTestSupabaseClient {
                install(Postgrest)
                install(Realtime) { connectOnSubscribe = false }
            }

            // When
            val client = config.open(supabase)

            // Then — opened; a join that cannot connect ends the channel, which is tried again
            assertEquals(1, config.watchedTables.size)
            client.close()
            supabase.close()
        }

    @Test
    fun with_auth_installed_the_channel_waits_for_a_user() =
        runTest(timeout = 2.minutes) {
            // Given — the Auth plugin installed with nobody signed in, and a scripted feed
            val feed = FakeServerChangeFeed()
            val config =
                Offline.Config().apply {
                    watchedTables = listOf(WatchedTable("tasks"))
                    serverChangeFeed = { feed }
                }
            val supabase = newTestSupabaseClient {
                install(Postgrest)
                install(Auth) {
                    sessionManager = MemorySessionManager()
                    codeVerifierCache = MemoryCodeVerifierCache()
                    autoLoadFromStorage = false
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                }
            }

            // When
            val client = config.open(supabase)

            // Then — nothing is followed while there is no user
            withContext(Dispatchers.Default) { delay(300.milliseconds) }
            assertTrue(feed.follows.value.isEmpty())
            client.close()
            supabase.close()
        }

    @Test
    fun without_the_postgrest_plugin_the_default_schema_is_public() =
        runTest(timeout = 2.minutes) {
            // Given
            val feed = FakeServerChangeFeed()
            val config =
                Offline.Config().apply {
                    watchedTables = listOf(WatchedTable("tasks"))
                    serverChangeFeed = { feed }
                }

            // When
            val client = config.open(newTestSupabaseClient(plugins = {}))

            // Then
            assertEquals("public", feed.awaitFollow(0).defaultSchema)
            client.close()
        }

    private fun Offline.Config.open(supabase: SupabaseClient): OfflineClient =
        buildOfflineClient(
            supabase = supabase,
            queryCacheDao = FakeQueryCacheDao(),
            cachedFileDao = InMemoryCachedFileDao(),
            mutationDao = FakeMutationDao(),
            dataDirectory = "/tmp".toPath(),
            prepareFileCacheDirectories = false,
        )
}
