// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.internal.db.AndroidDbPathProvider
import io.github.nesfeder.supabase.offline.internal.db.OFFLINE_DATABASE_FILE_NAME
import io.github.nesfeder.supabase.offline.internal.db.getDatabaseBuilder
import io.github.nesfeder.supabase.offline.internal.db.getRoomDatabase
import io.github.nesfeder.supabase.offline.internal.openOfflineClient
import io.github.nesfeder.supabase.offline.internal.storage.PlatformBaseDirectory

/**
 * Creates the offline client for Android over [supabase]. Its database is in the app's database
 * directory (`supabase_offline.db`) and downloaded files under `<filesDir>/supabase_offline`, or
 * both in [SupabaseOfflineConfig.offlineStoragePath] when that is set. Earlier versions kept the
 * database in the default directory whatever the configuration said; the first client created with
 * a configured directory moves it there, unsent writes and all.
 *
 * Create one client per database and keep it: while it is open, creating another on the same
 * database throws. [SupabaseOfflineClient.close] releases it.
 *
 * ### Backups
 *
 * The database holds cached rows and writes not yet sent, and the file cache holds downloaded
 * objects — all of it re-creatable, and one user's. Android's Auto Backup includes both locations
 * by default, so a restore onto a new device brings them back under whoever signs in next. Leave
 * them out in the app's backup rules. For Android 12 and later (`android:dataExtractionRules`):
 * ```xml
 * <data-extraction-rules>
 *     <cloud-backup>
 *         <exclude domain="database" path="supabase_offline.db" />
 *         <exclude domain="database" path="supabase_offline.db-wal" />
 *         <exclude domain="database" path="supabase_offline.db-shm" />
 *         <exclude domain="file" path="supabase_offline/" />
 *     </cloud-backup>
 *     <device-transfer>
 *         <exclude domain="database" path="supabase_offline.db" />
 *         <exclude domain="database" path="supabase_offline.db-wal" />
 *         <exclude domain="database" path="supabase_offline.db-shm" />
 *         <exclude domain="file" path="supabase_offline/" />
 *     </device-transfer>
 * </data-extraction-rules>
 * ```
 *
 * and the same four `<exclude>` lines in a `<full-backup-content>` file for Android 11 and earlier
 * (`android:fullBackupContent`). With [SupabaseOfflineConfig.offlineStoragePath] set, exclude that
 * directory instead.
 *
 * @param context Any context; its application context is used.
 * @param supabase The supabase-kt client to wrap. Install PostgREST, and Storage if you use it.
 * @param config Cache sizes, data directory, logging and connectivity hint.
 * @return The offline client.
 * @throws IllegalStateException when another open client already uses the same database or data
 *   directory.
 */
public fun createSupabaseOfflineClient(
    context: Context,
    supabase: SupabaseClient,
    config: SupabaseOfflineConfig = SupabaseOfflineConfig(),
): SupabaseOfflineClient {
    val configuredDirectory = config.offlineStoragePath
    val defaultDatabasePath = AndroidDbPathProvider(context).dbPath()
    return openOfflineClient(
        supabase = supabase,
        config = config,
        databasePath =
            configuredDirectory?.let { it / OFFLINE_DATABASE_FILE_NAME } ?: defaultDatabasePath,
        dataDirectory = configuredDirectory ?: PlatformBaseDirectory(context).path,
        openDatabase = { databasePath ->
            getRoomDatabase(getDatabaseBuilder(context, databasePath.toString()))
        },
        defaultDatabasePath = defaultDatabasePath.takeIf { configuredDirectory != null },
    )
}
