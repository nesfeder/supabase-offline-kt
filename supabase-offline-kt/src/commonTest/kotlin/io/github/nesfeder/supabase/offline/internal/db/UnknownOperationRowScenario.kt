// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.isFromCache
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.RecordedRequest
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first

/**
 * A queued write whose stored operation this build does not write, on a real Room database: the row
 * is isolated, as every queue row the engine cannot read is. Reads of its table still answer, the
 * pending lists leave it out, a replay pass refuses it to the rejected list where the app sees it,
 * and the writes queued around it are sent.
 *
 * Both platform test classes run it; only opening the database differs by platform, so it is passed
 * in.
 *
 * @param open Opens the file at the given path through the library's own database builder.
 */
internal class UnknownOperationRowScenario(private val open: (path: String) -> OfflineDatabase) {

    /**
     * The shipped queue, a write stored as `RPC` behind it and an INSERT behind that, read, listed
     * and replayed through a client over the database's own DAOs.
     */
    suspend fun anUnknownOperationIsRefusedAloneAndTheRestGoesOn(path: String) {
        // Given — the shipped database, an unknown operation queued behind its writes, an INSERT
        // queued behind that, and a client over the database that sends nothing until asked
        ShippedDatabaseFixture.writeTo(path)
        insertPendingTodosWrite(
            path,
            id = UNKNOWN_ID,
            operation = "RPC",
            bodyJson = """{"fn":"archive_done"}""",
            createdAtMs = 1_727_000_006_000,
        )
        insertPendingTodosWrite(
            path,
            id = BEHIND_ID,
            operation = MutationOperation.INSERT.storedName,
            bodyJson = """[{"id":9,"title":"Queued behind it"}]""",
            createdAtMs = 1_727_000_007_000,
        )
        val database = open(path)
        val server =
            RecordingPostgrestServer(
                answer = { request -> okJson(if (request.method == "GET") SERVED else "[]") }
            )
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                queryCacheDao = database.queryCacheDao(),
                cachedFileDao = database.cachedFileDao(),
                mutationDao = database.mutationDao(),
                config = Offline.Config().apply { autoSync = false },
            )
        try {
            // When — the table is read from the server, from the cache and reactively
            val online = client.from("todos").select()
            client.disableNetwork()
            val cached = client.from("todos").select()
            client.enableNetwork()
            val reactive =
                client.from("todos").selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly).first()

            // Then — each answers, with the writes around the unknown one applied
            assertEquals(OVERLAID, online.data)
            assertFalse(online.isFromCache)
            assertEquals(OVERLAID, cached.data)
            assertTrue(cached.isFromCache)
            assertEquals(OVERLAID, reactive.result?.data)

            // When — the queue is listed
            val pending = client.pendingMutations().first().map { mutation -> mutation.id }
            val pendingTodos =
                client.pendingMutations("todos").first().map { mutation -> mutation.id }

            // Then — both lists leave the unknown write out, and keep the one behind it
            assertEquals(listOf(1L, 2L, 3L, 4L, BEHIND_ID), pending)
            assertEquals(listOf(1L, 3L, 4L, BEHIND_ID), pendingTodos)

            // When — a pass replays the queue
            val readsSent = server.requests.size
            val report = client.syncPendingMutations()
            val rejected = client.rejectedMutations().first()

            // Then — the unknown write is refused for the app to see, and the rest are sent
            assertEquals(
                listOf(UNKNOWN_ID to "The queued operation is not recognized"),
                report.rejected.map { mutation -> mutation.id to mutation.message },
            )
            assertEquals(
                listOf(UNKNOWN_ID to "The queued operation is not recognized"),
                rejected.map { mutation -> mutation.id to mutation.message },
            )
            assertEquals(5 to 0, report.syncedCount to report.remainingCount)
            assertEquals(
                SHIPPED_QUEUE_REPLAY + BEHIND_REQUEST,
                server.requests.drop(readsSent),
            )
            assertEquals(emptyList(), database.mutationDao().pendingAll())
        } finally {
            client.close()
            database.close()
        }
    }

    private companion object {
        /** The id of the write stored as `RPC`, after every fixture row. */
        const val UNKNOWN_ID = 6L

        /** The id of the INSERT queued behind it. */
        const val BEHIND_ID = 7L

        /** What the server returns for a read of `todos`. */
        const val SERVED = """[{"id":2,"title":"Walk the dog","done":false,"user_id":"u-1"}]"""

        /**
         * [SERVED] with the queued writes applied: the UPDATE of row 2, and the rows of both
         * pending INSERTs added — the shipped one and the one behind the unknown write.
         */
        const val OVERLAID =
            """[{"id":2,"title":"Walk the dog twice","done":true,"user_id":"u-1"},""" +
                """{"id":3,"title":"Water the plants"},{"id":9,"title":"Queued behind it"}]"""

        /** The INSERT behind the unknown write, as it reaches the server. */
        val BEHIND_REQUEST: RecordedRequest =
            RecordedRequest(
                method = "POST",
                table = "todos",
                query = mapOf("columns" to "id,title"),
                prefer = "return=minimal",
                contentProfile = "public",
                acceptProfile = null,
                body = """[{"id":9,"title":"Queued behind it"}]""",
            )
    }
}

/**
 * Adds an unfiltered pending `todos` write to the file at [path] with the raw driver, its operation
 * stored exactly as [operation] spells it, whether or not this build writes that name.
 */
private fun insertPendingTodosWrite(
    path: String,
    id: Long,
    operation: String,
    bodyJson: String,
    createdAtMs: Long,
) {
    val connection = defaultSQLiteDriver().open(path)
    try {
        val statement =
            connection.prepare(
                "INSERT INTO mutation_cache (id, table_name, operation, body_json, filter_json, " +
                    "created_at_ms) VALUES (?, 'todos', ?, ?, NULL, ?)"
            )
        try {
            statement.bindLong(1, id)
            statement.bindText(2, operation)
            statement.bindText(3, bodyJson)
            statement.bindLong(4, createdAtMs)
            statement.step()
        } finally {
            statement.close()
        }
    } finally {
        connection.close()
    }
}
