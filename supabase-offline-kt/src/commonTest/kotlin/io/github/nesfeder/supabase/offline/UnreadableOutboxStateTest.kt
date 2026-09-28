package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.db.OfflineMutationSyncer
import io.github.nesfeder.supabase.offline.internal.db.QUEUE_OWNER_SIGNATURE
import io.github.nesfeder.supabase.offline.internal.db.REJECTED_MUTATIONS_SIGNATURE
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.outbox.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The outbox's own bookkeeping — who owns which write, and the rejected list — lives in reserved
 * cache rows. If one cannot be read (corruption, or a later build's format after a downgrade), the
 * engine degrades instead of turning the app's writes into internal exceptions or wedging the queue.
 */
class UnreadableOutboxStateTest {

    private val objectDao = FakeObjectDao()
    private val store = newTestOfflineStore(objectDao = objectDao)
    private val server = RecordingPostgrestServer()
    private val builder = OfflinePostgrestQueryBuilderImpl(
        delegate = server.supabaseClient().postgrest.from(TABLE),
        store = store,
        queueIdentity = { QueueIdentity.User("user-a") },
    )

    @Test
    fun unreadable_ownership_does_not_make_an_online_write_throw() = runTest {
        // Given — a pending write, and ownership bookkeeping that does not decode
        objectDao.seedPayload(RESERVED_TABLE_NAME, QUEUE_OWNER_SIGNATURE, "{not json")
        store.enqueueUpdate(TABLE, Json.parseToJsonElement("""{"title":"old"}"""), byId("2"))

        // When — online, the app edits another row
        val outcome = runCatching { builder.updateJson(Json.parseToJsonElement("""{"title":"new"}""")) { filter { eq("id", 1) } } }

        // Then — it gets an outcome, not an internal-state exception
        assertTrue(outcome.isSuccess, "online write failed with ${outcome.exceptionOrNull()}")
    }

    @Test
    fun unreadable_ownership_makes_an_offline_write_fail_with_its_own_connectivity_error() = runTest {
        // Given
        objectDao.seedPayload(RESERVED_TABLE_NAME, QUEUE_OWNER_SIGNATURE, "{not json")
        server.answer = { connectivityFailure() }

        // When
        val outcome = runCatching { builder.insertJson(rows("""[{"id":"n1"}]""")) }

        // Then — never an IllegalStateException
        assertIs<HttpRequestException>(outcome.exceptionOrNull(), "offline write failed with ${outcome.exceptionOrNull()}")
    }

    @Test
    fun an_unreadable_rejected_list_is_moved_aside_so_the_queue_does_not_wedge() = runTest {
        // Given — an unreadable list, and a queued write the server refuses
        objectDao.seedPayload(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE, "not a list")
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"refused"}]"""))
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"fine"}]"""))
        server.answer = { request ->
            if ("refused" in request.body) postgrestError(HttpStatusCode.BadRequest) else okJson("[]")
        }

        // When
        val report = OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).sync()

        // Then — the refusal is recorded on a fresh list, the next write went out, and the old
        // payload was kept aside rather than discarded
        assertEquals(1, report.syncedCount)
        assertEquals(1, store.outboxState.rejections().size)
        assertTrue(objectDao.rowsOf(RESERVED_TABLE_NAME).any { it.payload == "not a list" })
    }

    private fun byId(id: String): FilterDescriptor =
        FilterDescriptor(conditions = listOf(FilterDescriptor.Condition(column = "id", operatorId = "eq", value = id)))

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
    }
}
