// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.Room
import androidx.room.RoomDatabase

/** A Room builder for the offline database at [dbFilePath]. */
internal fun getDatabaseBuilder(dbFilePath: String): RoomDatabase.Builder<OfflineDatabase> {
    return Room.databaseBuilder<OfflineDatabase>(name = dbFilePath)
}
