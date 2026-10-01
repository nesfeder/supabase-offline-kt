// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.db.BackupExclusionCallback
import io.github.nesfeder.supabase.offline.internal.db.OFFLINE_DATABASE_FILE_NAME
import io.github.nesfeder.supabase.offline.internal.db.buildOfflineDatabase
import io.github.nesfeder.supabase.offline.internal.db.defaultDatabasePath
import io.github.nesfeder.supabase.offline.internal.db.offlineDatabaseBuilder
import io.github.nesfeder.supabase.offline.internal.storage.PlatformBaseDirectory
import io.github.nesfeder.supabase.offline.internal.storage.excludeFromBackup
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Opens [config]'s client over Room. The database is `Documents/supabase_offline.db` and downloaded
 * files live under `Documents/supabase_offline`, or both in [Offline.Config.dataDirectory] when it
 * is set; a database at the default location moves there. Both are excluded from iCloud and device
 * backups.
 */
internal actual fun openPlatformClient(
    supabase: SupabaseClient,
    config: Offline.Config,
): OfflineClient {
    val configuredDirectory = config.dataDirectory?.toPath()
    val dataDirectory = configuredDirectory ?: PlatformBaseDirectory().path
    if (configuredDirectory == null) {
        // The default data directory is the library's own; exclude all of it, whatever it holds
        try {
            FileSystem.SYSTEM.createDirectories(dataDirectory)
            excludeFromBackup(dataDirectory)
        } catch (_: IOException) {
            // Best effort: the cache directories inside it are excluded on their own as well
        }
    }
    val defaultPath = defaultDatabasePath()
    return openOfflineClient(
        supabase = supabase,
        config = config,
        databasePath = configuredDirectory?.let { it / OFFLINE_DATABASE_FILE_NAME } ?: defaultPath,
        dataDirectory = dataDirectory,
        openDatabase = { databasePath ->
            buildOfflineDatabase(
                offlineDatabaseBuilder(databasePath.toString())
                    .addCallback(BackupExclusionCallback(databasePath))
            )
        },
        defaultDatabasePath = defaultPath.takeIf { configuredDirectory != null },
    )
}
