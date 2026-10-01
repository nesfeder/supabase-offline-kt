// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredRejection
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredServerError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore

/**
 * Raw-driver reads and writes of a database file beside Room, for the platform tests that check
 * what the engine leaves on disk.
 */
internal object RawDatabaseQueries {

    /** Counts the reserved rows in the file. */
    fun reservedRowCount(path: String): Long =
        withConnection(path) { connection ->
            val statement =
                connection.prepare("SELECT COUNT(*) FROM object_cache WHERE table_name = ?")
            try {
                statement.bindText(1, RESERVED_TABLE_NAME)
                check(statement.step())
                statement.getLong(0)
            } finally {
                statement.close()
            }
        }

    /**
     * Adds a pending write whose wall-clock time is earlier than every other row's, as a device
     * whose clock was set back would, with the raw driver.
     */
    fun insertWriteWithEarlierClock(path: String, id: Long) {
        withConnection(path) { connection ->
            connection.execSQL(
                "INSERT INTO mutation_cache (id, table_name, operation, body_json, filter_json, created_at_ms) " +
                    "VALUES ($id, 'todos', 'INSERT', '[{\"title\":\"late\"}]', NULL, 1)"
            )
        }
    }

    private fun <T> withConnection(path: String, block: (SQLiteConnection) -> T): T {
        val connection = defaultSQLiteDriver().open(path)
        try {
            return block(connection)
        } finally {
            connection.close()
        }
    }
}

/** A rejection as the engine persists one, for the on-disk tests. */
internal val ON_DISK_REJECTION: StoredRejection =
    StoredRejection(
        id = 99,
        table = "todos",
        operation = MutationOperation.INSERT,
        bodyJson = """[{"title":"refused"}]""",
        createdAtMs = 1_727_000_005_000,
        rejectedAtMs = 1_727_000_006_000,
        message = "new row violates row-level security policy",
        serverError =
            StoredServerError(
                status = 403,
                code = "42501",
                message = "new row violates row-level security policy",
            ),
    )

/**
 * Owners for two of the fixture's pending writes; the other two stay unowned, as a write queued
 * with no session model is.
 */
internal val ON_DISK_OWNERS: Map<Long, String> = mapOf(1L to "user:user-a", 2L to "user:user-b")

/**
 * Writes the engine's queue state into this database through its own DAOs, as this build does at
 * run time: [ON_DISK_OWNERS], a rejected list in a reserved row, and a started send of the keyed
 * INSERT 1, recorded in its own row (the fixture has it already). The clock is the fixture's era,
 * older than every fixture row, so an eviction that did not exclude reserved rows would take them
 * first.
 */
internal suspend fun OfflineDatabase.writeReservedRows() {
    val store =
        newTestOfflineStore(
            queryCacheDao = queryCacheDao(),
            cachedFileDao = cachedFileDao(),
            mutationDao = mutationDao(),
            nowMillis = { 1_600_000_000_000 },
        )
    ON_DISK_OWNERS.forEach { (id, owner) -> mutationDao().setOwner(id, owner) }
    store.mutationQueueState.addRejection(ON_DISK_REJECTION)
    store.markSendUnconfirmed(1L)
}

/** Each pending mutation's id and recorded owner, in insertion order. */
internal suspend fun OfflineDatabase.pendingOwners(): List<Pair<Long, String?>> =
    mutationDao().pendingAll().map { row -> row.id to row.owner }

/** What [pendingOwners] reads once [writeReservedRows] ran over the fixture. */
internal val ON_DISK_PENDING_OWNERS: List<Pair<Long, String?>> =
    listOf(1L to "user:user-a", 2L to "user:user-b", 3L to null, 4L to null)

/** These contents with [ON_DISK_OWNERS] recorded, as [writeReservedRows] leaves the fixture. */
internal fun OfflineDatabaseContents.withOwners(): OfflineDatabaseContents =
    copy(
        pendingMutations = pendingMutations.map { row -> row.copy(owner = ON_DISK_OWNERS[row.id]) }
    )
