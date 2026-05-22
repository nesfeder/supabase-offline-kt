package io.github.nesfeder.supabase.internal.db

import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Build a KMP Room builder for iOS.
 *
 * Uses [IosDbPathProvider] so the Room database path is consistent with
 * the platform-specific path provider used elsewhere.
 */
internal fun getDatabaseBuilder(
    dbFilePath: String
): RoomDatabase.Builder<OfflineDatabase> {
    return Room.databaseBuilder<OfflineDatabase>(
        name = dbFilePath,
    )
}