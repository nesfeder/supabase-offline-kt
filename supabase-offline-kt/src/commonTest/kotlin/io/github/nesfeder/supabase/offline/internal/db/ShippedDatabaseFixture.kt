package io.github.nesfeder.supabase.offline.internal.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import io.github.nesfeder.supabase.offline.NumericDeltaColumn
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor

/**
 * A `supabase_offline.db` exactly as an install of the engine's previous home left it on disk:
 * Room schema version 2, identity hash `5a519d822fb8c3ead14608102c6846d9`, holding cached queries,
 * a storage row and unsent writes.
 *
 * [SCHEMA_STATEMENTS] are the `createSql` / index `createSql` / `setupQueries` of that release's
 * exported `schemas/<database class>/2.json`, copied verbatim with `${TABLE_NAME}` substituted —
 * the file is written with the raw SQLite driver, never through Room, so nothing the library does
 * today can shape it. `OfflineDatabaseSchemaContinuityTest` checks the copy against the exported
 * schema file.
 *
 * [writeVersion1To] writes the same rows at schema version 1 (from `1.json`, before `delta_json`
 * existed), for the one migration an older install still has to take.
 *
 * The rows use the stored formats the engine reads back: `operation` strings (written as
 * literals, so the file does not follow the library's constants), `filter_json` as the
 * JSON encoding of `FilterDescriptor` (an AND descriptor omits its default `mode`), `delta_json` as a
 * JSON list of `NumericDeltaColumn` (a null `minValue` is omitted), and booleans as SQLite integers.
 */
internal object ShippedDatabaseFixture {

    /** Room schema version of the shipped database. */
    const val VERSION: Long = 2

    /** Room identity hash of schema version 2. */
    const val IDENTITY_HASH: String = "5a519d822fb8c3ead14608102c6846d9"

    /** Schema version 2, statement for statement, from the exported `2.json`. */
    val SCHEMA_STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `object_cache` (`table_name` TEXT NOT NULL, `signature` TEXT NOT NULL, " +
            "`payload` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`table_name`, `signature`))",
        "CREATE TABLE IF NOT EXISTS `storage_objects` (`key` TEXT NOT NULL, `bucketId` TEXT NOT NULL, " +
            "`path` TEXT NOT NULL, `localPath` TEXT NOT NULL, `sizeBytes` INTEGER, `eTag` TEXT, " +
            "`lastFetchedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`key`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_storage_objects_bucketId_path` ON `storage_objects` " +
            "(`bucketId`, `path`)",
        "CREATE TABLE IF NOT EXISTS `mutation_cache` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`table_name` TEXT NOT NULL, `operation` TEXT NOT NULL, `body_json` TEXT NOT NULL, " +
            "`filter_json` TEXT, `created_at_ms` INTEGER NOT NULL, `synced_at_ms` INTEGER, " +
            "`default_to_null` INTEGER, `on_conflict` TEXT, `ignore_duplicates` INTEGER, `delta_json` TEXT)",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '5a519d822fb8c3ead14608102c6846d9')",
    )

    /** Room identity hash of schema version 1. */
    const val VERSION_1_IDENTITY_HASH: String = "da81d9c688873737d807333f92e2e56d"

    /** Schema version 1, statement for statement, from the exported `1.json`. */
    val VERSION_1_SCHEMA_STATEMENTS: List<String> = listOf(
        SCHEMA_STATEMENTS[0],
        SCHEMA_STATEMENTS[1],
        SCHEMA_STATEMENTS[2],
        "CREATE TABLE IF NOT EXISTS `mutation_cache` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`table_name` TEXT NOT NULL, `operation` TEXT NOT NULL, `body_json` TEXT NOT NULL, " +
            "`filter_json` TEXT, `created_at_ms` INTEGER NOT NULL, `synced_at_ms` INTEGER, " +
            "`default_to_null` INTEGER, `on_conflict` TEXT, `ignore_duplicates` INTEGER)",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'da81d9c688873737d807333f92e2e56d')",
    )

    /** The UPDATE's filter: an AND descriptor, stored without its default `mode`. */
    const val UPDATE_FILTER_JSON: String =
        """{"conditions":[{"column":"id","operatorId":"eq","value":"2"},""" +
            """{"column":"user_id","operatorId":"eq","value":"u-1"}]}"""

    /** The DELETE's filter: an OR descriptor, whose non-default `mode` is stored. */
    const val DELETE_FILTER_JSON: String =
        """{"mode":"OR","conditions":[{"column":"id","operatorId":"in","value":"(7,8)"},""" +
            """{"column":"status","operatorId":"eq","value":"archived"}]}"""

    /** The UPDATE's delta declarations; the second has no floor, so its `minValue` is omitted. */
    const val UPDATE_DELTA_JSON: String =
        """[{"column":"done_count","snapshotValue":4.0,"minValue":0.0},""" +
            """{"column":"minutes_spent","snapshotValue":30.0}]"""

    /** [UPDATE_FILTER_JSON] decoded. */
    val UPDATE_FILTER: FilterDescriptor = FilterDescriptor(
        mode = FilterDescriptor.Mode.AND,
        conditions = listOf(
            FilterDescriptor.Condition(column = "id", operatorId = "eq", value = "2"),
            FilterDescriptor.Condition(column = "user_id", operatorId = "eq", value = "u-1"),
        ),
    )

    /** [DELETE_FILTER_JSON] decoded. */
    val DELETE_FILTER: FilterDescriptor = FilterDescriptor(
        mode = FilterDescriptor.Mode.OR,
        conditions = listOf(
            FilterDescriptor.Condition(column = "id", operatorId = "in", value = "(7,8)"),
            FilterDescriptor.Condition(column = "status", operatorId = "eq", value = "archived"),
        ),
    )

    /** [UPDATE_DELTA_JSON] decoded. */
    val UPDATE_DELTAS: List<NumericDeltaColumn> = listOf(
        NumericDeltaColumn(column = "done_count", snapshotValue = 4.0, minValue = 0.0),
        NumericDeltaColumn(column = "minutes_spent", snapshotValue = 30.0),
    )

    /** Cached SELECT payloads, one plain and one positional window. */
    val OBJECT_ROWS: List<ObjectRow> = listOf(
        ObjectRow(
            table = "todos",
            signature = "todos?select=*&user_id=eq.u-1",
            payload = """[{"id":1,"title":"Buy milk","done":false}]""",
            updatedAt = 1_727_000_000_000,
        ),
        ObjectRow(
            table = "todos",
            signature = "todos?order=created_at.desc&user_id=eq.u-1|range=0-19",
            payload = """[{"id":2,"title":"Walk the dog","done":false}]""",
            updatedAt = 1_727_000_000_100,
        ),
    )

    /** A downloaded storage object's metadata row, keyed `"$bucket::$path"`. */
    val STORAGE_ROW: StorageRow = StorageRow(
        key = "avatars::u-1/me.png",
        bucketId = "avatars",
        path = "u-1/me.png",
        localPath = "/data/user/0/app/files/supabase_offline/storage/avatars/u-1/me.png",
        sizeBytes = 20_480,
        eTag = null,
        lastFetchedEpochMs = 1_727_000_000_500,
    )

    /** Four unsent writes, one per operation, plus one synced write not yet retired. */
    val MUTATION_ROWS: List<MutationRow> = listOf(
        MutationRow(
            id = 1,
            table = "todos",
            operation = "INSERT",
            bodyJson = """[{"id":3,"title":"Water the plants"}]""",
            filterJson = null,
            createdAtMs = 1_727_000_001_000,
            syncedAtMs = null,
            defaultToNull = true,
            onConflict = null,
            ignoreDuplicates = null,
            deltaJson = null,
        ),
        MutationRow(
            id = 2,
            table = "list_members",
            operation = "UPSERT",
            bodyJson = """[{"list_id":"l-1","user_id":"u-2","role":"editor"}]""",
            filterJson = null,
            createdAtMs = 1_727_000_002_000,
            syncedAtMs = null,
            defaultToNull = false,
            onConflict = "list_id,user_id",
            ignoreDuplicates = true,
            deltaJson = null,
        ),
        MutationRow(
            id = 3,
            table = "todos",
            operation = "UPDATE",
            bodyJson = """{"title":"Walk the dog twice","done_count":5,"minutes_spent":45}""",
            filterJson = UPDATE_FILTER_JSON,
            createdAtMs = 1_727_000_003_000,
            syncedAtMs = null,
            defaultToNull = null,
            onConflict = null,
            ignoreDuplicates = null,
            deltaJson = UPDATE_DELTA_JSON,
        ),
        MutationRow(
            id = 4,
            table = "todos",
            operation = "DELETE",
            bodyJson = "{}",
            filterJson = DELETE_FILTER_JSON,
            createdAtMs = 1_727_000_004_000,
            syncedAtMs = null,
            defaultToNull = null,
            onConflict = null,
            ignoreDuplicates = null,
            deltaJson = null,
        ),
        MutationRow(
            id = 5,
            table = "todos",
            operation = "INSERT",
            bodyJson = """[{"id":4,"title":"Already sent"}]""",
            filterJson = null,
            createdAtMs = 1_727_000_000_900,
            syncedAtMs = 1_727_000_000_950,
            defaultToNull = true,
            onConflict = null,
            ignoreDuplicates = null,
            deltaJson = null,
        ),
    )

    /** [MUTATION_ROWS] as a schema version 1 install holds them: no `delta_json` column yet. */
    val VERSION_1_MUTATION_ROWS: List<MutationRow> = MUTATION_ROWS.map { row -> row.copy(deltaJson = null) }

    /**
     * Writes the shipped database to [path] with the raw SQLite [driver]: schema version 2, every
     * fixture row, and `PRAGMA user_version = 2`, which is how Room records the schema version.
     *
     * The driver is a parameter so the same file can be written by the bundled SQLite and by the
     * system one; this object never names a driver itself, so a test binary that must not link the
     * bundled SQLite can still use it.
     */
    fun writeTo(path: String, driver: SQLiteDriver) {
        write(path, driver, version = VERSION, schemaStatements = SCHEMA_STATEMENTS, mutationRows = MUTATION_ROWS)
    }

    /**
     * Writes the same database at schema version 1 — the `1.json` schema, the rows without
     * `delta_json`, and `PRAGMA user_version = 1` — as an install that never ran a version-2 build
     * holds it.
     */
    fun writeVersion1To(path: String, driver: SQLiteDriver) {
        write(
            path,
            driver,
            version = 1,
            schemaStatements = VERSION_1_SCHEMA_STATEMENTS,
            mutationRows = VERSION_1_MUTATION_ROWS,
        )
    }

    private fun write(
        path: String,
        driver: SQLiteDriver,
        version: Long,
        schemaStatements: List<String>,
        mutationRows: List<MutationRow>,
    ) {
        val hasDeltaColumn = version >= VERSION
        val connection = driver.open(path)
        try {
            schemaStatements.forEach { statement -> connection.execSQL(statement) }
            OBJECT_ROWS.forEach { row ->
                connection.execSQL(
                    "INSERT INTO object_cache (table_name, signature, payload, updated_at) VALUES (" +
                        "${sql(row.table)}, ${sql(row.signature)}, ${sql(row.payload)}, ${row.updatedAt})",
                )
            }
            connection.execSQL(
                "INSERT INTO storage_objects (`key`, bucketId, path, localPath, sizeBytes, eTag, " +
                    "lastFetchedEpochMs) VALUES (${sql(STORAGE_ROW.key)}, ${sql(STORAGE_ROW.bucketId)}, " +
                    "${sql(STORAGE_ROW.path)}, ${sql(STORAGE_ROW.localPath)}, ${STORAGE_ROW.sizeBytes}, " +
                    "${sql(STORAGE_ROW.eTag)}, ${STORAGE_ROW.lastFetchedEpochMs})",
            )
            mutationRows.forEach { row ->
                val deltaColumn = if (hasDeltaColumn) ", delta_json" else ""
                val deltaValue = if (hasDeltaColumn) ", ${sql(row.deltaJson)}" else ""
                connection.execSQL(
                    "INSERT INTO mutation_cache (id, table_name, operation, body_json, filter_json, " +
                        "created_at_ms, synced_at_ms, default_to_null, on_conflict, ignore_duplicates" +
                        "$deltaColumn) VALUES (${row.id}, ${sql(row.table)}, ${sql(row.operation)}, " +
                        "${sql(row.bodyJson)}, ${sql(row.filterJson)}, ${row.createdAtMs}, " +
                        "${row.syncedAtMs ?: "NULL"}, ${sql(row.defaultToNull)}, ${sql(row.onConflict)}, " +
                        "${sql(row.ignoreDuplicates)}$deltaValue)",
                )
            }
            connection.execSQL("PRAGMA user_version = $version")
        } finally {
            connection.close()
        }
    }

    /** Reads `PRAGMA user_version` from the file at [path] with the raw [driver]. */
    fun readUserVersion(path: String, driver: SQLiteDriver): Long =
        readSingleLong(path, driver, "PRAGMA user_version")

    /** Reads the identity hash Room keeps in `room_master_table`, with the raw [driver]. */
    fun readIdentityHash(path: String, driver: SQLiteDriver): String? {
        val connection = driver.open(path)
        try {
            val statement = connection.prepare("SELECT identity_hash FROM room_master_table WHERE id = 42")
            try {
                return if (statement.step()) statement.getText(0) else null
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
    }

    private fun readSingleLong(path: String, driver: SQLiteDriver, query: String): Long {
        val connection: SQLiteConnection = driver.open(path)
        try {
            val statement = connection.prepare(query)
            try {
                check(statement.step()) { "$query returned no row" }
                return statement.getLong(0)
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
    }

    private fun sql(value: String?): String = value?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"

    private fun sql(value: Boolean?): String = when (value) {
        null -> "NULL"
        true -> "1"
        false -> "0"
    }
}
