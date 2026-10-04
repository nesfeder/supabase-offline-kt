// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MAX_REJECTED_MUTATIONS
import io.github.nesfeder.supabase.offline.internal.mutationqueue.ServerError
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.dismissRejectedMutation
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A write the server refuses for good is not lost: it leaves the queue for a persisted list — the
 * newest 100, within a size budget — that carries the write and the server's reason until the app
 * dismisses or retries it.
 */
class RejectedMutationListTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()
    private var clock = 1_000L
    private val store =
        newTestStore(
            queryCacheDao = queryCacheDao,
            mutationDao = mutationDao,
            nowMillis = { clock },
        )
    private val server = RecordingPostgrestServer()
    private val replayer =
        newTestMutationQueueReplayer(store = store, supabase = server.supabaseClient())

    @Test
    fun a_permanent_rejection_keeps_the_write_and_the_servers_reason() = runTest {
        // Given — a queued insert the server refuses with a check violation
        val id =
            store.mutationQueue.enqueueInsert(
                table = TABLE,
                body = rows("""[{"id":"t1","title":""}]"""),
            )
        server.answer = {
            postgrestError(
                HttpStatusCode.BadRequest,
                code = "23514",
                message = "new row violates check constraint",
                details = "Failing row contains (t1, ).",
                hint = "Give the task a title",
            )
        }
        clock = 5_000L

        // When
        val report = replayer.sync()

        // Then — it left the queue, and the rejection says what the server said
        assertEquals(0, store.pendingMutationCount())
        val rejected = report.rejected.single()
        assertEquals(id, rejected.id)
        assertEquals(TABLE, rejected.table)
        assertEquals(MutationOperation.INSERT, rejected.operation)
        assertEquals(Instant.fromEpochMilliseconds(1_000L), rejected.createdAt)
        assertEquals(Instant.fromEpochMilliseconds(5_000L), rejected.rejectedAt)
        assertEquals(rows("""[{"id":"t1","title":""}]"""), rejected.body)
        assertEquals(listOf(JsonObject(mapOf("id" to JsonPrimitive("t1")))), rejected.rowKeys)
        assertEquals(
            ServerError(
                status = 400,
                code = "23514",
                message = "new row violates check constraint",
                details = "Failing row contains (t1, ).",
                hint = "Give the task a title",
            ),
            rejected.serverError,
        )
        assertEquals("new row violates check constraint", rejected.message)
    }

    @Test
    fun the_rejected_list_survives_a_restart() = runTest {
        // Given — a write rejected in one process
        val id =
            store.mutationQueue.enqueueInsert(table = TABLE, body = rows("""[{"title":"x"}]"""))
        server.answer = { postgrestError(HttpStatusCode.UnprocessableEntity) }
        replayer.sync()

        // When — a new process opens the same database
        val restarted = newTestStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)

        // Then
        assertEquals(listOf(id), restarted.mutationQueue.rejectedList.rejections().map { it.id })
        assertEquals(
            422,
            restarted.mutationQueue.rejectedList.rejections().single().serverError?.status,
        )
    }

    @Test
    fun a_body_that_no_longer_parses_is_rejected_without_a_request() = runTest {
        // Given
        val id =
            mutationDao.insert(
                MutationRow(
                    table = TABLE,
                    operation = MutationOperation.INSERT,
                    bodyJson = "{broken",
                    filterJson = null,
                    createdAtMs = 0L,
                )
            )

        // When
        val report = replayer.sync()

        // Then — refused locally: no server error, a reason, nothing sent
        val rejected = report.rejected.single()
        assertEquals(id, rejected.id)
        assertNull(rejected.serverError)
        assertNull(rejected.body)
        assertEquals("The queued body no longer parses", rejected.message)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun an_unfiltered_update_is_rejected_without_a_request() = runTest {
        // Given — a legacy UPDATE row that carries no filter
        mutationDao.insert(
            MutationRow(
                table = TABLE,
                operation = MutationOperation.UPDATE,
                bodyJson = """{"done":true}""",
                filterJson = null,
                createdAtMs = 0L,
            )
        )

        // When
        val report = replayer.sync()

        // Then
        assertEquals(MutationOperation.UPDATE, report.rejected.single().operation)
        assertNull(report.rejected.single().serverError)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun a_filter_that_can_no_longer_be_read_is_refused_for_that_reason() = runTest {
        // Given — an UPDATE and an INSERT whose stored filters no longer decode
        mutationDao.insert(
            MutationRow(
                table = TABLE,
                operation = MutationOperation.UPDATE,
                bodyJson = """{"done":true}""",
                filterJson = "{not a filter",
                createdAtMs = 0L,
            )
        )
        mutationDao.insert(
            MutationRow(
                table = TABLE,
                operation = MutationOperation.INSERT,
                bodyJson = """[{"id":"t1"}]""",
                filterJson = "{not a filter",
                createdAtMs = 0L,
            )
        )

        // When
        val report = replayer.sync()

        // Then — both refused locally, saying the filter cannot be read; neither sent without it
        assertEquals(
            listOf(
                "The queued filter can no longer be read",
                "The queued filter can no longer be read",
            ),
            report.rejected.map { it.message },
        )
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun the_list_keeps_the_newest_hundred() = runTest {
        // Given — 105 writes the server refuses
        repeat(105) { index ->
            store.mutationQueue.enqueueInsert(table = TABLE, body = rows("""[{"n":$index}]"""))
        }
        server.answer = { postgrestError(HttpStatusCode.BadRequest, code = "22P02") }

        // When
        replayer.sync()

        // Then — the oldest five went first
        val ids = store.mutationQueue.rejectedList.rejections().map { it.id }
        assertEquals(MAX_REJECTED_MUTATIONS, ids.size)
        assertEquals((6L..105L).toList(), ids)
    }

    @Test
    fun a_dismissed_rejection_leaves_the_list() = runTest {
        // Given
        val id =
            store.mutationQueue.enqueueInsert(table = TABLE, body = rows("""[{"title":"x"}]"""))
        server.answer = { postgrestError(HttpStatusCode.BadRequest) }
        replayer.sync()

        // When
        val dismissed = store.mutationQueue.dismissRejectedMutation(id)

        // Then
        assertTrue(dismissed)
        assertEquals(emptyList(), store.mutationQueue.rejectedList.rejections())
        assertEquals(emptyList(), store.mutationQueue.rejectedList.rejectionsFlow().first())
    }

    @Test
    fun a_retried_rejection_rejoins_the_queue_at_the_tail_and_replays() = runTest {
        // Given — a rejected write, and a newer write queued after it
        val rejectedId =
            store.mutationQueue.enqueueInsert(table = TABLE, body = rows("""[{"title":"first"}]"""))
        server.answer = { postgrestError(HttpStatusCode.Forbidden) }
        replayer.sync()
        store.mutationQueue.enqueueInsert(table = TABLE, body = rows("""[{"title":"second"}]"""))

        // When — the app fixed the cause and retries it
        val newId =
            assertNotNull(store.mutationQueue.retryRejectedMutation(rejectedId, identity = null))
        server.answer = { okJson("[]") }
        val report = replayer.sync()

        // Then — it replayed after the newer write, under a new id, and left the list
        assertTrue(newId > rejectedId)
        assertEquals(2, report.syncedCount)
        assertEquals(
            listOf("""[{"title":"second"}]""", """[{"title":"first"}]"""),
            server.requests.takeLast(2).map { it.body },
        )
        assertEquals(emptyList(), store.mutationQueue.rejectedList.rejections())
    }

    @Test
    fun an_unreadable_rejected_list_is_kept_aside_and_a_fresh_one_started() = runTest {
        // Given — the reserved row holds something this build cannot decode
        val logger = RecordingLogSink()
        val loggingStore =
            newTestStore(
                queryCacheDao = queryCacheDao,
                mutationDao = mutationDao,
                nowMillis = { clock },
                engineLogger = logger.asEngineLogger(),
            )
        queryCacheDao.seedPayload(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY, "not a list")

        // When
        val rejections = loggingStore.mutationQueue.rejectedList.rejections()

        // Then — read as a fresh list, the old payload kept under its own key, and said at ERROR
        assertEquals(emptyList(), rejections)
        val keptAside =
            queryCacheDao.rowsOf(RESERVED_TABLE_NAME).filter { row ->
                row.cacheKey.startsWith("$REJECTED_MUTATIONS_CACHE_KEY.unreadable.")
            }
        assertEquals(listOf("not a list"), keptAside.map { row -> row.payload })
        assertEquals(1, logger.entriesAt(EngineLogLevel.ERROR).size)
    }

    @Test
    fun a_rejection_that_cannot_be_recorded_keeps_the_write_queued() = runTest {
        // Given — a write the server refuses, and a rejected list that cannot be written
        store.mutationQueue.enqueueInsert(table = TABLE, body = rows("""[{"title":"x"}]"""))
        queryCacheDao.failUpsertOf = { row ->
            row.table == RESERVED_TABLE_NAME && row.cacheKey == REJECTED_MUTATIONS_CACHE_KEY
        }
        server.answer = { postgrestError(HttpStatusCode.BadRequest) }

        // When
        val report = replayer.sync()

        // Then — the pass stopped with the write still queued rather than losing it
        assertIs<SyncStop.TransientFailure>(report.stop)
        assertEquals(1, store.mutationQueue.allPendingMutations().size)
        assertEquals(emptyList(), store.mutationQueue.rejectedList.rejections())
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "todos"
    }
}
