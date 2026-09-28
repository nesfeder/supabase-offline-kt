package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import io.github.nesfeder.supabase.offline.internal.storage.excludeFromBackup
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Leaves the offline database out of iCloud and device backups once Room has opened it — the
 * first moment the file, and in WAL mode its `-wal` and `-shm` companions, exist.
 *
 * The database holds cached rows and writes not yet sent: re-creatable data, which Apple's storage
 * guidelines keep out of backups, and one user's, which a restore onto another device must not
 * bring back under whoever signs in next. The exclusion is a file attribute, so it is set again on
 * every open: SQLite deletes and re-creates its companion files.
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
