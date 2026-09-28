package io.github.nesfeder.supabase.offline.internal.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore

/**
 * Support for the platform tests that put the engine's reserved rows into a real database file and
 * then read it the way an older build of the engine would.
 *
 * An older build knows nothing of reserved rows. What it runs against `object_cache` are exactly the
 * statements in [PreviousEngineQueries], copied from its DAOs; if those run cleanly over a file that
 * holds reserved rows, and never hand one to a caller that asks for an app table, the older build
 * cannot crash on them.
 */
internal object PreviousEngineQueries {

    /** The older build's `ObjectDao.allForTable`. */
    const val ALL_FOR_TABLE: String = "SELECT * FROM object_cache WHERE table_name = ?"

    /** The older build's `ObjectDao.get`. */
    const val GET: String = "SELECT * FROM object_cache WHERE table_name = ? AND signature = ? LIMIT 1"

    /** The older build's `ObjectDao.totalPayloadBytes`, which counts every row. */
    const val TOTAL_PAYLOAD_BYTES: String = "SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM object_cache"

    /** The older build's `ObjectDao.deleteOldestPayloadRows`, which may evict any row. */
    const val DELETE_OLDEST: String =
        "DELETE FROM object_cache WHERE rowid IN (SELECT rowid FROM object_cache ORDER BY updated_at ASC LIMIT ?)"

    /** The older build's `MutationDao.pendingAll`, ordered by the wall clock. */
    const val PENDING_ALL: String = "SELECT id FROM mutation_cache WHERE synced_at_ms IS NULL ORDER BY created_at_ms ASC"

    /**
     * What the older build's table reads see for [table]: each row's signature, in rowid order.
     *
     * @param path The database file.
     * @param table The app table the older build asks for.
     * @return The signatures of the rows it gets back.
     */
    fun signaturesForTable(path: String, table: String): List<String> = withConnection(path) { connection ->
        val statement = connection.prepare(ALL_FOR_TABLE)
        try {
            statement.bindText(1, table)
            buildList { while (statement.step()) add(statement.getText(1)) }
        } finally {
            statement.close()
        }
    }

    /**
     * Runs the older build's `get` for a reserved row's key and reports whether it found one — which
     * it only ever would if asked for the reserved table by name, as no app does.
     */
    fun getFindsRow(path: String, table: String, signature: String): Boolean = withConnection(path) { connection ->
        val statement = connection.prepare(GET)
        try {
            statement.bindText(1, table)
            statement.bindText(2, signature)
            statement.step()
        } finally {
            statement.close()
        }
    }

    /** Runs the older build's size query. */
    fun totalPayloadBytes(path: String): Long = withConnection(path) { connection ->
        val statement = connection.prepare(TOTAL_PAYLOAD_BYTES)
        try {
            check(statement.step())
            statement.getLong(0)
        } finally {
            statement.close()
        }
    }

    /** Runs the older build's eviction of the [limit] oldest rows. */
    fun deleteOldest(path: String, limit: Int) {
        withConnection(path) { connection ->
            val statement = connection.prepare(DELETE_OLDEST)
            try {
                statement.bindLong(1, limit.toLong())
                statement.step()
            } finally {
                statement.close()
            }
        }
    }

    /** Runs the older build's pending query and returns the ids in its order. */
    fun pendingIds(path: String): List<Long> = withConnection(path) { connection ->
        val statement = connection.prepare(PENDING_ALL)
        try {
            buildList { while (statement.step()) add(statement.getLong(0)) }
        } finally {
            statement.close()
        }
    }

    /** Counts the reserved rows in the file. */
    fun reservedRowCount(path: String): Long = withConnection(path) { connection ->
        val statement = connection.prepare("SELECT COUNT(*) FROM object_cache WHERE table_name = ?")
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
                    "VALUES ($id, 'todos', 'INSERT', '[{\"title\":\"late\"}]', NULL, 1)",
            )
        }
    }

    private fun <T> withConnection(path: String, block: (SQLiteConnection) -> T): T {
        val connection = BundledSQLiteDriver().open(path)
        try {
            return block(connection)
        } finally {
            connection.close()
        }
    }
}

/** A rejection as the engine persists one, for the on-disk tests. */
internal val ON_DISK_REJECTION: StoredRejection = StoredRejection(
    id = 99,
    table = "todos",
    operation = MutationOperation.INSERT,
    bodyJson = """[{"title":"refused"}]""",
    createdAtMs = 1_727_000_005_000,
    rejectedAtMs = 1_727_000_006_000,
    message = "new row violates row-level security policy",
    serverError = StoredServerError(status = 403, code = "42501", message = "new row violates row-level security policy"),
)

/**
 * Writes the engine's reserved rows — a queue owner and a rejected list — into this database through
 * its own DAOs, as this build does at run time. The clock is the shipped fixture's era, older than
 * every fixture row, so an eviction that did not exclude reserved rows would take them first.
 */
internal suspend fun OfflineDatabase.writeReservedRows() {
    val store = newTestOfflineStore(
        objectDao = objectDao(),
        storageDao = storageDao(),
        mutationDao = mutationDao(),
        nowMillis = { 1_600_000_000_000 },
    )
    store.outboxState.setQueueOwner("user:user-a")
    store.outboxState.addRejection(ON_DISK_REJECTION)
}
