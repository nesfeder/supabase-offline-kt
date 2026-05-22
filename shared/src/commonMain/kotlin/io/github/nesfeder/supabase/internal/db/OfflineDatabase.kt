package io.github.nesfeder.supabase.internal.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO


@ConstructedBy(OfflineDatabaseConstructor::class)
@Database(
    entities = [ObjectRow::class, StorageRow::class, MutationRow::class],
    version = 1,
    exportSchema = false
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

internal fun getRoomDatabase(
    builder: RoomDatabase.Builder<OfflineDatabase>
): OfflineDatabase {
    return builder
        .addMigrations()
        .fallbackToDestructiveMigrationOnDowngrade(true)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
