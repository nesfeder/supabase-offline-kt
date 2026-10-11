// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.db.OFFLINE_DATABASE_FILE_NAME
import io.github.nesfeder.supabase.offline.internal.db.buildOfflineDatabase
import io.github.nesfeder.supabase.offline.internal.db.defaultDatabasePath
import io.github.nesfeder.supabase.offline.internal.db.offlineDatabaseBuilder
import io.github.nesfeder.supabase.offline.internal.network.connectivityManagerNetworkAvailability
import io.github.nesfeder.supabase.offline.internal.storage.PlatformBaseDirectory
import okio.Path.Companion.toPath

internal actual fun openPlatformClient(
    supabase: SupabaseClient,
    config: Offline.Config,
): OfflineClient = openAndroidClient(recordedApplicationContext(), supabase, config)

/**
 * Opens [config]'s client for [supabase] over Room. The database is in [context]'s database
 * directory (`supabase_offline.db`) and downloaded files under `<filesDir>/supabase_offline`, or
 * both in [Offline.Config.dataDirectory] when it is set; a database at the default location moves
 * there with its files.
 *
 * @throws IllegalStateException when another open client already uses the database or the
 *   directory.
 */
internal fun openAndroidClient(
    context: Context,
    supabase: SupabaseClient,
    config: Offline.Config,
): OfflineClient {
    val configuredDirectory = config.dataDirectory?.toPath()
    val defaultLocation =
        DataLocation(defaultDatabasePath(context), PlatformBaseDirectory(context).path)
    return openOfflineClient(
        supabase = supabase,
        config = config,
        databasePath =
            configuredDirectory?.let { it / OFFLINE_DATABASE_FILE_NAME }
                ?: defaultLocation.databasePath,
        dataDirectory = configuredDirectory ?: defaultLocation.dataDirectory,
        openDatabase = { databasePath ->
            buildOfflineDatabase(offlineDatabaseBuilder(context, databasePath.toString()))
        },
        defaultLocation = defaultLocation.takeIf { configuredDirectory != null },
        platformNetworkAvailability = connectivityManagerNetworkAvailability(context),
    )
}
