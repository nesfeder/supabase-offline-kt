// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.MutationOperation
import kotlin.test.assertEquals

/**
 * The mutation queue's projected and counting queries on a real Room database: what each returns is
 * exactly what the whole-row queries it replaced would have been filtered to, for every kind of row
 * that must be left out. The in-memory DAO the engine's other tests run over re-implements these
 * filters in Kotlin, so only a real database runs the SQL.
 *
 * Both platform test classes run it; only opening the database differs by platform, so it is passed
 * in.
 *
 * @param open Opens the file at the given path through the library's own database builder.
 */
internal class MutationQueueProjectionsScenario(
    private val open: (path: String) -> OfflineDatabase
) {

    /**
     * A queue of guarded and unguarded writes of two owners and of none, pending and synced, in two
     * tables: the base forward's projection, retirement's projection and the pending counts read
     * exactly what they stand for.
     */
    suspend fun theProjectionsReadExactlyTheRowsTheyStandFor(path: String) {
        // Given — a queue with every kind of row a base forward of user A's writes to `todos`
        // after write 2 must leave out: the writes up to it, an unguarded one, user B's, an
        // ownerless one, a synced one and one in another table
        val database = open(path)
        try {
            val dao = database.mutationDao()
            dao.insert(guarded(id = 1, owner = USER_A))
            dao.insert(guarded(id = 2, owner = USER_A))
            dao.insert(guarded(id = 3, owner = USER_A))
            dao.insert(guarded(id = 4, owner = USER_A).copy(baseVersion = null, baseJson = null))
            dao.insert(guarded(id = 5, owner = USER_B))
            dao.insert(guarded(id = 6, owner = null))
            dao.insert(guarded(id = 7, owner = USER_A).copy(syncedAtMs = 7_000, syncSequence = 2))
            dao.insert(guarded(id = 8, owner = USER_A).copy(table = OTHER_TABLE))
            dao.insert(guarded(id = 9, owner = USER_A))
            dao.insert(guarded(id = 10, owner = USER_B).copy(syncedAtMs = 6_000, syncSequence = 1))

            // When
            val usersGuardedAfterTwo = dao.pendingGuardedAfter(TABLE, afterId = 2, owner = USER_A)
            val ownerlessGuarded = dao.pendingGuardedAfter(TABLE, afterId = 0, owner = null)
            val synced = dao.syncedForTable(TABLE)
            val pending = dao.countPending()
            val pendingInTable = dao.countPendingForTable(TABLE)

            // Then — only A's guarded pending writes to the table after write 2, in order, with
            // their filter and base; only the ownerless one for no owner; the synced rows by
            // sequence; and the pending rows counted whatever their owner
            assertEquals(
                listOf(
                    PendingGuardedMutation(3, FILTER_JSON, BASE_JSON, USER_A),
                    PendingGuardedMutation(9, FILTER_JSON, BASE_JSON, USER_A),
                ),
                usersGuardedAfterTwo,
            )
            assertEquals(
                listOf(PendingGuardedMutation(6, FILTER_JSON, BASE_JSON, null)),
                ownerlessGuarded,
            )
            assertEquals(listOf(SyncedMutationRef(10, 1), SyncedMutationRef(7, 2)), synced)
            assertEquals(8, pending)
            assertEquals(7, pendingInTable)
        } finally {
            database.close()
        }
    }

    /** A guarded UPDATE of `todos` row 1 by [owner], pending, stored under [id]. */
    private fun guarded(id: Long, owner: String?): MutationRow =
        MutationRow(
            id = id,
            table = TABLE,
            operation = MutationOperation.UPDATE.storedName,
            bodyJson = """{"title":"renamed $id"}""",
            filterJson = FILTER_JSON,
            createdAtMs = 1_000 * id,
            owner = owner,
            baseVersion = "v1",
            baseJson = BASE_JSON,
        )

    private companion object {
        const val TABLE = "todos"
        const val OTHER_TABLE = "lists"
        const val USER_A = "user:a"
        const val USER_B = "user:b"
        const val FILTER_JSON = """{"conditions":[{"column":"id","operatorId":"eq","value":"1"}]}"""
        const val BASE_JSON = """{"title":"theirs","updated_at":"v1"}"""
    }
}
