// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.testdoubles.FIRST_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_CACHE_KEY
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.MOVED_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.RecordingConflictPolicy
import io.github.nesfeder.supabase.offline.testdoubles.VERSION_COLUMN
import io.github.nesfeder.supabase.offline.testdoubles.cacheRows
import io.github.nesfeder.supabase.offline.testdoubles.enqueueDelete
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpdate
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.overlaidPayloadFlow
import io.github.nesfeder.supabase.offline.testdoubles.rowWithId
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import io.github.nesfeder.supabase.offline.testdoubles.versionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How each guarded replay ends against a server that stamps a new version on every update: applied
 * with its rows back, resent after the row moved, dropped or synced when the row is gone, refused
 * when a policy hides the row from the write, synced when an earlier send's answer was lost, parked
 * when the row will not hold still, and kept queued when the read by key cannot reach the server.
 */
class GuardedReplayOutcomesTest {

    private val store = newGuardedStore()
    private val server =
        FakeRelationalPostgrestServer(
            versionColumns =
                mapOf(GUARDED_TABLE to FakeRelationalPostgrestServer.VersionColumn(VERSION_COLUMN))
        )
    private val policy = RecordingConflictPolicy()

    private fun replayer(): MutationQueueReplayer =
        newTestMutationQueueReplayer(
            store = store,
            supabase = server.supabaseClient(),
            conflictPolicy = policy,
        )

    /** The server and the cache hold task 1 at [FIRST_VERSION], and an update of it is queued. */
    private suspend fun queueRenameOfTaskOne(title: String = "mine") {
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        store.cacheRows(taskRow(1, "theirs"))
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, titled(title), rowWithId(1))
    }

    @Test
    fun an_update_that_applies_patches_its_returned_row_into_the_cache_once() = runTest {
        // Given
        queueRenameOfTaskOne()

        // When
        val report = replayer().sync()

        // Then — synced with no conflict; the read shows the one row, at the server's new version
        assertEquals(1 to 0, report.syncedCount to report.conflictsResolvedCount)
        val onServer = server.rows(GUARDED_TABLE).single()
        assertEquals(JsonPrimitive("mine"), onServer["title"])
        val read = cachedRows()
        assertEquals(listOf(onServer), read)
        assertNotEquals(FIRST_VERSION, versionOf(read.single()))
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun a_row_moved_on_another_column_is_resent_against_its_new_version() = runTest {
        // Given — another device marked the task done after this one renamed it
        queueRenameOfTaskOne()
        server.replaceRow(GUARDED_TABLE, taskRow(1, "theirs", done = true, version = MOVED_VERSION))

        // When
        val report = replayer().sync()

        // Then — sent, refused by its precondition, read, resent at the new version: both changes
        assertEquals(listOf("PATCH", "GET", "PATCH"), server.requests.map { it.method })
        assertEquals("eq.$MOVED_VERSION", server.requests.last().query[VERSION_COLUMN])
        assertEquals(1 to 1, report.syncedCount to report.conflictsResolvedCount)
        val moved = assertIs<ConflictCase.Moved>(policy.cases.single())
        assertEquals(emptySet(), moved.changedOnServer)
        val onServer = server.rows(GUARDED_TABLE).single()
        assertEquals(
            JsonPrimitive("mine") to JsonPrimitive(true),
            onServer["title"] to onServer["done"],
        )
    }

    @Test
    fun a_row_moved_on_the_same_column_is_won_by_the_local_change_by_default() = runTest {
        // Given — another device renamed it too
        queueRenameOfTaskOne()
        server.replaceRow(GUARDED_TABLE, taskRow(1, "renamed elsewhere", version = MOVED_VERSION))

        // When
        val report = replayer().sync()

        // Then — the conflicting column is named, and the local change wins it
        val moved = assertIs<ConflictCase.Moved>(policy.cases.single())
        assertEquals(setOf("title"), moved.changedOnServer)
        assertEquals(setOf("title"), moved.conflictingColumns)
        assertEquals(MOVED_VERSION, moved.serverVersion)
        assertEquals(1 to 1, report.syncedCount to report.conflictsResolvedCount)
        assertEquals(JsonPrimitive("mine"), server.rows(GUARDED_TABLE).single()["title"])
    }

    @Test
    fun an_update_of_a_row_deleted_on_the_server_is_dropped_and_counted() = runTest {
        // Given
        queueRenameOfTaskOne()
        server.deleteRow(GUARDED_TABLE, "1")

        // When
        val report = replayer().sync()

        // Then — nothing resurrected; the write left the queue, counted as the server's
        assertIs<ConflictCase.Gone>(policy.cases.single())
        assertEquals(0 to 1, report.syncedCount to report.conflictsKeptServerCount)
        assertEquals(emptyList(), server.rows(GUARDED_TABLE))
        assertEquals(0, store.pendingMutationCount())
        assertEquals(emptyList(), store.mutationQueue.conflictedList.conflicts())
        assertEquals(emptyList(), cachedRows())
    }

    @Test
    fun a_delete_of_a_row_already_gone_is_synced_as_already_on_the_server() = runTest {
        // Given
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        store.cacheRows(taskRow(1, "theirs"))
        store.mutationQueue.enqueueDelete(GUARDED_TABLE, rowWithId(1))
        server.deleteRow(GUARDED_TABLE, "1")

        // When
        val report = replayer().sync()

        // Then
        assertEquals(1 to 1, report.syncedCount to report.alreadyOnServerCount)
        assertEquals(emptyList(), policy.cases)
    }

    @Test
    fun a_write_a_policy_hides_the_row_from_is_rejected_without_a_server_error() = runTest {
        // Given — the row reads at the base version, but no update reaches it
        queueRenameOfTaskOne()
        server.hidesRowsFromWrite = { request -> request.method == "PATCH" }

        // When
        val report = replayer().sync()

        // Then
        val rejected = report.rejected.single()
        assertEquals(
            "The row cannot be changed under the server's current policies",
            rejected.message,
        )
        assertNull(rejected.serverError)
        assertEquals(emptyList(), policy.cases)
        assertEquals(JsonPrimitive("theirs"), server.rows(GUARDED_TABLE).single()["title"])
    }

    @Test
    fun a_replay_whose_earlier_answer_was_lost_finds_its_change_and_syncs() = runTest {
        // Given — the first replay commits, and its answer never arrives
        queueRenameOfTaskOne()
        server.loseResponseAfterCommit = { request -> request.method == "PATCH" }
        val first = replayer().sync()

        // When — the next pass replays it at the old base
        val report = replayer().sync()

        // Then — the row already holds the change: synced as already there, not resent
        assertIs<SyncStop.TransientFailure>(first.stop)
        assertEquals(1 to 1, report.syncedCount to report.alreadyOnServerCount)
        assertEquals(listOf("PATCH", "PATCH", "GET"), server.requests.map { it.method })
        assertEquals(emptyList(), policy.cases)
        assertEquals(JsonPrimitive("mine"), cachedRows().single()["title"])
    }

    @Test
    fun a_row_that_keeps_moving_is_parked_after_three_sends_and_the_pass_goes_on() = runTest {
        // Given — every time the policy is asked, another device moves the row again; and a second
        // write to another task waits behind it
        queueRenameOfTaskOne()
        server.seed(GUARDED_TABLE, taskRow(2, "other"))
        store.cacheRows(taskRow(1, "theirs"), taskRow(2, "other"), cacheKey = "tasks?both")
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, titled("second"), rowWithId(2))
        server.replaceRow(GUARDED_TABLE, taskRow(1, "moved", version = MOVED_VERSION))
        var moves = 0
        policy.answer = { case ->
            moves++
            server.replaceRow(
                GUARDED_TABLE,
                taskRow(1, "move $moves", version = server.nextVersion(GUARDED_TABLE).content),
            )
            DefaultConflictPolicy.decide(case)
        }

        // When
        val report = replayer().sync()

        // Then — three sends of the first, then parked; the second applied
        assertEquals(3, server.requests.count { it.method == "PATCH" && it.query["id"] == "eq.1" })
        val parked = report.conflicted.single()
        assertEquals(1L, parked.id)
        assertEquals(
            "The row kept changing on the server while this write was replayed",
            parked.message,
        )
        assertEquals(1, report.syncedCount)
        assertEquals(
            JsonPrimitive("second"),
            server.rows(GUARDED_TABLE).first { it["id"] == JsonPrimitive(2) }["title"],
        )
        assertEquals(listOf(parked), store.mutationQueue.conflictedList.conflicts())
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun a_read_by_key_that_cannot_reach_the_server_stops_the_pass_with_the_write_queued() =
        runTest {
            // Given — the row moved, and the read that would tell why fails to connect
            queueRenameOfTaskOne()
            server.replaceRow(GUARDED_TABLE, taskRow(1, "theirs", version = MOVED_VERSION))
            server.failsRequest = { request -> request.method == "GET" }

            // When
            val report = replayer().sync()

            // Then
            assertIs<SyncStop.TransientFailure>(report.stop)
            assertEquals(1, store.pendingMutationCount())
            assertEquals(emptyList(), policy.cases)
            assertTrue(report.conflicted.isEmpty())
        }

    @Test
    fun an_integer_version_column_guards_the_replay_as_a_timestamp_does() = runTest {
        // Given — a counter the server bumps on every update
        val counted =
            FakeRelationalPostgrestServer(
                versionColumns =
                    mapOf(
                        GUARDED_TABLE to
                            FakeRelationalPostgrestServer.VersionColumn(
                                VERSION_COLUMN,
                                FakeRelationalPostgrestServer.VersionKind.COUNTER,
                            )
                    )
            )
        val atFive = Json.parseToJsonElement("""{"id":1,"title":"theirs","updated_at":5}""")
        counted.seed(GUARDED_TABLE, atFive as JsonObject)
        store.cacheRows(atFive)
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, titled("mine"), rowWithId(1))

        // When
        val report =
            newTestMutationQueueReplayer(store = store, supabase = counted.supabaseClient()).sync()

        // Then
        assertEquals("eq.5", counted.requests.single().query[VERSION_COLUMN])
        assertEquals(1, report.syncedCount)
        assertEquals(JsonPrimitive(6), counted.rows(GUARDED_TABLE).single()[VERSION_COLUMN])
    }

    private suspend fun cachedRows(): List<JsonObject> {
        val payload =
            store.queryOverlay.overlaidPayloadFlow(GUARDED_TABLE, GUARDED_CACHE_KEY).first()
        return (Json.parseToJsonElement(payload.orEmpty().ifEmpty { "[]" }) as JsonArray).map {
            it as JsonObject
        }
    }

    private fun titled(title: String): JsonObject =
        JsonObject(mapOf("title" to JsonPrimitive(title)))
}
