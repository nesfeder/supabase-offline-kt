// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor

/**
 * A `supabase_offline.db` exactly as this build leaves it on disk: Room schema version 1, the one
 * schema the library ships, identity hash `d82a2435c50b4595368573f8728a370b`, holding cached
 * queries, a storage row, unsent writes and one synced write not yet retired.
 *
 * [SCHEMA_STATEMENTS] are the `createSql` / index `createSql` / `setupQueries` of the exported
 * `schemas/<database class>/1.json`, copied verbatim with `${TABLE_NAME}` substituted — the file is
 * written with the raw SQLite driver, never through Room, so nothing the library does today can
 * shape it. `OfflineDatabaseSchemaContinuityTest` checks the copy against the exported schema file.
 *
 * The rows use the stored formats the engine reads back: `operation` strings (written as literals,
 * so the file does not follow the library's constants), `filter_json` as the JSON encoding of
 * `FilterDescriptor` (an AND descriptor omits its default `mode`), and booleans as SQLite integers.
 * The unsent writes carry no owner, as a write queued with no session model does.
 */
internal object ShippedDatabaseFixture {

    /** Room schema version of the database. */
    const val VERSION: Long = 1

    /** Room identity hash of schema version 1. */
    const val IDENTITY_HASH: String = "d82a2435c50b4595368573f8728a370b"

    /** Schema version 1, statement for statement, from the exported `1.json`. */
    val SCHEMA_STATEMENTS: List<String> =
        listOf(
            "CREATE TABLE IF NOT EXISTS `object_cache` (`table_name` TEXT NOT NULL, `signature` TEXT NOT NULL, " +
                "`payload` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "`synced_through` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`table_name`, `signature`))",
            "CREATE TABLE IF NOT EXISTS `storage_objects` (`key` TEXT NOT NULL, `bucketId` TEXT NOT NULL, " +
                "`path` TEXT NOT NULL, `localPath` TEXT NOT NULL, `sizeBytes` INTEGER, `eTag` TEXT, " +
                "`lastFetchedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_storage_objects_bucketId_path` ON `storage_objects` " +
                "(`bucketId`, `path`)",
            "CREATE TABLE IF NOT EXISTS `mutation_cache` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`table_name` TEXT NOT NULL, `operation` TEXT NOT NULL, `body_json` TEXT NOT NULL, " +
                "`filter_json` TEXT, `created_at_ms` INTEGER NOT NULL, `synced_at_ms` INTEGER, " +
                "`default_to_null` INTEGER, `on_conflict` TEXT, `ignore_duplicates` INTEGER, " +
                "`owner` TEXT, `sync_sequence` INTEGER, `schema_cache_misses` INTEGER NOT NULL DEFAULT 0, " +
                "`send_unconfirmed` INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS `index_mutation_cache_owner_synced_at_ms` ON `mutation_cache` " +
                "(`owner`, `synced_at_ms`)",
            "CREATE INDEX IF NOT EXISTS `index_mutation_cache_table_name` ON `mutation_cache` " +
                "(`table_name`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'd82a2435c50b4595368573f8728a370b')",
        )

    /** The UPDATE's filter: an AND descriptor, stored without its default `mode`. */
    const val UPDATE_FILTER_JSON: String =
        """{"conditions":[{"column":"id","operatorId":"eq","value":"2"},""" +
            """{"column":"user_id","operatorId":"eq","value":"u-1"}]}"""

    /** The DELETE's filter: an OR descriptor, whose non-default `mode` is stored. */
    const val DELETE_FILTER_JSON: String =
        """{"mode":"OR","conditions":[{"column":"id","operatorId":"in","value":"(7,8)"},""" +
            """{"column":"status","operatorId":"eq","value":"archived"}]}"""

    /** [UPDATE_FILTER_JSON] decoded. */
    val UPDATE_FILTER: FilterDescriptor =
        FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions =
                listOf(
                    FilterDescriptor.Condition(column = "id", operatorId = "eq", value = "2"),
                    FilterDescriptor.Condition(
                        column = "user_id",
                        operatorId = "eq",
                        value = "u-1",
                    ),
                ),
        )

    /** [DELETE_FILTER_JSON] decoded. */
    val DELETE_FILTER: FilterDescriptor =
        FilterDescriptor(
            mode = FilterDescriptor.Mode.OR,
            conditions =
                listOf(
                    FilterDescriptor.Condition(column = "id", operatorId = "in", value = "(7,8)"),
                    FilterDescriptor.Condition(
                        column = "status",
                        operatorId = "eq",
                        value = "archived",
                    ),
                ),
        )

    /**
     * Cached SELECT payloads, one plain and one positional window; the first was fetched after the
     * synced write replayed, and holds it.
     */
    val OBJECT_ROWS: List<ObjectRow> =
        listOf(
            ObjectRow(
                table = "todos",
                signature = "todos?select=*&user_id=eq.u-1",
                payload = """[{"id":1,"title":"Buy milk","done":false}]""",
                updatedAt = 1_727_000_000_000,
                syncedThrough = 1,
            ),
            ObjectRow(
                table = "todos",
                signature = "todos?order=created_at.desc&user_id=eq.u-1|range=0-19",
                payload = """[{"id":2,"title":"Walk the dog","done":false}]""",
                updatedAt = 1_727_000_000_100,
            ),
        )

    /** A downloaded storage object's metadata row, keyed `"$bucket::$path"`. */
    val STORAGE_ROW: StorageRow =
        StorageRow(
            key = "avatars::u-1/me.png",
            bucketId = "avatars",
            path = "u-1/me.png",
            localPath = "/data/user/0/app/files/supabase_offline/storage/avatars/u-1/me.png",
            sizeBytes = 20_480,
            eTag = null,
            lastFetchedEpochMs = 1_727_000_000_500,
        )

    /**
     * Four unsent writes, one per operation, plus one synced write not yet retired. The INSERT's
     * send started and its outcome is unknown.
     */
    val MUTATION_ROWS: List<MutationRow> =
        listOf(
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
                sendUnconfirmed = true,
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
            ),
            MutationRow(
                id = 3,
                table = "todos",
                operation = "UPDATE",
                bodyJson = """{"title":"Walk the dog twice","done":true}""",
                filterJson = UPDATE_FILTER_JSON,
                createdAtMs = 1_727_000_003_000,
                syncedAtMs = null,
                defaultToNull = null,
                onConflict = null,
                ignoreDuplicates = null,
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
                owner = "user:u-1",
                syncSequence = 1,
            ),
        )

    /**
     * Writes the database to [path] with the raw SQLite [driver] — this build's own unless a test
     * names another: schema version 1, every fixture row, and `PRAGMA user_version = 1`.
     */
    fun writeTo(path: String, driver: SQLiteDriver = defaultSQLiteDriver()) {
        write(path, driver, version = VERSION, schemaStatements = SCHEMA_STATEMENTS)
    }

    private fun write(
        path: String,
        driver: SQLiteDriver,
        version: Long,
        schemaStatements: List<String>,
    ) {
        val connection = driver.open(path)
        try {
            schemaStatements.forEach { statement -> connection.execSQL(statement) }
            OBJECT_ROWS.forEach { row ->
                connection.execSQL(
                    "INSERT INTO object_cache (table_name, signature, payload, updated_at, " +
                        "synced_through) VALUES (${sql(row.table)}, ${sql(row.signature)}, " +
                        "${sql(row.payload)}, ${row.updatedAt}, ${row.syncedThrough})"
                )
            }
            connection.execSQL(
                "INSERT INTO storage_objects (`key`, bucketId, path, localPath, sizeBytes, eTag, " +
                    "lastFetchedEpochMs) VALUES (${sql(STORAGE_ROW.key)}, ${sql(STORAGE_ROW.bucketId)}, " +
                    "${sql(STORAGE_ROW.path)}, ${sql(STORAGE_ROW.localPath)}, ${STORAGE_ROW.sizeBytes}, " +
                    "${sql(STORAGE_ROW.eTag)}, ${STORAGE_ROW.lastFetchedEpochMs})"
            )
            MUTATION_ROWS.forEach { row ->
                connection.execSQL(
                    "INSERT INTO mutation_cache (id, table_name, operation, body_json, filter_json, " +
                        "created_at_ms, synced_at_ms, default_to_null, on_conflict, ignore_duplicates, " +
                        "owner, sync_sequence, send_unconfirmed) VALUES (${row.id}, " +
                        "${sql(row.table)}, " +
                        "${sql(row.operation)}, ${sql(row.bodyJson)}, ${sql(row.filterJson)}, " +
                        "${row.createdAtMs}, ${row.syncedAtMs ?: "NULL"}, ${sql(row.defaultToNull)}, " +
                        "${sql(row.onConflict)}, ${sql(row.ignoreDuplicates)}, ${sql(row.owner)}, " +
                        "${row.syncSequence ?: "NULL"}, ${if (row.sendUnconfirmed) 1 else 0})"
                )
            }
            connection.execSQL("PRAGMA user_version = $version")
        } finally {
            connection.close()
        }
    }

    /** Reads `PRAGMA user_version` from the file at [path] with the raw [driver]. */
    fun readUserVersion(path: String, driver: SQLiteDriver = defaultSQLiteDriver()): Long =
        readSingleLong(path, driver, "PRAGMA user_version")

    /** Reads the identity hash Room keeps in `room_master_table`, with the raw [driver]. */
    fun readIdentityHash(path: String, driver: SQLiteDriver = defaultSQLiteDriver()): String? {
        val connection = driver.open(path)
        try {
            val statement =
                connection.prepare("SELECT identity_hash FROM room_master_table WHERE id = 42")
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

    private fun sql(value: String?): String =
        value?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"

    private fun sql(value: Boolean?): String =
        when (value) {
            null -> "NULL"
            true -> "1"
            false -> "0"
        }
}
