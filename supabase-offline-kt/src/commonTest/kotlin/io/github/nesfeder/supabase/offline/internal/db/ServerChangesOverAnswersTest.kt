// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.cache.CacheWriteOutcome
import io.github.nesfeder.supabase.offline.internal.cache.ServerRowChange
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.RowWindow
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
 * A change the cache cannot lay over an answer exactly (one that may leave the query wrong, shows a
 * row the answer does not hold, or moves a row in an ordered list or a window) never makes the
 * answer read as fresh: it is refused, and on the reader's last ask written stale-stamped.
 *
 * Each test drives the store's tick by hand, so the order of request, change and answer is exact.
 * The holes a first attempt opened (#223), where an answer in flight held a row a change would have
 * changed while the cache did not, are pinned in their own section.
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
        assertNull(written.staleStamp)
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
    fun a_cold_live_lists_first_answer_without_a_server_record_is_refused() = runTest {
        // Given — a live list with nothing cached, and its first refresh out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao, liveCacheKeys = setOf("sig-A")) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server saves project-2 at tick 20, which gives the live list its rows
        tick = 20L
        store.applyServerChanges(saved("project-2"))
        val created = """[{"id":"project-2"}]"""
        assertEquals(created, queryCacheDao.getRow("projects", "sig-A")?.payload)

        // And — the first answer, read before project-2 was saved, lands
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = oneProject,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — it is refused: the record is a row it does not hold, which the server may not
        // show this list. The list keeps the record, stale, and its next request holds it
        assertEquals(CacheWriteOutcome.REFUSED_STALE, outcome)
        val row = queryCacheDao.getRow("projects", "sig-A")
        assertEquals(created, row?.payload)
        assertTrue(row != null && isStaleStamp(row.updatedAt), "the list is to be fetched again")
    }

    @Test
    fun a_server_record_an_answer_does_not_hold_refuses_it_until_the_last_ask() = runTest {
        // Given — nothing cached and no live query, and a one-shot read out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server saves project-2 at tick 20, and the answer from before lands
        tick = 20L
        store.applyServerChanges(saved("project-2"))
        tick = 30L
        val first =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = oneProject,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — it is refused, and nothing is cached
        assertEquals(CacheWriteOutcome.REFUSED_STALE, first)
        assertNull(queryCacheDao.getRow("projects", "sig-A"))

        // When — the one more ask goes out at tick 40, the server saves project-3 at tick 50, and
        // the answer, which holds project-2, lands as the last ask
        tick = 40L
        val lastRequestStartedAtTick = store.freshness.tickNow()
        tick = 50L
        store.applyServerChanges(saved("project-3"))
        tick = 60L
        val last =
            store.queryCache.cacheResponseReporting(
                table = "projects",
                cacheKey = "sig-A",
                payload = bothProjects,
                requestStartedAtTick = lastRequestStartedAtTick,
                lastAsk = true,
            )

        // Then — it is written with project-3 laid over it, stale: the read settles, and the next
        // one asks the server again
        val withProjectThree = """[{"id":"project-1"},{"id":"project-2"},{"id":"project-3"}]"""
        assertEquals(CacheWriteOutcome.WRITTEN, last.outcome)
        assertEquals(withProjectThree, last.rebasedPayload)
        val row = queryCacheDao.getRow("projects", "sig-A")
        assertEquals(withProjectThree, row?.payload)
        assertTrue(row != null && isStaleStamp(row.updatedAt), "the answer is not fresh")
    }

    // ── what is not laid over exactly ──────────────────────────────────────

    @Test
    fun an_older_server_record_does_not_put_back_a_row_the_answer_no_longer_holds() = runTest {
        // Given — "my todos" cached with todo-1 and todo-2, and a refresh out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        val cachedRows = """[{"id":"todo-1","owner":"me"},{"id":"todo-2","owner":"me"}]"""
        queryCacheDao.seedPayload("todos", "mine", cachedRows)
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        store.recordQueryShape("todos", "mine") { filter { eq("owner", "me") } }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server renames todo-2 (delivered at tick 20), then reassigns it to another
        // user, which RLS or the channel's filter never delivers here; the answer, read after the
        // reassignment, lacks todo-2
        tick = 20L
        store.applyServerChanges(
            saved("todo-2", "owner" to "me", "title" to "Renamed"),
            table = "todos",
        )
        val patched = queryCacheDao.getRow("todos", "mine")?.payload
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "todos",
                cacheKey = "mine",
                payload = """[{"id":"todo-1","owner":"me"}]""",
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — it is refused, not written fresh with todo-2 put back, which nothing would remove
        // until a later refresh
        assertEquals(CacheWriteOutcome.REFUSED_STALE, outcome)
        assertEquals(patched, queryCacheDao.getRow("todos", "mine")?.payload)
    }

    @Test
    fun a_server_insert_laid_over_an_ordered_page_is_not_written_as_fresh() = runTest {
        // Given — the newest two messages cached, and a refresh out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("messages", "latest-2", """[{"id":"m3"},{"id":"m2"}]""")
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        store.recordQueryShape("messages", "latest-2") {
            order("id", Order.DESCENDING)
            limit(2)
        }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — another user posts m4 after the server read the page; its insert arrives at tick
        // 20, before the answer
        tick = 20L
        store.applyServerChanges(saved("m4"), table = "messages")
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "messages",
                cacheKey = "latest-2",
                payload = """[{"id":"m3"},{"id":"m2"}]""",
                requestStartedAtTick = requestStartedAtTick,
                window = RowWindow(orderColumns = setOf("id"), isWindowed = true),
            )

        // Then — it is refused, not written as [m3, m2, m4]: past the limit and out of order. The
        // next request returns [m4, m3]
        assertEquals(CacheWriteOutcome.REFUSED_STALE, outcome)
    }

    @Test
    fun a_server_record_laid_over_a_keyless_answer_does_not_duplicate_its_row() = runTest {
        // Given — a list of titles alone (rows without their key), and a refresh out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("todos", "titles", """[{"title":"Milk"}]""")
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        store.recordQueryShape("todos", "titles", columns = TITLES) {}
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server renames todo-1 at tick 20; the answer, read after it, holds it
        tick = 20L
        store.applyServerChanges(saved("todo-1", "title" to "Milk 2"), table = "todos")
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "todos",
                cacheKey = "titles",
                payload = """[{"title":"Milk 2"}]""",
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — it is refused, not written with a second copy of the row; nothing tells which
        // row the record is, so the patch marked the list stale too
        assertEquals(CacheWriteOutcome.REFUSED_STALE, outcome)
        val row = queryCacheDao.getRow("todos", "titles")
        assertTrue(row != null && isStaleStamp(row.updatedAt), "the list is to be fetched again")
    }

    @Test
    fun a_server_delete_laid_over_a_window_is_not_written_as_fresh() = runTest {
        // Given — a page of two projects, and its refresh out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server deletes project-2 at tick 20, and the page from before lands
        tick = 20L
        store.applyServerChanges(deleted("project-2"))
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "page-1",
                payload = bothProjects,
                requestStartedAtTick = requestStartedAtTick,
                window = RowWindow(isWindowed = true),
            )

        // Then — it is refused: the server's page would hold the next project in its place
        assertEquals(CacheWriteOutcome.REFUSED_STALE, outcome)
    }

    @Test
    fun a_server_change_to_the_column_a_list_is_ordered_by_is_not_written_as_fresh() = runTest {
        // Given — a list ordered by rank, and its refresh out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server moves project-1 down at tick 20, and the answer from before lands
        tick = 20L
        store.applyServerChanges(saved("project-1", "rank" to 3))
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "by-rank",
                payload = """[{"id":"project-1","rank":"1"},{"id":"project-2","rank":"2"}]""",
                requestStartedAtTick = requestStartedAtTick,
                window = RowWindow(orderColumns = setOf("rank")),
            )

        // Then — it is refused: the server would place project-1 after project-2
        assertEquals(CacheWriteOutcome.REFUSED_STALE, outcome)
    }

    @Test
    fun a_server_change_to_another_column_of_an_ordered_list_is_laid_over_it() = runTest {
        // Given — a list ordered by rank, and its refresh out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the server renames project-1 at tick 20, and the answer from before lands
        tick = 20L
        store.applyServerChanges(saved("project-1", "rank" to 1, "title" to "New"))
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "by-rank",
                payload =
                    """[{"id":"project-1","rank":"1","title":"Old"},{"id":"project-2","rank":"2"}]""",
                requestStartedAtTick = requestStartedAtTick,
                window = RowWindow(orderColumns = setOf("rank")),
            )

        // Then — it is written fresh, renamed in place: the order holds
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        val row = queryCacheDao.getRow("projects", "by-rank")
        assertEquals(
            """[{"id":"project-1","rank":"1","title":"New"},{"id":"project-2","rank":"2"}]""",
            row?.payload,
        )
        assertTrue(row != null && !isStaleStamp(row.updatedAt), "the answer is fresh")
    }

    @Test
    fun on_the_last_ask_an_answer_a_change_may_leave_wrong_is_written_as_it_came_and_stale() =
        runTest {
            // Given — a list of titles alone, and the read's last ask out at tick 10
            val queryCacheDao = FakeQueryCacheDao()
            var tick = 10L
            val store = buildStore(queryCacheDao) { tick }
            store.recordQueryShape("todos", "titles", columns = TITLES) {}
            val requestStartedAtTick = store.freshness.tickNow()

            // When — the server deletes a todo at tick 20, which the cache cannot find among rows
            // without their key, and the answer lands
            tick = 20L
            store.applyServerChanges(deleted("todo-2"), table = "todos")
            tick = 30L
            val answer = """[{"title":"Milk"},{"title":"Eggs"}]"""
            val written =
                store.queryCache.cacheResponseReporting(
                    table = "todos",
                    cacheKey = "titles",
                    payload = answer,
                    requestStartedAtTick = requestStartedAtTick,
                    lastAsk = true,
                )

            // Then — it is written as the server sent it, stale: the read settles, and the next
            // one asks again
            assertEquals(CacheWriteOutcome.WRITTEN, written.outcome)
            assertNull(written.rebasedPayload)
            assertTrue(written.staleStamp != null, "reported as written stale")
            val row = queryCacheDao.getRow("todos", "titles")
            assertEquals(answer, row?.payload)
            assertTrue(row != null && isStaleStamp(row.updatedAt), "the answer is not fresh")
        }

    @Test
    fun a_last_ask_answer_with_an_unplaceable_change_still_drops_a_deleted_row() = runTest {
        // Given — "all" holds p1 and p2, a list read with other columns holds p3, and the last
        // ask of "all" out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "all", """[{"id":"p1"},{"id":"p2"}]""")
        queryCacheDao.seedPayload("projects", "titles", """[{"id":"p3","title":"T"}]""")
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        store.recordQueryShape("projects", "all") {}
        store.recordQueryShape("projects", "titles", columns = Columns.list("id,title")) {}
        val requestStartedAtTick = store.freshness.tickNow()

        // When — one Realtime batch deletes p2 and saves p3, which only a list of other columns
        // holds, so the patch may leave "all" wrong; then the answer, which holds p2, lands
        tick = 20L
        store.applyServerChanges(deleted("p2"), saved("p3", "title" to "T2"))
        tick = 30L
        val written =
            store.queryCache.cacheResponseReporting(
                table = "projects",
                cacheKey = "all",
                payload = """[{"id":"p1"},{"id":"p2"}]""",
                requestStartedAtTick = requestStartedAtTick,
                lastAsk = true,
            )

        // Then — it is written stale without p2: the delete Realtime delivered is not undone, and
        // no later event would remove the row
        assertEquals(CacheWriteOutcome.WRITTEN, written.outcome)
        val row = queryCacheDao.getRow("projects", "all")
        assertEquals("""[{"id":"p1"}]""", row?.payload)
        assertTrue(row != null && isStaleStamp(row.updatedAt), "the answer is not fresh")
        assertEquals(row.updatedAt, written.staleStamp)
    }

    @Test
    fun a_change_too_long_for_the_log_refuses_an_answer_that_left_before_it() = runTest {
        // Given — a refresh out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        var tick = 10L
        val store = buildStore(queryCacheDao) { tick }
        val requestStartedAtTick = store.freshness.tickNow()

        // When — a record longer than the log keeps for a table arrives
        tick = 20L
        val long = "x".repeat(CacheFreshness.MAX_LOGGED_SERVER_LENGTH.toInt())
        store.applyServerChanges(saved("project-2", "notes" to long))

        // Then — the answer is refused, as it was before server changes were logged
        tick = 30L
        assertEquals(
            CacheWriteOutcome.REFUSED_STALE,
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = oneProject,
                requestStartedAtTick = requestStartedAtTick,
            ),
        )
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
