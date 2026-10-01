// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The schema version of [OfflineDatabase]: 1, the one schema 0.1.0 ships. A later change of any
 * table, column or index raises it and adds a migration that keeps `mutation_cache`'s rows.
 */
internal const val OFFLINE_DB_VERSION: Int = 1

@ConstructedBy(OfflineDatabaseConstructor::class)
@Database(
    entities = [ObjectRow::class, StorageRow::class, MutationRow::class],
    version = OFFLINE_DB_VERSION,
    exportSchema = true,
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
 * The SQLite driver the offline database opens with when none is passed to [getRoomDatabase]: the
 * bundled SQLite everywhere, so every install runs one version.
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
 * @return the database, not yet opened; Room opens it on first use.
 */
internal fun getRoomDatabase(
    builder: RoomDatabase.Builder<OfflineDatabase>,
    driver: SQLiteDriver = defaultSQLiteDriver(),
): OfflineDatabase {
    return builder
        // A version with no migration path to this one — a file another schema wrote, or a later
        // version after a downgrade — is dropped and rebuilt. `exportSchema` records each version
        // under the module's `schemas/` directory, so a change is a reviewed diff.
        .fallbackToDestructiveMigration(true)
        .setDriver(driver)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
