// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * PostgREST answers `PGRST205` for a table and `PGRST204` for a column its schema cache does not
 * know, which is what a write made just after a migration meets until the cache reloads. The write
 * did not run, and stays queued; only a table or column still missing after ten misses, each at
 * least thirty seconds after the last one counted, puts it on the rejected list, so a table that
 * does not exist cannot hold the queue for ever.
 */
class SchemaCacheMissTest {

    private var now: Duration = Duration.ZERO
    private val mutationDao = FakeMutationDao()
    private val store =
        newTestOfflineStore(mutationDao = mutationDao, monotonicTick = { now.inWholeNanoseconds })

    @Test
    fun a_replay_refused_for_a_table_the_schema_cache_does_not_know_stays_queued() = runTest {
        // Given — a queued keyed insert, and a server whose schema cache has not seen the table
        val id = enqueueInsert()
        val server =
            RecordingPostgrestServer(
                answer = { postgrestError(HttpStatusCode.NotFound, code = "PGRST205") }
            )
        val replayer = MutationQueueReplayer(store = store, supabase = server.supabaseClient())

        // When — a pass meets the miss, then the cache reloads
        val missed = replayer.sync()
        server.answer = { okJson("[]") }
        val after = replayer.sync()

        // Then — kept, not rejected, and known never to have run: it replays as the plain insert
        assertIs<SyncStop.TransientFailure>(missed.stop)
        assertEquals(emptyList(), missed.rejected)
        assertEquals(1, missed.remainingCount)
        assertEquals(1, after.syncedCount)
        assertEquals("return=minimal", server.requests.last().prefer)
        assertFalse(store.hasUnconfirmedSend(id))
    }

    @Test
    fun a_write_meeting_a_column_the_schema_cache_does_not_know_is_queued() = runTest {
        // Given — a server whose schema cache has not seen a column the write sets
        val server =
            RecordingPostgrestServer(
                answer = { postgrestError(HttpStatusCode.BadRequest, code = "PGRST204") }
            )
        val builder =
            OfflinePostgrestQueryBuilderImpl(
                delegate = server.supabaseClient().postgrest.from(TABLE),
                store = store,
            )

        // When
        val outcome = builder.insertJson(rows("""[{"id":"k1","title":"Buy milk"}]"""))

        // Then — the caller is told it is queued, and it waits for the cache
        assertEquals(MutationOutcome.Queued, outcome)
        assertEquals(1, store.allPendingMutations().size)
        assertEquals(1, mutationDao.inserted.single().schemaCacheMisses)
    }

    @Test
    fun a_table_still_missing_after_ten_spaced_misses_is_rejected() = runTest {
        // Given — a table the server never knows
        enqueueInsert()
        val server =
            RecordingPostgrestServer(
                answer = {
                    postgrestError(
                        HttpStatusCode.NotFound,
                        code = "PGRST205",
                        message = "Could not find the table in the schema cache",
                    )
                }
            )
        val replayer = MutationQueueReplayer(store = store, supabase = server.supabaseClient())

        // When — nine misses thirty seconds apart, with passes in between that come too soon
        repeat(9) {
            replayer.sync()
            now += 10.seconds
            replayer.sync()
            now += 20.seconds
        }
        val ninth = mutationDao.inserted.single().schemaCacheMisses
        val last = replayer.sync()

        // Then — only the spaced misses counted, and the tenth rejects it with the server's reason
        assertEquals(9, ninth)
        assertEquals(0, last.remainingCount)
        assertEquals(
            "Could not find the table in the schema cache",
            last.rejected.single().message,
        )
        assertEquals("PGRST205", last.rejected.single().serverError?.code)
    }

    @Test
    fun misses_counted_before_a_restart_count_towards_the_bound() = runTest {
        // Given — five counted misses, then a new process, whose clock starts again
        enqueueInsert()
        val server =
            RecordingPostgrestServer(
                answer = { postgrestError(HttpStatusCode.NotFound, code = "PGRST205") }
            )
        val before = MutationQueueReplayer(store = store, supabase = server.supabaseClient())
        repeat(5) {
            before.sync()
            now += 30.seconds
        }
        now = Duration.ZERO
        val after = MutationQueueReplayer(store = store, supabase = server.supabaseClient())

        // When — five more, spaced
        val reports =
            List(5) {
                after.sync().also { now += 30.seconds }
            }

        // Then — the first after the restart counted at once, and the fifth reached the bound
        assertEquals(listOf(1, 1, 1, 1, 0), reports.map { it.remainingCount })
        assertEquals(1, reports.last().rejected.size)
    }

    private suspend fun enqueueInsert(): Long {
        store.enqueueOwnedBy(null) {
            enqueueInsert(table = TABLE, body = rows("""[{"id":"k1","title":"Buy milk"}]"""))
        }
        return store.allPendingMutations().single().id
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "todos"
    }
}
