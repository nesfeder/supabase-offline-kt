// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FIRST_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_CACHE_KEY
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.MOVED_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.RecordingConflictPolicy
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.VERSION_COLUMN
import io.github.nesfeder.supabase.offline.testdoubles.cacheRows
import io.github.nesfeder.supabase.offline.testdoubles.enqueueDelete
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpdate
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineTable
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.overlaidPayloadFlow
import io.github.nesfeder.supabase.offline.testdoubles.rowWithId
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
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
    fun a_row_moved_on_another_column_is_resent_against_its_new_version_without_a_conflict() =
        runTest {
            // Given — another device marked the task done after this one renamed it
            queueRenameOfTaskOne()
            server.replaceRow(
                GUARDED_TABLE,
                taskRow(1, "theirs", done = true, version = MOVED_VERSION),
            )

            // When
            val report = replayer().sync()

            // Then — a stale base, not a conflict: resent at the new version, nobody asked, not
            // counted as resolved, and both changes kept
            assertEquals(listOf("PATCH", "GET", "PATCH"), server.requests.map { it.method })
            assertEquals("eq.$MOVED_VERSION", server.requests.last().query[VERSION_COLUMN])
            assertEquals(1 to 0, report.syncedCount to report.conflictsResolvedCount)
            assertEquals(emptyList(), policy.cases)
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
        // Asked after the first two sends only: no send is left to act on a third answer
        assertEquals(2, policy.cases.size)
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

    @Test
    fun a_delete_of_a_row_edited_elsewhere_is_a_conflict_the_delete_wins_by_default() = runTest {
        // Given — this device deleted the task while another renamed it
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        store.cacheRows(taskRow(1, "theirs"))
        store.mutationQueue.enqueueDelete(GUARDED_TABLE, rowWithId(1))
        server.replaceRow(GUARDED_TABLE, taskRow(1, "renamed elsewhere", version = MOVED_VERSION))

        // When
        val report = replayer().sync()

        // Then — asked once, with no local columns and no column sets; resent and deleted
        val moved = assertIs<ConflictCase.Moved>(policy.cases.single())
        assertNull(moved.local)
        assertEquals(emptySet(), moved.changedOnServer)
        assertEquals(emptySet(), moved.conflictingColumns)
        assertEquals(1 to 1, report.syncedCount to report.conflictsResolvedCount)
        assertEquals(listOf("DELETE", "GET", "DELETE"), server.requests.map { it.method })
        assertEquals(emptyList(), server.rows(GUARDED_TABLE))
    }

    @Test
    fun a_queued_update_after_a_head_send_that_returned_no_rows_replays_straight_through() =
        runTest {
            // Given — task 1 cached; one update sent at once, asking for no rows back, so nothing
            // could move the base of the next; then another made with the server out of reach
            server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
            store.cacheRows(taskRow(1, "theirs"))
            val tasks =
                newTestOfflineTable(
                    delegate = server.supabaseClient().postgrest.from(GUARDED_TABLE),
                    store = store,
                )
            val applied = tasks.update(column("done", true)) { filter { eq("id", 1) } }
            server.failsRequest = { true }
            val queued = tasks.update(column("title", "mine")) { filter { eq("id", 1) } }
            server.failsRequest = null
            server.recorder.requests.clear()

            // When
            val report = replayer().sync()

            // Then — its base was the earlier version; the device's own write moved it, which is
            // no conflict: resent at once, with no policy asked and nothing counted as resolved
            assertIs<MutationOutcome.Applied>(applied)
            assertIs<MutationOutcome.Queued>(queued)
            assertEquals(listOf("PATCH", "GET", "PATCH"), server.requests.map { it.method })
            assertEquals(emptyList(), policy.cases)
            assertEquals(1 to 0, report.syncedCount to report.conflictsResolvedCount)
            val onServer = server.rows(GUARDED_TABLE).single()
            assertEquals(
                JsonPrimitive("mine") to JsonPrimitive(true),
                onServer["title"] to onServer["done"],
            )
        }

    @Test
    fun a_row_the_rest_of_the_filter_no_longer_reaches_is_synced_as_the_unguarded_replay_was() =
        runTest {
            // Given — an update of task 1 while it is still open; on the server it was closed,
            // at the same version, so the write's own filter now reaches no row
            server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
            store.cacheRows(taskRow(1, "theirs"))
            store.mutationQueue.enqueueUpdate(GUARDED_TABLE, titled("mine"), notDone(1))
            server.replaceRow(GUARDED_TABLE, taskRow(1, "theirs", done = true))

            // When
            val report = replayer().sync()

            // Then — no refusal and no conflict: a write that reached no row, as before
            assertEquals(1, report.syncedCount)
            assertEquals(emptyList(), report.rejected)
            assertEquals(emptyList(), policy.cases)
            assertEquals(
                "done,id,updated_at,title".split(',').toSet(),
                server.requests.last().query.getValue("select").split(',').toSet(),
            )
            assertEquals(JsonPrimitive("theirs"), server.rows(GUARDED_TABLE).single()["title"])
        }

    @Test
    fun a_row_the_whole_filter_still_reaches_at_the_base_version_is_a_policy_denial() = runTest {
        // Given — the same filtered update, the row still open, and a policy hiding it from writes
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        store.cacheRows(taskRow(1, "theirs"))
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, titled("mine"), notDone(1))
        server.hidesRowsFromWrite = { request -> request.method == "PATCH" }

        // When
        val report = replayer().sync()

        // Then
        assertEquals(0, report.syncedCount)
        assertEquals(
            "The row cannot be changed under the server's current policies",
            report.rejected.single().message,
        )
    }

    @Test
    fun a_read_by_key_the_session_change_stops_leaves_the_write_queued() = runTest {
        // Given — user A's update of task 1, which moved on the server; A is signed out for B
        // as the precondition fails, so the read would go out under B's token
        val recording = RecordingPostgrestServer()
        val supabase = newTestSupabaseClientWithAuth(recording.engine)
        supabase.signInForTest("user-a")
        store.cacheRows(taskRow(1, "theirs"))
        val base = store.mutationQueue.baseFor(GUARDED_TABLE, rowWithId(1), setOf("title"), null)
        store.mutationQueue.enqueueOwnedBy("user:user-a") {
            enqueueUpdate(GUARDED_TABLE, titled("mine"), rowWithId(1), base)
        }
        recording.answer = { request ->
            if (request.method == "PATCH") supabase.signInForTest("user-b")
            okJson("[]")
        }

        // When
        val report =
            newTestMutationQueueReplayer(
                    store = store,
                    supabase = supabase,
                    conflictPolicy = policy,
                )
                .sync()

        // Then — never read as a gone row: nothing asked, dropped or parked, still A's to send
        assertEquals(SyncStop.SessionChanged, report.stop)
        assertEquals(listOf("PATCH"), recording.requests.map { it.method })
        assertEquals(emptyList(), policy.cases)
        assertEquals(0, report.conflictsKeptServerCount)
        assertEquals(
            listOf("user:user-a"),
            store.mutationQueue.allPendingMutations().map { it.owner },
        )
    }

    @Test
    fun a_filter_the_engine_cannot_judge_on_a_row_moved_elsewhere_resends_the_write() = runTest {
        // Given — an update filtered by a pattern the engine does not evaluate, on a row another
        // device changed: the precondition fails although the pattern still matches
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        store.cacheRows(taskRow(1, "theirs"))
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, column("done", true), titledLike(1))
        server.replaceRow(GUARDED_TABLE, taskRow(1, "theirs", version = MOVED_VERSION))

        // When
        val report = replayer().sync()

        // Then — never taken for a filter that missed: resent, and the change reached the server
        assertEquals(listOf("PATCH", "GET", "PATCH"), server.requests.map { it.method })
        assertEquals(1, report.syncedCount)
        assertEquals(JsonPrimitive(true), server.rows(GUARDED_TABLE).single()["done"])
    }

    @Test
    fun a_filter_the_engine_cannot_judge_on_an_unchanged_row_is_synced_without_a_resend() =
        runTest {
            // Given — the same update, the row at the version the device saw, renamed so the
            // pattern no longer matches it: the server's own answer is that the filter missed
            server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
            store.cacheRows(taskRow(1, "theirs"))
            store.mutationQueue.enqueueUpdate(GUARDED_TABLE, column("done", true), titledLike(1))
            server.replaceRow(GUARDED_TABLE, taskRow(1, "renamed"))

            // When
            val report = replayer().sync()

            // Then — a write that reached no row, as unguarded: no resend, refusal or conflict
            assertEquals(listOf("PATCH", "GET"), server.requests.map { it.method })
            assertEquals(1, report.syncedCount)
            assertEquals(emptyList(), report.rejected)
            assertEquals(emptyList(), policy.cases)
            assertEquals(JsonPrimitive(false), server.rows(GUARDED_TABLE).single()["done"])
        }

    @Test
    fun partial_rows_back_patch_their_columns_and_forward_a_base_that_keeps_the_rest() = runTest {
        // Given — two updates of task 1, each of one column; the server answers only the columns
        // a guarded write selects
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        store.cacheRows(taskRow(1, "theirs"))
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, titled("mine"), rowWithId(1))
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, column("done", true), rowWithId(1))

        // When
        val report = replayer().sync()

        // Then — the first came back without `done`, the second still replayed at its version,
        // and the cached row keeps every column with the server's latest version
        val (first, second) = server.requests
        assertEquals("id,updated_at,title", first.query["select"])
        assertEquals("id,updated_at,done", second.query["select"])
        assertNotEquals(first.query[VERSION_COLUMN], second.query[VERSION_COLUMN])
        assertEquals(2 to 0, report.syncedCount to report.conflictsResolvedCount)
        val onServer = server.rows(GUARDED_TABLE).single()
        assertEquals(listOf(onServer), cachedRows())
    }

    private fun titledLike(id: Int): FilterDescriptor =
        FilterDescriptor(
            conditions =
                listOf(
                    FilterDescriptor.Condition("id", "eq", id.toString()),
                    FilterDescriptor.Condition("title", "like", "the*"),
                )
        )

    private fun notDone(id: Int): FilterDescriptor =
        FilterDescriptor(
            conditions =
                listOf(
                    FilterDescriptor.Condition("id", "eq", id.toString()),
                    FilterDescriptor.Condition("done", "is", "false"),
                )
        )

    private fun column(name: String, value: Any): JsonObject =
        JsonObject(
            mapOf(
                name to
                    when (value) {
                        is Boolean -> JsonPrimitive(value)
                        else -> JsonPrimitive(value.toString())
                    }
            )
        )

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
