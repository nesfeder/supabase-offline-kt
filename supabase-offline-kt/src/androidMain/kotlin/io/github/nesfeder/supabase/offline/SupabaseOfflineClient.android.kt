// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.internal.openAndroidClient
import io.github.nesfeder.supabase.offline.internal.toOfflineConfig

/**
 * Creates the offline client for Android over [supabase], outside its plugins.
 *
 * Install the plugin instead: `install(Offline) { … }` in `createSupabaseClient`, then
 * `supabase.offline`. The plugin needs no [Context], takes the same settings in [Offline.Config],
 * and closes with the client. This factory opens the same database in the same place, so an app
 * moving to the plugin keeps its cache and its unsent writes.
 *
 * The database is in the app's database directory (`supabase_offline.db`) and downloaded files
 * under `<filesDir>/supabase_offline`, or both in [SupabaseOfflineConfig.offlineStoragePath] when
 * that is set. One client per database: while it is open, creating another on the same database
 * throws. [Offline.close] releases it.
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
 * (`android:fullBackupContent`). With a data directory set, exclude that directory instead.
 *
 * @param context Any context; its application context is used.
 * @param supabase The supabase-kt client to wrap. Install PostgREST, and Storage if you use it.
 * @param config Cache sizes, data directory, logging and connectivity hint.
 * @return The offline client.
 * @throws IllegalStateException when another open client already uses the same database or data
 *   directory.
 */
@Deprecated(
    "Install the plugin instead: createSupabaseClient { install(Offline) { … } }, then use " +
        "supabase.offline. The settings move into install(Offline) { … }.",
    ReplaceWith("supabase.offline", "io.github.nesfeder.supabase.offline.offline"),
)
@Suppress("DEPRECATION")
public fun createSupabaseOfflineClient(
    context: Context,
    supabase: SupabaseClient,
    config: SupabaseOfflineConfig = SupabaseOfflineConfig(),
): Offline = openAndroidClient(context, supabase, config.toOfflineConfig())
