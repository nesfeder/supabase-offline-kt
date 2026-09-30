// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/** A Room builder for the offline database at [dbPath], on the application context. */
internal fun getDatabaseBuilder(
    context: Context,
    dbPath: String,
): RoomDatabase.Builder<OfflineDatabase> {
    val appContext = context.applicationContext
    return Room.databaseBuilder(
        appContext,
        OfflineDatabase::class.java,
        dbPath,
    )
}
