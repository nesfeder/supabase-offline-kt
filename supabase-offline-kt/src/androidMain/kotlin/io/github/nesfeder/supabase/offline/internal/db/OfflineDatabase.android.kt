package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * KMP Room builder for Android (requires Context).
 *
 * Uses [AndroidDbPathProvider] so the Room database path is consistent with
 * the platform-specific path provider used elsewhere.
 */
internal fun getDatabaseBuilder(
    context: Context,
    dbPath: String
): RoomDatabase.Builder<OfflineDatabase> {
    val appContext = context.applicationContext
    return Room.databaseBuilder(
        appContext,
        OfflineDatabase::class.java,
        dbPath,
    )
}
