// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.docs

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.ResponseTrust
import io.github.nesfeder.supabase.offline.applyServerRecord
import io.github.nesfeder.supabase.offline.httpStatusOrNull
import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.offline
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

// The documentation site's Reference pages, and the Behaviors pages on eviction and Realtime,
// include the sections of this file between the `--8<--` markers, so every Kotlin sample there
// compiles. Nothing here runs.

/** Every setting of the plugin, at its default unless the comment says otherwise. */
internal fun everySetting(
    supabaseUrl: String,
    supabaseKey: String,
    appConnectivity: Flow<Boolean>,
): SupabaseClient {
    // --8<-- [start:every-setting]
    val supabase =
        createSupabaseClient(supabaseUrl, supabaseKey) {
            install(Auth)
            install(Postgrest)
            install(Storage)
            install(Offline) {
                cacheSizeBytes = 100L * 1024 * 1024 // the default, 100 MiB
                fileCacheSizeBytes = 256L * 1024 * 1024 // the default, 256 MiB
                dataDirectory = null // the default: the platform's place
                networkAvailability =
                    appConnectivity // the default, null, is the platform's monitor
                autoSync = true // the default
                primaryKey("task_tags", "task_id", "tag") // a table whose key is not `id`
                responseTrust = ResponseTrust.SettledSession // the default
                logLevel = null // the default: the client's defaultLogLevel
            }
        }
    // --8<-- [end:every-setting]
    return supabase
}

/** The cache bounds, with eviction off for the files. */
internal fun cacheSizes(supabaseUrl: String, supabaseKey: String): SupabaseClient {
    // --8<-- [start:sizes]
    val supabase =
        createSupabaseClient(supabaseUrl, supabaseKey) {
            install(Postgrest)
            install(Storage)
            install(Offline) {
                cacheSizeBytes = 20L * 1024 * 1024 // cached query results, 20 MiB
                fileCacheSizeBytes = Offline.CACHE_SIZE_UNLIMITED // downloads, never evicted
            }
        }
    // --8<-- [end:sizes]
    return supabase
}

/** The app's own connectivity signal and no automatic replay. */
internal fun ownSignal(
    supabaseUrl: String,
    supabaseKey: String,
    isOnline: Flow<Boolean>,
): SupabaseClient {
    // --8<-- [start:own-signal]
    val supabase =
        createSupabaseClient(supabaseUrl, supabaseKey) {
            install(Postgrest)
            install(Offline) {
                networkAvailability = isOnline // true while online, in place of the monitor
                autoSync = false // the app calls syncPendingMutations() itself
            }
        }
    // --8<-- [end:own-signal]
    return supabase
}

/** A client whose quiet logs still name what the plugin did. */
internal fun quietLogging(supabaseUrl: String, supabaseKey: String): SupabaseClient {
    // --8<-- [start:log-level]
    val supabase =
        createSupabaseClient(supabaseUrl, supabaseKey) {
            defaultLogLevel = LogLevel.WARNING
            install(Postgrest)
            install(Offline) { logLevel = LogLevel.INFO }
        }
    // --8<-- [end:log-level]
    return supabase
}

/** A Realtime record applied to every cached list of its table. */
internal suspend fun applyRealtimeRecords(
    supabase: SupabaseClient,
    record: JsonObject,
    oldRecord: JsonObject,
    todo: Todo,
) {
    // --8<-- [start:apply-records]
    // An INSERT or UPDATE: replaces the cached row with the same key, or joins the lists it matches
    supabase.offline.applyServerRecord("todos", record)

    // A DELETE: its old record carries at least the key
    supabase.offline.applyServerDelete("todos", oldRecord)

    // A typed row, encoded by the client's PostgREST serializer
    supabase.offline.applyServerRecord("todos", todo)
    // --8<-- [end:apply-records]
}

/** What a crash report may say about a failure: its kind and status, never its message. */
internal fun reportableFailure(failure: Throwable): String {
    // --8<-- [start:reportable-failure]
    val reportable = buildString {
        append(failure::class.simpleName) // the type, never the message
        failure.httpStatusOrNull()?.let { append(" HTTP ").append(it) }
        if (failure.isNetworkOrConnectivityFailure()) append(" (connectivity)")
    }
    // --8<-- [end:reportable-failure]
    return reportable
}
