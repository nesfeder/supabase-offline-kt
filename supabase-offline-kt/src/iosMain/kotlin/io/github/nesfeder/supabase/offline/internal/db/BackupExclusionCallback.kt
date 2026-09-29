// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import io.github.nesfeder.supabase.offline.internal.storage.excludeFromBackup
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Leaves the offline database out of iCloud and device backups once Room has opened it, when the
 * file, `-wal` and `-shm` first exist. It holds one user's re-creatable data, which a restore must
 * not bring back; the attribute is set on every open, because SQLite re-creates its side files.
 *
 * @param databasePath the database file.
 */
internal class BackupExclusionCallback(private val databasePath: Path) : RoomDatabase.Callback() {

    override fun onOpen(connection: SQLiteConnection) {
        listOf("", "-wal", "-shm", "-journal")
            .map { suffix -> "$databasePath$suffix".toPath() }
            .filter { file -> FileSystem.SYSTEM.exists(file) }
            .forEach { file -> excludeFromBackup(file) }
    }
}
