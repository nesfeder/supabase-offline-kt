// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer.ForeignKey
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Every `QueueIfOffline` write takes its place in the queue before it is sent, and is sent at once
 * only when nothing of the same identity is pending ahead of it, in any table. So a write can never
 * overtake an older one: not one still in flight on a stalled connection, and not a queued parent
 * row in another table.
 */
class WriteAheadOrderTest {

    private val store = newTestOfflineStore()

    @Test
    fun an_older_write_that_falls_back_to_the_queue_does_not_overwrite_a_newer_online_write() =
        runTest {
            // Given — edit A is in flight on a stalled connection
            var requestCount = 0
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val server =
                RecordingPostgrestServer(
                    answer = {
                        requestCount++
                        if (requestCount == 1) {
                            parked.complete(Unit)
                            release.await()
                            connectivityFailure()
                        }
                        okJson("[]")
                    }
                )
            val builder =
                newTestOfflineTable(
                    delegate = server.supabaseClient().postgrest.from(TABLE),
                    store = store,
                )
            val first = async {
                builder.update(element("""{"title":"A"}""")) { filter { eq("id", 1) } }
            }
            parked.await()

            // When — the user edits again (B); then A times out; then the queue replays
            val second = builder.update(element("""{"title":"B"}""")) { filter { eq("id", 1) } }
            release.complete(Unit)
            val firstOutcome = first.await()
            newTestMutationQueueReplayer(store = store, supabase = server.supabaseClient())
                .syncAllTables()

            // Then — the server ends on the user's latest edit, B
            val patches = server.requests.filter { it.method == "PATCH" }.map { it.body }
            assertEquals(
                """{"title":"B"}""",
                patches.last(),
                "second=$second first=$firstOutcome patches=$patches",
            )
            assertEquals(0, store.pendingMutationCount())
        }

    @Test
    fun an_online_child_insert_waits_for_its_queued_parent_in_another_table() = runTest {
        // Given — the parent task was created offline and is still queued
        val server =
            FakeRelationalPostgrestServer(
                foreignKeys = mapOf(PHOTOS to ForeignKey("task_id", TASKS))
            )
        store.enqueueInsert(table = TASKS, body = rows("""[{"id":"T","title":"new task"}]"""))
        val photos =
            newTestOfflineTable(
                delegate = server.supabaseClient().postgrest.from(PHOTOS),
                store = store,
            )

        // When — back online, the user attaches a photo to it before any pass has run
        val outcome = runCatching { photos.insert(rows("""[{"id":"P","task_id":"T"}]""")) }
        newTestMutationQueueReplayer(store = store, supabase = server.supabaseClient())
            .syncAllTables()

        // Then — no error reached the caller, and both rows arrived, parent first
        assertTrue(outcome.isSuccess, "child write failed with ${outcome.exceptionOrNull()}")
        assertIs<MutationOutcome.Queued>(outcome.getOrNull())
        assertEquals(1, server.rows(PHOTOS).size)
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private fun element(json: String): JsonElement = Json.parseToJsonElement(json)

    private companion object {
        const val TABLE = "tasks"
        const val TASKS = "tasks"
        const val PHOTOS = "task_photos"
    }
}
