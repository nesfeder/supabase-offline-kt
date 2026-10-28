// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.query.Columns
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.cache.CacheWriteOutcome
import io.github.nesfeder.supabase.offline.internal.cache.ServerRowChange
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.recordQueryShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A server change applied to a table while a request for it is out (a Realtime event, a record the
 * app applies) is laid over the answer when it lands, instead of refusing it: the answer is written
 * with the change, so a row the server deleted is not brought back, and a table that changes every
 * round trip still has its refreshes land. The device's own writes still refuse an older answer,
 * since nothing replays them over it.
 *
 * Each test drives the store's tick by hand, so the order of request, change and answer is exact.
 * The last three pin the holes a first attempt opened (#223), where an answer in flight held a row
 * a change would have changed while the cache did not.
 */
class ServerChangesOverAnswersTest {

    private val oneProject = """[{"id":"project-1"}]"""
    private val bothProjects = """[{"id":"project-1"},{"id":"project-2"}]"""

    @Test
    fun a_server_delete_while_a_request_is_out_is_laid_over_its_answer() = runTest {
        // Given — sig-A cached as one row, and a refresh of it out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server deletes project-2 at tick 20, and the answer, which still holds it,
        // lands at tick 30
        tick = 20L
        store.applyServerChanges(deleted("project-2"))
        tick = 30L
        val written =
            store.queryCache.cacheResponseReporting(
                table = "projects",
                cacheKey = "sig-A",
                payload = bothProjects,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — the answer is written, with the delete laid over it: the read settles, and the
        // cache does not hold the deleted row
        assertEquals(CacheWriteOutcome.WRITTEN, written.outcome)
        assertEquals(oneProject, written.rebasedPayload)
        val row = queryCacheDao.getRow("projects", "sig-A")
        assertEquals(oneProject, row?.payload)
        assertTrue(row != null && !isStaleStamp(row.updatedAt), "the answer is not stale")
    }

    @Test
    fun a_server_record_while_a_request_is_out_is_laid_over_its_answer() = runTest {
        // Given — a cached list and a refresh of it out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", """[{"id":"project-1","title":"Old"}]""")
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server renames the project at tick 20, and the answer, read before the
        // rename, lands at tick 30
        tick = 20L
        store.applyServerChanges(saved("project-1", "title" to "New"))
        tick = 30L
        val written =
            store.queryCache.cacheResponseReporting(
                table = "projects",
                cacheKey = "sig-A",
                payload = """[{"id":"project-1","title":"Old"}]""",
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — the answer is written with the record laid over it
        assertEquals(CacheWriteOutcome.WRITTEN, written.outcome)
        assertEquals(
            """[{"id":"project-1","title":"New"}]""",
            queryCacheDao.getRow("projects", "sig-A")?.payload,
        )
    }

    @Test
    fun an_answer_that_already_holds_the_server_changes_is_written_as_it_came() = runTest {
        // Given — a refresh out at tick 10, and another user's delete of a row the answer never
        // held at tick 20, as Realtime sends every subscriber every delete
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val requestStartedAtTick = store.freshness.tickNow()
        tick = 20L
        store.applyServerChanges(deleted("theirs"))

        // When — the answer lands
        tick = 30L
        val written =
            store.queryCache.cacheResponseReporting(
                table = "projects",
                cacheKey = "sig-A",
                payload = bothProjects,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — written as the server sent it: the delete changed none of its rows
        assertEquals(CacheWriteOutcome.WRITTEN, written.outcome)
        assertNull(written.rebasedPayload)
        assertEquals(bothProjects, queryCacheDao.getRow("projects", "sig-A")?.payload)
    }

    @Test
    fun a_server_change_before_the_request_left_is_not_laid_over_its_answer() = runTest {
        // Given — the server deletes project-2 at tick 10, and a refresh goes out after it
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        store.applyServerChanges(deleted("project-2"))
        tick = 20L
        val requestStartedAtTick = store.freshness.tickNow()

        // When — its answer holds project-2, put back on the server since
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = bothProjects,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — the answer describes the delete already, and is written as it came
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        assertEquals(bothProjects, queryCacheDao.getRow("projects", "sig-A")?.payload)
    }

    @Test
    fun answers_keep_landing_while_a_server_change_arrives_every_round_trip() = runTest {
        // Given — a cached list, and another user deleting one of their own rows during every
        // request this device sends
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        var tick = 0L
        val store = buildStore(queryCacheDao) { tick }

        // When — five refreshes in a row, each answered after one of those deletes arrived
        val outcomes =
            (1..5).map { round ->
                tick += 10
                val requestStartedAtTick = store.freshness.tickNow()
                tick += 5
                store.applyServerChanges(deleted("theirs-$round"))
                tick += 5
                store.queryCache.cacheResponse(
                    table = "projects",
                    cacheKey = "sig-A",
                    payload = bothProjects,
                    requestStartedAtTick = requestStartedAtTick,
                )
            }

        // Then — every one lands
        assertEquals(List(5) { CacheWriteOutcome.WRITTEN }, outcomes)
        assertEquals(bothProjects, queryCacheDao.getRow("projects", "sig-A")?.payload)
    }

    @Test
    fun a_server_change_that_leaves_the_log_refuses_an_answer_that_left_before_it() = runTest {
        // Given — a refresh out at tick 10, a server delete at tick 20, a second refresh out at
        // tick 25, then as many more server deletes as the table's log keeps
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val firstRequestTick = store.freshness.tickNow()
        tick = 20L
        store.applyServerChanges(deleted("project-2"))
        tick = 25L
        val secondRequestTick = store.freshness.tickNow()
        repeat(CacheFreshness.MAX_LOGGED_SERVER_WRITES) { index ->
            tick = 30L + index
            store.applyServerChanges(deleted("theirs-$index"))
        }

        // When — both answers land: the first still holds project-2, the second does not
        tick = 1_000L
        val first =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = bothProjects,
                requestStartedAtTick = firstRequestTick,
            )
        val second =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = oneProject,
                requestStartedAtTick = secondRequestTick,
            )

        // Then — the first left before the delete the log no longer holds, and is refused as
        // before; the second left after it, and is written with the changes still logged
        assertEquals(CacheWriteOutcome.REFUSED_STALE, first)
        assertEquals(CacheWriteOutcome.WRITTEN, second)
        assertEquals(oneProject, queryCacheDao.getRow("projects", "sig-A")?.payload)
    }

    @Test
    fun a_burst_larger_than_the_log_refuses_an_answer_that_left_before_it() = runTest {
        // Given — a refresh out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — a burst of more server changes than the log keeps arrives as one patch
        tick = 20L
        store.applyServerChanges(
            *Array(CacheFreshness.MAX_LOGGED_SERVER_WRITES + 1) { index ->
                deleted("theirs-$index")
            }
        )

        // Then — the answer is refused, as it was before server changes were logged
        tick = 30L
        assertEquals(
            CacheWriteOutcome.REFUSED_STALE,
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = bothProjects,
                requestStartedAtTick = requestStartedAtTick,
            ),
        )
    }

    @Test
    fun a_server_change_from_a_channel_no_longer_followed_is_not_laid_over_an_answer() = runTest {
        // Given — a refresh out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — a delete arrives from a channel joined for a user who is no longer signed in
        tick = 20L
        store.cachePatcher.patchServerChanges(
            table = "projects",
            keyColumns = listOf("id"),
            changes = listOf(deleted("project-2")),
            applies = { false },
        )

        // Then — the answer is written as the server sent it
        tick = 30L
        val written =
            store.queryCache.cacheResponseReporting(
                table = "projects",
                cacheKey = "sig-A",
                payload = bothProjects,
                requestStartedAtTick = requestStartedAtTick,
            )
        assertEquals(CacheWriteOutcome.WRITTEN, written.outcome)
        assertEquals(bothProjects, queryCacheDao.getRow("projects", "sig-A")?.payload)
    }

    // ── the holes of the first attempt ─────────────────────────────────────

    @Test
    fun an_online_insert_with_nothing_cached_still_refuses_an_older_one_shot_answer() = runTest {
        // Given — nothing cached and no live query, and a one-shot read out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the user's own insert succeeds online at tick 20; it reaches no cached query
        tick = 20L
        store.cachePatcher.patchCachedQueries(
            table = "projects",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":"project-2"}]""",
            filterJson = null,
            defaultToNull = true,
        )

        // And — the read's answer from before the insert lands
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = oneProject,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — it is refused: nothing replays an online write, so a later cache-first or offline
        // read would lack the user's row
        assertEquals(CacheWriteOutcome.REFUSED_STALE, outcome)
        assertNull(queryCacheDao.getRow("projects", "sig-A"))
    }

    @Test
    fun a_server_change_that_may_leave_a_stale_query_wrong_keeps_it_stale() = runTest {
        // Given — a list whose rows carry no key, already marked stale, and its refresh out at
        // tick 10
        val queryCacheDao = FakeQueryCacheDao()
        val cachedRows = """[{"title":"Milk"}]"""
        queryCacheDao.seedPayload("todos", "titles", cachedRows, updatedAt = staleStampOf(1_000L))
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        store.recordQueryShape("todos", "titles", columns = TITLES) {}
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server deletes a row at tick 20; with no key in the rows, the cache cannot
        // tell which, if any, the list shows
        tick = 20L
        store.applyServerChanges(deleted("todo-2"), table = "todos")
        assertEquals(cachedRows, queryCacheDao.getRow("todos", "titles")?.payload)

        // And — the refresh's answer from before the delete lands
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "todos",
                cacheKey = "titles",
                payload = """[{"title":"Milk"},{"title":"Eggs"}]""",
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — it is refused, and the list stays stale, so it is fetched again
        assertEquals(CacheWriteOutcome.REFUSED_STALE, outcome)
        val row = queryCacheDao.getRow("todos", "titles")
        assertEquals(cachedRows, row?.payload)
        assertTrue(row != null && isStaleStamp(row.updatedAt), "the stale mark is kept")
    }

    @Test
    fun a_server_delete_of_an_uncached_row_does_not_bring_it_back() = runTest {
        // Given — nothing cached, and a re-read out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server deletes project-2 at tick 20; no cached query holds it
        tick = 20L
        store.applyServerChanges(deleted("project-2"))

        // And — the re-read's answer, which still holds project-2, lands
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = bothProjects,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — it is written without the deleted row, which no later event would remove
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        assertEquals(oneProject, queryCacheDao.getRow("projects", "sig-A")?.payload)
    }

    @Test
    fun a_server_update_that_moves_a_row_out_of_a_filtered_list_does_not_bring_it_back() = runTest {
        // Given — nothing cached for a list of open todos, and its read out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        store.recordQueryShape("todos", "open") { filter { eq("done", false) } }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server marks todo-2 done at tick 20
        tick = 20L
        store.applyServerChanges(saved("todo-2", "done" to true), table = "todos")

        // And — the answer from before lands, todo-2 still open in it
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "todos",
                cacheKey = "open",
                payload = """[{"id":"todo-1","done":false},{"id":"todo-2","done":false}]""",
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — it is written without todo-2
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        assertEquals(
            """[{"id":"todo-1","done":false}]""",
            queryCacheDao.getRow("todos", "open")?.payload,
        )
    }

    @Test
    fun a_server_record_for_a_cold_live_list_is_laid_over_its_first_answer() = runTest {
        // Given — a live list with nothing cached, and its first refresh out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao, liveCacheKeys = setOf("sig-A")) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server saves project-2 at tick 20, which gives the live list its rows
        tick = 20L
        store.applyServerChanges(saved("project-2"))
        assertEquals(
            """[{"id":"project-2"}]""",
            queryCacheDao.getRow("projects", "sig-A")?.payload,
        )

        // And — the first answer, read before project-2 was saved, lands
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = oneProject,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — it is written with project-2 laid over it, and is the server's answer: fresh
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        val row = queryCacheDao.getRow("projects", "sig-A")
        assertEquals(bothProjects, row?.payload)
        assertTrue(row != null && !isStaleStamp(row.updatedAt), "the answer is not stale")
    }

    @Test
    fun a_server_record_for_an_uncached_one_shot_query_is_laid_over_its_answer() = runTest {
        // Given — nothing cached and no live query, and a one-shot read out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server saves project-2 at tick 20, and the answer from before lands
        tick = 20L
        store.applyServerChanges(saved("project-2"))
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = oneProject,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — the cache holds project-2 too, as a later cache-first read must
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        assertEquals(bothProjects, queryCacheDao.getRow("projects", "sig-A")?.payload)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Applies [changes] to [table] as one patch, as the Realtime path and the app's calls do. */
    private suspend fun TestStore.applyServerChanges(
        vararg changes: ServerRowChange,
        table: String = "projects",
    ) {
        cachePatcher.patchServerChanges(
            table,
            keyColumns = listOf("id"),
            changes = changes.toList(),
        )
    }

    /** The server's record of the row [id], with [columns], saved. */
    private fun saved(id: String, vararg columns: Pair<String, Any>): ServerRowChange =
        change(id, deleted = false, columns.associate { (name, value) -> name to value.json() })

    /** The server's delete of the row [id]. */
    private fun deleted(id: String): ServerRowChange = change(id, deleted = true, emptyMap())

    private fun change(
        id: String,
        deleted: Boolean,
        columns: Map<String, JsonPrimitive>,
    ): ServerRowChange =
        ServerRowChange(
            record = JsonObject(mapOf("id" to JsonPrimitive(id)) + columns),
            keyFilterJson =
                encodeFilter(
                    FilterDescriptor(
                        conditions =
                            listOf(FilterDescriptor.Condition("id", operatorId = "eq", value = id))
                    ),
                    Json,
                ),
            deleted = deleted,
        )

    private fun Any.json(): JsonPrimitive =
        if (this is Boolean) JsonPrimitive(this) else JsonPrimitive(toString())

    /** A store over [queryCacheDao] whose ticks [tick] reads, with [liveCacheKeys] read live. */
    private fun buildStore(
        queryCacheDao: QueryCacheDao,
        liveCacheKeys: Set<String> = emptySet(),
        tick: () -> Long,
    ): TestStore =
        newTestStore(
            queryCacheDao = queryCacheDao,
            monotonicTick = tick,
            liveCacheKeysOf = { liveCacheKeys },
        )

    private companion object {
        /** A select of the title alone: rows without their key. */
        val TITLES = Columns.list("title")
    }
}
