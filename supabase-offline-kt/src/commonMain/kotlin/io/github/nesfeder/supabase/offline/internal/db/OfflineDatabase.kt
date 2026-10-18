// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The schema version of [OfflineDatabase]: 2, which adds the queue's base columns to 0.1.0's
 * version 1. A later change raises it and adds a migration that keeps `mutation_cache`'s rows.
 */
internal const val OFFLINE_DB_VERSION: Int = 2

/**
 * Version 1 to 2: `mutation_cache` gains `base_version` and `base_json`, both `NULL` for every row
 * already queued, so each replays unguarded as it would have before.
 */
internal val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `mutation_cache` ADD COLUMN `base_version` TEXT")
            connection.execSQL("ALTER TABLE `mutation_cache` ADD COLUMN `base_json` TEXT")
        }
    }

@ConstructedBy(OfflineDatabaseConstructor::class)
@Database(
    entities = [CachedQueryRow::class, CachedFileRow::class, MutationRow::class],
    version = OFFLINE_DB_VERSION,
    exportSchema = true,
)
internal abstract class OfflineDatabase : RoomDatabase() {

    abstract fun queryCacheDao(): QueryCacheDao

    abstract fun cachedFileDao(): CachedFileDao

    abstract fun mutationDao(): MutationDao
}

// The Room compiler generates the `actual` implementations.
internal expect object OfflineDatabaseConstructor : RoomDatabaseConstructor<OfflineDatabase> {
    override fun initialize(): OfflineDatabase
}

/**
 * The SQLite driver the offline database opens with when none is passed to [buildOfflineDatabase]:
 * the bundled SQLite everywhere, so every install runs one version.
 * `-Psupabase.offline.iosSqlite=system` swaps the iOS implementation for the system SQLite; the
 * module's build file says why at build time.
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
 * @param queryDispatcher where Room runs its queries.
 * @return the database, not yet opened; Room opens it on first use.
 */
internal fun buildOfflineDatabase(
    builder: RoomDatabase.Builder<OfflineDatabase>,
    driver: SQLiteDriver = defaultSQLiteDriver(),
    queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
): OfflineDatabase {
    return builder
        .addMigrations(MIGRATION_1_2)
        // A version with no migration path to this one — a file another schema wrote, or a later
        // version after a downgrade — is dropped and rebuilt. `exportSchema` records each version
        // under the module's `schemas/` directory, so a change is a reviewed diff.
        .fallbackToDestructiveMigration(true)
        .setDriver(driver)
        .setQueryCoroutineContext(queryDispatcher)
        .build()
}
