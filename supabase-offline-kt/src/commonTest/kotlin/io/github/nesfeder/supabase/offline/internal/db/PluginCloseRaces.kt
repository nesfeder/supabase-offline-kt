// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(OfflineInternal::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.offline
import io.github.nesfeder.supabase.offline.testdoubles.FakeResumableCache
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_KEY
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_URL

/** The plugin, closed the way an app closes it: through `SupabaseClient.close()`. */
private class ClosedThroughSupabase(private val plugin: Offline) : Offline by plugin {

    override suspend fun close() {
        plugin.supabaseClient.close()
    }
}

/**
 * Opens a scenario's client through `install(Offline)` on the platform's database, with the
 * scenario server's engine and logging, and closes it through `SupabaseClient.close()`, which
 * closes supabase-kt's HTTP client before the plugin. The close races run over it as over the
 * platform's open path.
 */
internal val openThroughPlugin: OpenOfflineClient = { scenarioClient, config ->
    val supabase =
        createSupabaseClient(supabaseUrl = TEST_SUPABASE_URL, supabaseKey = TEST_SUPABASE_KEY) {
            httpEngine = checkNotNull(scenarioClient.config.networkConfig.httpEngine)
            defaultLogLevel = scenarioClient.config.loggingConfig.defaultLogLevel
            defaultLoggingFactory = scenarioClient.config.loggingConfig.defaultLoggingFactory
            install(Postgrest) { maxRetries = 0 }
            install(Storage) { resumable { cache = FakeResumableCache() } }
            install(Offline) {
                dataDirectory = config.dataDirectory
                cacheSizeBytes = config.cacheSizeBytes
                fileCacheSizeBytes = config.fileCacheSizeBytes
            }
        }
    ClosedThroughSupabase(supabase.offline)
}
