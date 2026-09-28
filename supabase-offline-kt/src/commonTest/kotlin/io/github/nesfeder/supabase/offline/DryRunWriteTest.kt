package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A write built with `dryRun()` asks PostgREST to roll the transaction back (`Prefer: tx=rollback`),
 * so it changes nothing on the server. The engine treats it as `OnlineOnly` whatever strategy the
 * caller passed: it is never queued (a queued dry run replayed as a real write, since the queue does
 * not carry `Prefer`) and never overlaid onto the cache.
 */
class DryRunWriteTest {

    private val server = RecordingPostgrestServer()
    private val objectDao = FakeObjectDao()
    private val mutationDao = FakeMutationDao()
    private val store = newTestOfflineStore(objectDao = objectDao, mutationDao = mutationDao)
    private val builder = OfflinePostgrestQueryBuilderImpl(
        delegate = server.supabaseClient().postgrest.from(TABLE),
        store = store,
    )

    @Test
    fun an_online_dry_run_update_leaves_the_cache_untouched() = runTest {
        // Given
        objectDao.seedPayload(TABLE, SIGNATURE, ORIGINAL)

        // When
        val outcome = builder.updateJson(patch(), MutationStrategy.QueueIfOffline) {
            filter { eq("id", 1) }
            dryRun()
        }

        // Then — the server saw a dry run and the cache still shows the committed row
        assertIs<MutationOutcome.Applied>(outcome)
        assertTrue(server.requests.single().prefer.orEmpty().contains("tx=rollback"))
        assertEquals(ORIGINAL, objectDao.getRow(TABLE, SIGNATURE)?.payload)
    }

    @Test
    fun an_online_dry_run_insert_is_not_materialized_into_the_cache() = runTest {
        // Given
        objectDao.seedPayload(TABLE, SIGNATURE, ORIGINAL)

        // When
        builder.insertJson(Json.parseToJsonElement("""[{"id":1,"title":"never committed"}]""") as JsonArray) {
            dryRun()
        }

        // Then
        assertEquals(ORIGINAL, objectDao.getRow(TABLE, SIGNATURE)?.payload)
    }

    @Test
    fun an_offline_dry_run_is_refused_and_never_queued() = runTest {
        // Given
        server.answer = { connectivityFailure() }

        // When / Then — the connectivity failure surfaces, as it would under OnlineOnly
        assertFailsWith<HttpRequestException> {
            builder.updateJson(patch(), MutationStrategy.QueueIfOffline) {
                filter { eq("id", 1) }
                dryRun()
            }
        }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun a_dry_run_on_a_table_with_pending_writes_goes_online_instead_of_joining_the_queue() = runTest {
        // Given — an earlier write to the table is still queued
        server.answer = { connectivityFailure() }
        builder.updateJson(patch(), MutationStrategy.QueueIfOffline) { filter { eq("id", 2) } }
        server.answer = { okJson("[]") }

        // When
        val outcome = builder.updateJson(patch(), MutationStrategy.QueueIfOffline) {
            filter { eq("id", 1) }
            dryRun()
        }

        // Then — sent as the dry run it is; nothing joined the queue
        assertIs<MutationOutcome.Applied>(outcome)
        assertTrue(server.requests.last().prefer.orEmpty().contains("tx=rollback"))
        assertEquals(1, mutationDao.inserted.size)
    }

    private fun patch(): JsonElement = Json.parseToJsonElement("""{"title":"never committed"}""")

    private companion object {
        const val TABLE = "todos"
        const val SIGNATURE = "todos?id=eq.1"
        const val ORIGINAL = """[{"id":1,"title":"committed"}]"""
    }
}
