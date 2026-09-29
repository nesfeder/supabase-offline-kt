// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.db.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A write the server keeps failing with a `5xx` stops every pass at it, and so holds back every
 * later write of the same identity. Discarding it is the way out: it leaves the queue unsent, the
 * overlay stops showing it, and the next pass goes on to the writes behind it.
 */
class DiscardPendingMutationTest {

    private val objectDao = FakeObjectDao()
    private val store = newTestOfflineStore(objectDao = objectDao)
    private val server = RecordingPostgrestServer()
    private var session: QueueIdentity = USER_A
    private val syncer =
        MutationQueueReplayer(
            store = store,
            supabase = server.supabaseClient(),
            currentIdentity = { session },
        )
    private val builder =
        OfflinePostgrestQueryBuilderImpl(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = store,
            queueIdentity = { session },
            headSender = syncer,
        )

    private suspend fun renameOffline(id: Int, title: String) {
        server.answer = { connectivityFailure() }
        builder.updateJson(patch(title)) { filter { eq("id", id) } }
    }

    @Test
    fun discarding_the_write_the_server_keeps_failing_lets_the_rest_through() = runTest {
        // Given — a poison write at the head, and a good one behind it
        renameOffline(1, "poison")
        renameOffline(2, "fine")
        server.answer = { request ->
            if ("poison" in request.body)
                postgrestError(HttpStatusCode.InternalServerError, code = "54000")
            else okJson("[]")
        }
        val stuck = syncer.sync()
        val poison = store.allPendingMutations().first()

        // When
        val discarded = syncer.discardPendingMutation(poison.id, session)
        val report = syncer.sync()

        // Then
        assertIs<SyncStop.TransientFailure>(stuck.stop)
        assertTrue(discarded)
        assertEquals(1, report.syncedCount)
        assertEquals("""{"title":"fine"}""", server.requests.last().body)
        assertEquals(0, store.allPendingMutations().size)
    }

    @Test
    fun a_discarded_write_disappears_from_the_overlay() = runTest {
        // Given — a cached row renamed offline
        objectDao.seedPayload(TABLE, SIGNATURE, """[{"id":1,"title":"original"}]""")
        renameOffline(1, "renamed")
        assertEquals(
            """[{"id":1,"title":"renamed"}]""",
            store.overlaidPayloadFlow(TABLE, SIGNATURE).first(),
        )

        // When
        syncer.discardPendingMutation(store.allPendingMutations().single().id, session)

        // Then
        assertEquals(
            """[{"id":1,"title":"original"}]""",
            store.overlaidPayloadFlow(TABLE, SIGNATURE).first(),
        )
    }

    @Test
    fun another_identitys_write_cannot_be_discarded() = runTest {
        // Given — user A's write
        renameOffline(1, "A's edit")

        // When — user B tries to discard it
        val discarded =
            syncer.discardPendingMutation(store.allPendingMutations().single().id, USER_B)

        // Then
        assertFalse(discarded)
        assertEquals(1, store.allPendingMutations().size)
    }

    @Test
    fun a_write_being_sent_cannot_be_discarded() = runTest {
        // Given — the write is on the wire
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.answer = {
            parked.complete(Unit)
            release.await()
            okJson("[]")
        }
        val write = async { builder.updateJson(patch("A")) { filter { eq("id", 1) } } }
        parked.await()

        // When
        val discarded =
            syncer.discardPendingMutation(store.allPendingMutations().single().id, session)
        release.complete(Unit)

        // Then — too late: it is already going out
        assertFalse(discarded)
        assertEquals(MutationOutcome.Applied::class, write.await()::class)
    }

    private fun patch(title: String): JsonElement =
        Json.parseToJsonElement("""{"title":"$title"}""")

    private companion object {
        const val TABLE = "tasks"
        const val SIGNATURE = "tasks?id=eq.1"
        val USER_A = QueueIdentity.User("user-a")
        val USER_B = QueueIdentity.User("user-b")
    }
}
