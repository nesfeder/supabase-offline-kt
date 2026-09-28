package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Clearing local data is identity teardown (sign-out, account switch, account deletion). Once it
 * has run, nothing the outgoing session started may reach the server or the local store: a sweep
 * already replaying the queue stops, a write whose response arrives afterwards is not overlaid onto
 * the next user's cache, and a write that failed offline afterwards is not queued.
 */
class ClearLocalDataDuringWritesTest {

    private val server = RecordingPostgrestServer()
    private val objectDao = FakeObjectDao()
    private val mutationDao = FakeMutationDao()
    private val store = newTestOfflineStore(objectDao = objectDao, mutationDao = mutationDao)
    private val client = newTestOfflineClient(supabase = server.supabaseClient(), store = store)
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
        val sweep = launch { client.syncAllPendingMutations() }
        parked.await()

        // When — the user signs out while the first replay is in flight
        client.clearAllCache()
        release.complete(Unit)
        sweep.join()

        // Then — the second write never went out, and nothing is left queued
        assertEquals(1, server.requests.size)
        assertEquals(0, mutationDao.countAll())
    }

    @Test
    fun a_write_whose_response_arrives_after_clearing_is_not_overlaid_onto_the_next_users_cache() = runTest {
        // Given — the outgoing user's insert is in flight
        server.answer = {
            parked.complete(Unit)
            release.await()
            okJson("[]")
        }
        val write = async {
            client.from(TABLE).insertJson(rows("""[{"id":1,"title":"outgoing user's row"}]"""))
        }
        parked.await()

        // When — the cache is cleared, the next user's first read lands, then the old response
        client.clearAllCache()
        store.addPayload(
            table = TABLE,
            signature = SIGNATURE,
            payload = "[]",
            authoritativeEmpty = true,
            requestStartedAtTick = store.monotonicTickNow(),
        )
        release.complete(Unit)
        write.await()

        // Then — the next user's cache holds only what their own read returned
        assertEquals("[]", objectDao.getRow(TABLE, SIGNATURE)?.payload)
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
            runCatching { client.from(TABLE).insertJson(rows("""[{"id":1,"title":"outgoing user's row"}]""")) }
        }
        parked.await()

        // When — the cache is cleared before the failure comes back
        client.clearAllCache()
        release.complete(Unit)
        val outcome = write.await()

        // Then — the write is reported as failed, not queued under whoever is signed in next
        assertTrue(outcome.exceptionOrNull() is HttpRequestException, "got $outcome")
        assertEquals(0, mutationDao.countAll())
    }

    @Test
    fun an_offline_write_made_after_clearing_is_queued_normally() = runTest {
        // Given — the cache was cleared earlier
        client.clearAllCache()
        server.answer = { connectivityFailure() }

        // When
        val outcome = client.from(TABLE).insertJson(rows("""[{"id":1,"title":"new user's row"}]"""))

        // Then
        assertEquals(MutationOutcome.Queued, outcome)
        assertEquals(1, mutationDao.countAll())
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "todos"
        const val SIGNATURE = "todos?select=*"
    }
}
