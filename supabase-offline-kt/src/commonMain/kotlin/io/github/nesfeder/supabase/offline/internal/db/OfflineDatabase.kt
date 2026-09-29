package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO


/**
 * Current schema version of [OfflineDatabase]. Declared here rather than inline so
 * [OFFLINE_DB_MIGRATIONS] can be checked against it — see the migration test, which fails a bump
 * that arrives without the migration step to reach it.
 */
internal const val OFFLINE_DB_VERSION: Int = 2

@ConstructedBy(OfflineDatabaseConstructor::class)
@Database(
    entities = [ObjectRow::class, StorageRow::class, MutationRow::class],
    version = OFFLINE_DB_VERSION,
    exportSchema = true
)
internal abstract class OfflineDatabase : RoomDatabase() {

    abstract fun objectDao(): ObjectDao

    abstract fun storageDao(): StorageDao

    abstract fun mutationDao(): MutationDao
}

// The Room compiler generates the `actual` implementations.
internal expect object OfflineDatabaseConstructor : RoomDatabaseConstructor<OfflineDatabase> {
    override fun initialize(): OfflineDatabase
}

/**
 * Adds [MutationRow.deltaJson] so a queued UPDATE can declare which of its columns carry a
 * *relative* change (see [io.github.nesfeder.supabase.offline.NumericDeltaColumn]).
 *
 * This one is migrated rather than dropped. The object cache is genuinely disposable — it refills
 * on the next read — but `mutation_cache` holds **writes the user made that the server has never
 * seen**, and the destructive fallback would delete them on the update that ships this column.
 * Losing an unsynced edit is the exact failure class delta encoding exists to prevent, so an
 * additive `ALTER TABLE` carries the queue across instead. Every existing row gets `NULL`, which
 * the syncer reads as "no declarations" and replays verbatim, exactly as before.
 */
internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE mutation_cache ADD COLUMN delta_json TEXT")
    }
}

/**
 * Every migration [getRoomDatabase] registers, in ascending order.
 *
 * The list is what makes a version bump safe for `mutation_cache`: Room only takes the destructive
 * fallback when it cannot find a path from the on-disk version to [OFFLINE_DB_VERSION], so a step
 * missing from here is not a compile error or a crash — it is a silent wipe of the user's unsynced
 * writes on the update that ships it. The migration test asserts this covers `1 ..
 * OFFLINE_DB_VERSION` without a gap, so the bump and the step land together or the build fails.
 */
internal val OFFLINE_DB_MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2)

/**
 * The SQLite driver the offline database opens with when none is passed to [getRoomDatabase]: the
 * bundled SQLite everywhere, so every install runs one version. `-Psupabase.offline.iosSqlite=system`
 * swaps the iOS implementation for the system SQLite; the module's build file says why at build time.
 *
 * @return a new driver instance.
 */
internal expect fun defaultSQLiteDriver(): SQLiteDriver

/**
 * Builds the offline database from a platform [builder].
 *
 * @param builder the platform builder, already pointed at the database file.
 * @param driver the SQLite driver to open it with; [defaultSQLiteDriver] unless a caller needs
 *   another. Internal on purpose: the published artifact always uses the default.
 * @return the database, not yet opened; Room opens it on first use.
 */
internal fun getRoomDatabase(
    builder: RoomDatabase.Builder<OfflineDatabase>,
    driver: SQLiteDriver = defaultSQLiteDriver(),
): OfflineDatabase {
    return builder
        .addMigrations(*OFFLINE_DB_MIGRATIONS.toTypedArray())
        // The offline DB is a disposable cache: on any schema-version change with no registered
        // migration path (up or down) drop and rebuild rather than ship one. `exportSchema = true`
        // records each version's schema under the module's `schemas/` dir so a bump is a
        // deliberate, reviewed diff. A bump that touches `mutation_cache` is the exception — that
        // table holds un-synced user writes, so it gets a real migration (see [MIGRATION_1_2]).
        .fallbackToDestructiveMigration(true)
        .setDriver(driver)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
