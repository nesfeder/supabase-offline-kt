package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.outbox.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The identity is checked before every request of a pass, not once at its start. A sign-out or an
 * account switch while a pass is running stops it before its next request; otherwise the rest of
 * the outgoing user's writes would go out under the new session — an UPDATE reaching nothing under
 * the anon key and being marked done, or an INSERT landing in the next user's account.
 */
class SessionChangeDuringPassTest {

    private val store = newTestOfflineStore()

    @Test
    fun no_write_is_sent_after_the_session_changes_mid_pass() = runTest {
        // Given — three of user A's edits queued; the session changes after the first replay
        var session: QueueIdentity = USER_A
        store.enqueueUpdate(TABLE, Json.parseToJsonElement("""{"title":"1"}"""), byId("a"))
        store.enqueueUpdate(TABLE, Json.parseToJsonElement("""{"title":"2"}"""), byId("b"))
        store.enqueueUpdate(TABLE, Json.parseToJsonElement("""{"title":"3"}"""), byId("c"))
        val server = RecordingPostgrestServer(
            answer = {
                session = USER_B
                okJson("[]")
            },
        )
        val syncer = OfflineMutationSyncer(store = store, supabase = server.supabaseClient(), currentIdentity = { session })

        // When
        syncer.sync()

        // Then — nothing went out under B, and A's two remaining edits are still queued
        assertEquals(1, server.requests.size, "requests sent: ${server.requests.map { it.body }}")
        assertEquals(listOf("""{"title":"2"}""", """{"title":"3"}"""), store.allPendingMutations().map { it.bodyJson })
    }

    private fun byId(id: String): FilterDescriptor =
        FilterDescriptor(conditions = listOf(FilterDescriptor.Condition(column = "id", operatorId = "eq", value = id)))

    private companion object {
        const val TABLE = "tasks"
        val USER_A = QueueIdentity.User("user-a")
        val USER_B = QueueIdentity.User("user-b")
    }
}
