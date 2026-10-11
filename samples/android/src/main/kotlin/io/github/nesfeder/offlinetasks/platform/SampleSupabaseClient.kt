// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.platform

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.ResponseTrust
import kotlinx.coroutines.flow.Flow

/** The most the cached query results may hold: 100 MiB, the library's default. */
const val QUERY_CACHE_SIZE_BYTES: Long = 100L * 1024 * 1024

/** The most the downloaded photos may hold: 256 MiB, the library's default. */
const val PHOTO_CACHE_SIZE_BYTES: Long = 256L * 1024 * 1024

/**
 * The one [SupabaseClient] of the app, for the project in `local.properties`, or null when its URL
 * or key is missing (as on CI).
 *
 * @param networkAvailability the app's own connectivity signal, `true` while online.
 * @param debugBuild whether this is a debug build, which logs the plugin at `INFO`.
 */
fun createSampleSupabaseClient(
    url: String,
    publishableKey: String,
    networkAvailability: Flow<Boolean>,
    debugBuild: Boolean,
): SupabaseClient? {
    if (url.isBlank() || publishableKey.isBlank()) return null
    // --8<-- [start:install]
    return createSupabaseClient(supabaseUrl = url, supabaseKey = publishableKey) {
        // Warnings and errors only: request logs name filter values.
        defaultLogLevel = LogLevel.WARNING
        install(Auth) // the anonymous session; the cache belongs to one user at a time
        install(Postgrest) // required
        install(Storage) // for offline.storage
        install(Realtime) // for Live updates
        // Last, so no plugin after it can fail once it has claimed its database.
        install(Offline) { writeEverySetting(networkAvailability, debugBuild) }
    }
    // --8<-- [end:install]
}

// --8<-- [start:every-setting]
/**
 * Every setting, each set to the default it would take anyway but the two the app supplies: remove
 * any line and the plugin behaves the same.
 */
private fun Offline.Config.writeEverySetting(
    networkAvailability: Flow<Boolean>,
    debugBuild: Boolean,
) {
    // --8<-- [start:sizes]
    // Cached query results, by the length of their answers: past it, the longest-fetched go.
    cacheSizeBytes =
        QUERY_CACHE_SIZE_BYTES // the default; Offline.CACHE_SIZE_UNLIMITED never evicts
    // Downloaded photos: past it, the least recently used go, never one in use.
    fileCacheSizeBytes = PHOTO_CACHE_SIZE_BYTES // the default
    // --8<-- [end:sizes]
    // --8<-- [start:data-directory]
    // Where the database and the downloaded files live; null is the app's own places.
    dataDirectory = null // the default: the database directory, and filesDir/supabase_offline
    // --8<-- [end:data-directory]
    // --8<-- [start:own-signal]
    // The app's own connectivity signal in place of the plugin's monitor. Both watch Android's
    // validated network; null would use the plugin's.
    this.networkAvailability = networkAvailability
    // Send queued writes when the session settles and when the network comes back.
    autoSync = true // the default; false leaves them to Sync now
    // --8<-- [end:own-signal]
    // --8<-- [start:primary-keys]
    // How the cache tells rows apart: each table's key columns. `id` is the default for both.
    primaryKey(Tables.TASKS, "id")
    primaryKeys[Tables.TASK_PHOTOS] = listOf("id") // the same thing, as a map entry
    // --8<-- [end:primary-keys]
    // --8<-- [start:response-trust]
    // Which answers may replace the cache: those sent and answered under one settled session.
    responseTrust = ResponseTrust.SettledSession // the default
    // --8<-- [end:response-trust]
    // --8<-- [start:log-level]
    // The plugin's least severe level; null takes defaultLogLevel above. INFO never logs a row.
    logLevel = if (debugBuild) LogLevel.INFO else null
    // --8<-- [end:log-level]
}
// --8<-- [end:every-setting]
