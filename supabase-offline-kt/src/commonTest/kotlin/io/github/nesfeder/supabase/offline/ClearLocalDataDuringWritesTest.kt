// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.nesfeder.supabase.offline.internal.db.REJECTED_MUTATIONS_CACHE_KEY
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Clearing local data is identity teardown (sign-out, account switch, account deletion). Once it
 * has run, nothing the outgoing session started may reach the server or the local store: a sweep
 * already replaying the queue stops, a write whose response arrives afterwards is not overlaid onto
 * the next user's cache, and a write that failed offline afterwards is not queued.
 */
class ClearLocalDataDuringWritesTest {

    private val server = RecordingPostgrestServer()
    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()
    private val store =
        newTestOfflineStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)
    /** Passes run only when a test starts one, so none the engine starts can race the clear. */
    private val client =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            store = store,
            coordinatorScope = idleCoroutineScope(),
        )
    private val parked = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    @Test
    fun clearing_local_data_during_a_parked_sweep_sends_nothing_further() = runTest {
        // Given — two queued writes; the server holds the first request open
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"first"}]"""))
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"second"}]"""))
        server.answer = {
            parked.complete(Unit)
            release.await()
            okJson("[]")
        }
        val sweep = launch { client.syncPendingMutations().syncedCount }
        parked.await()

        // When — the user signs out while the first replay is in flight
        client.clearLocalData()
        release.complete(Unit)
        sweep.join()

        // Then — the second write never went out, and nothing is left queued
        assertEquals(1, server.requests.size)
        assertEquals(0, mutationDao.countAll())
    }

    @Test
    fun a_refusal_that_arrives_after_clearing_is_not_recorded_for_the_next_user() = runTest {
        // Given — the server holds the outgoing user's replay open, then refuses it
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"outgoing user's write"}]"""))
        server.answer = {
            parked.complete(Unit)
            release.await()
            postgrestError(HttpStatusCode.Forbidden)
        }
        val sweep = launch { client.syncPendingMutations().syncedCount }
        parked.await()

        // When — local data is cleared while the replay is in flight, then the refusal comes back
        client.clearLocalData()
        release.complete(Unit)
        sweep.join()

        // Then — the pass was stopped before it could record the outgoing user's write on the list
        assertEquals(emptyList(), store.rejectedList.rejections())
        assertNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY))
    }

    @Test
    fun a_write_whose_response_arrives_after_clearing_is_not_overlaid_onto_the_next_users_cache() =
        runTest {
            // Given — the outgoing user's insert is in flight
            server.answer = {
                parked.complete(Unit)
                release.await()
                okJson("[]")
            }
            val write = async {
                client.from(TABLE).insert(rows("""[{"id":1,"title":"outgoing user's row"}]"""))
            }
            parked.await()

            // When — the cache is cleared, the next user's first read lands, then the old response
            client.clearLocalData()
            store.queryCache.cacheResponse(
                table = TABLE,
                cacheKey = CACHE_KEY,
                payload = "[]",
                authoritativeEmpty = true,
                requestStartedAtTick = store.freshness.tickNow(),
            )
            release.complete(Unit)
            write.await()

            // Then — the next user's cache holds only what their own read returned
            assertEquals("[]", queryCacheDao.getRow(TABLE, CACHE_KEY)?.payload)
        }

    @Test
    fun a_write_that_fails_offline_after_clearing_is_not_queued() = runTest {
        // Given — the outgoing user's write is in flight and will fail for connectivity
        server.answer = {
            parked.complete(Unit)
            release.await()
            connectivityFailure()
        }
        val write = async {
            runCatching {
                client.from(TABLE).insert(rows("""[{"id":1,"title":"outgoing user's row"}]"""))
            }
        }
        parked.await()

        // When — the cache is cleared before the failure comes back
        client.clearLocalData()
        release.complete(Unit)
        val outcome = write.await()

        // Then — the write is reported as failed, not queued under whoever is signed in next
        assertTrue(outcome.exceptionOrNull() is HttpRequestException, "got $outcome")
        assertEquals(0, mutationDao.countAll())
    }

    @Test
    fun an_offline_write_made_after_clearing_is_queued_normally() = runTest {
        // Given — the cache was cleared earlier
        client.clearLocalData()
        server.answer = { connectivityFailure() }

        // When
        val outcome = client.from(TABLE).insert(rows("""[{"id":1,"title":"new user's row"}]"""))

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(1, mutationDao.countAll())
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "todos"
        const val CACHE_KEY = "todos?select=*"
    }
}
