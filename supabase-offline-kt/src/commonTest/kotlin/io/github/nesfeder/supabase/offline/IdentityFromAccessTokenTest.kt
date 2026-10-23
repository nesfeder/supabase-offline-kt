// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A client without the Auth plugin that tells its users apart by the `sub` of its own provider's
 * token keeps each user's queued writes for that user's token, drops the cache when the token names
 * another user, queues and sends nothing for a token that names no user, joins the Realtime channel
 * again for a new user, and settles the token's user as it opens. Without the setting, one queue
 * serves every user, as before.
 */
class IdentityFromAccessTokenTest {

    /** The token the app's provider returns now; a test signs users in and out by setting it. */
    private var token: String? = testJwt(USER_A)

    @Test
    fun each_users_queued_writes_are_kept_apart_and_sent_only_with_their_own_token() =
        runTest(timeout = 2.minutes) {
            // Given — offline, user A's write and then user B's are queued
            val server = RecordingPostgrestServer(answer = { connectivityFailure() })
            val client = newClient(server)
            assertIs<MutationOutcome.Queued>(insert(client, "a1", "A's tea"))
            token = testJwt(USER_B)
            assertIs<MutationOutcome.Queued>(insert(client, "b1", "B's tea"))

            // Then — B sees only B's write
            assertEquals(listOf("b1"), pendingIds(client))

            // When — online, B syncs
            goOnline(server)
            val forB = client.syncPendingMutations()

            // Then — only B's write went, with B's token
            assertEquals(1, forB.syncedCount)
            assertEquals(0, forB.remainingCount)
            assertEquals(listOf("b1" to bearer(USER_B)), postsOf(server))

            // When — A is back, and syncs
            token = testJwt(USER_A)
            assertEquals(listOf("a1"), pendingIds(client))
            val forA = client.syncPendingMutations()

            // Then — A's write went, with A's token
            assertEquals(1, forA.syncedCount)
            assertEquals(listOf("b1" to bearer(USER_B), "a1" to bearer(USER_A)), postsOf(server))
            client.close()
        }

    @Test
    fun a_new_user_is_never_served_the_last_users_cached_rows_and_the_cache_is_dropped() =
        runTest(timeout = 2.minutes) {
            // Given — A's rows read and cached, fresh
            val cache = FakeQueryCacheDao()
            val server =
                RecordingPostgrestServer(
                    answer = { request ->
                        when (request.authorization) {
                            bearer(USER_A) -> okJson("""[{"id":"a1","title":"A's milk"}]""")
                            bearer(USER_B) -> okJson("""[{"id":"b1","title":"B's milk"}]""")
                            else -> okJson("[]")
                        }
                    }
                )
            val client = newClient(server, cache = cache)
            client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
            assertTrue(cache.rowsOf(TABLE).any { row -> "A's milk" in row.payload })

            // When — B's token, and a read only the cache may answer
            token = testJwt(USER_B)

            // Then — nothing of A's is served to B, and A's cache is dropped
            assertFailsWith<CacheMissException> {
                client.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly())
            }
            awaitTrue("A's cached rows dropped") {
                cache.rowsOf(TABLE).none { row -> "A's milk" in row.payload }
            }

            // When / Then — a read the cache could answer, were the rows B's, asks the server
            val read = client.from(TABLE).select(readStrategy = ReadStrategy.CacheFirst(1.days))
            assertTrue("B's milk" in read.data && "A's milk" !in read.data, read.data)
            client.close()
        }

    @Test
    fun a_token_that_names_no_user_queues_no_write_and_sends_none_queued() =
        runTest(timeout = 2.minutes) {
            // Given — offline, user A's write queued
            val server = RecordingPostgrestServer(answer = { connectivityFailure() })
            val client = newClient(server)
            assertIs<MutationOutcome.Queued>(insert(client, "a1", "A's tea"))

            // When — the provider returns a token that names no user, and a write is made
            token = "opaque-token"
            assertFailsWith<MutationNotQueueableException> { insert(client, "x1", "Whose tea") }

            // Then — nothing of A's is shown or sent, online either
            assertEquals(emptyList(), pendingIds(client))
            goOnline(server)
            assertEquals(0, client.syncPendingMutations().syncedCount)
            assertEquals(emptyList(), postsOf(server))

            // When — A's token is back
            token = testJwt(USER_A)

            // Then — A's write waited for it
            assertEquals(listOf("a1"), pendingIds(client))
            assertEquals(1, client.syncPendingMutations().syncedCount)
            assertEquals(listOf("a1" to bearer(USER_A)), postsOf(server))
            client.close()
        }

    @Test
    fun without_the_setting_every_user_shares_one_queue_as_before() =
        runTest(timeout = 2.minutes) {
            // Given — the setting off: A's and B's writes queued offline
            val server = RecordingPostgrestServer(answer = { connectivityFailure() })
            val client = newClient(server, fromToken = false)
            insert(client, "a1", "A's tea")
            token = testJwt(USER_B)
            insert(client, "b1", "B's tea")

            // Then — one queue, both shown to B
            assertEquals(listOf("a1", "b1"), pendingIds(client))

            // When — B syncs (the write queued behind the first may have asked for a pass too)
            goOnline(server)
            client.syncPendingMutations()

            // Then — both sent with B's token
            awaitTrue("both writes sent") {
                postsOf(server) == listOf("a1" to bearer(USER_B), "b1" to bearer(USER_B))
            }
            client.close()
        }

    @Test
    fun the_realtime_channel_is_joined_again_when_the_token_names_another_user() =
        runTest(timeout = 2.minutes) {
            // Given — the channel joined for A once the client read A's token as it opened
            val feed = FakeServerChangeFeed()
            val server = RecordingPostgrestServer(answer = { okJson("[]") })
            val client = newClient(server, feed = feed)
            val first = feed.awaitFollow(0)

            // When — B's token, seen at the client's next call
            token = testJwt(USER_B)
            client.pendingMutationCount()

            // Then — A's channel left, and one joined for B
            first.awaitLeft()
            feed.awaitFollow(1)
            client.close()
        }

    @Test
    fun as_it_opens_the_client_sends_what_the_tokens_user_left_queued_and_nothing_else() =
        runTest(timeout = 2.minutes) {
            // Given — a run that queued A's write and then B's, offline, and closed
            val queue = FakeMutationDao()
            val offline = RecordingPostgrestServer(answer = { connectivityFailure() })
            val earlier = newClient(offline, queue = queue)
            insert(earlier, "a1", "A's tea")
            token = testJwt(USER_B)
            insert(earlier, "b1", "B's tea")
            earlier.close()

            // When — a new run opens online as A, with sending on, and the app calls nothing
            token = testJwt(USER_A)
            val online = RecordingPostgrestServer(answer = { okJson("[]") })
            val client = newClient(online, queue = queue, autoSync = true)

            // Then — A's write is sent with A's token, and B's waits for B
            awaitTrue("A's queued write sent") { ("a1" to bearer(USER_A)) in postsOf(online) }
            client.syncPendingMutations()
            assertEquals(listOf("a1" to bearer(USER_A)), postsOf(online))
            client.close()
        }

    /** A client over [server] whose provider returns [token], telling users apart by it. */
    private fun newClient(
        server: RecordingPostgrestServer,
        cache: FakeQueryCacheDao = FakeQueryCacheDao(),
        queue: MutationDao = FakeMutationDao(),
        feed: FakeServerChangeFeed? = null,
        fromToken: Boolean = true,
        autoSync: Boolean = false,
    ): OfflineClient =
        newTestOfflineClient(
            supabase = tokenClient(server),
            queryCacheDao = cache,
            mutationDao = queue,
            nowMillis = { Clock.System.now().toEpochMilliseconds() },
            config =
                Offline.Config().apply {
                    identityFromAccessToken = fromToken
                    this.autoSync = autoSync
                },
            watchedTables = if (feed == null) emptyList() else listOf(WatchedTable(TABLE)),
            serverChangeFeed = feed,
        )

    /** A supabase-kt client without Auth whose own provider returns [token]. */
    private fun tokenClient(server: RecordingPostgrestServer): SupabaseClient =
        newTestSupabaseClient(engine = server.engine) {
            accessToken = { token }
            install(Postgrest) { maxRetries = 0 }
        }

    private suspend fun insert(client: OfflineClient, id: String, title: String): MutationOutcome =
        client
            .from(TABLE)
            .insert(
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", id)
                            put("title", title)
                        }
                    )
                )
            )

    /** The ids of the writes the current user has queued, in order. */
    private suspend fun pendingIds(client: OfflineClient): List<String> =
        client.pendingMutations().first().flatMap { pending ->
            pending.rowKeys.mapNotNull { key -> key["id"]?.jsonPrimitive?.content }
        }

    /** Each insert the server received: the row's id and the token it carried. */
    private fun postsOf(server: RecordingPostgrestServer): List<Pair<String, String?>> =
        server.requests
            .filter { request -> request.method == "POST" && request.table == TABLE }
            .map { request ->
                val id = Regex(""""id":"([^"]+)"""").find(request.body)?.groupValues?.get(1)
                id.orEmpty() to request.authorization
            }

    private fun bearer(user: String): String = "Bearer ${testJwt(user)}"

    /** Answers every request from now on, and forgets the ones that failed offline. */
    private fun goOnline(server: RecordingPostgrestServer) {
        server.answer = { okJson("[]") }
        server.requests.clear()
    }

    /** Waits, on real threads, up to ten seconds for [condition]. */
    private suspend fun awaitTrue(what: String, condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) {
                while (!condition()) delay(20)
            }
        }
        assertTrue(condition(), what)
    }

    private companion object {
        const val TABLE = "todos"
        const val USER_A = "user-a"
        const val USER_B = "user-b"
    }
}
