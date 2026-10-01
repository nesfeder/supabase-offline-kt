// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.networkAvailabilityHint
import io.github.nesfeder.supabase.offline.internal.newEngineScope
import io.github.nesfeder.supabase.offline.internal.primaryKeysByTable
import io.github.nesfeder.supabase.offline.internal.responseTrustPolicy
import kotlinx.coroutines.CoroutineScope
import okio.Path
import okio.Path.Companion.toPath

/**
 * Makes `install(Offline)` open its client over [store], an in-memory store, instead of the
 * platform's database, wired from this configuration as the platform opener wires it. For tests of
 * the plugin that need no database files.
 *
 * @param store the store the client keeps its data in.
 * @param coordinatorScope the parent of the client's own scope; see [newTestOfflineClient].
 */
internal fun Offline.Config.openInMemory(
    store: OfflineStore = newTestOfflineStore(),
    coordinatorScope: CoroutineScope? = null,
) {
    openLocalData = { supabase, config ->
        val engineLogger = EngineLogger(config.logger, config.minLogLevel)
        OfflineClient(
            supabaseClient = supabase,
            store = store,
            baseDirectoryProvider = { IN_MEMORY_TEST_DIRECTORY },
            engineLogger = engineLogger,
            networkAvailability = config.networkAvailabilityHint(),
            coordinatorScope = coordinatorScope ?: newEngineScope(engineLogger),
            primaryKeys = config.primaryKeysByTable(),
            responseTrust = config.responseTrustPolicy(),
            config = config,
        )
    }
}

/** A data directory nothing is written to: the in-memory store keeps no files. */
private val IN_MEMORY_TEST_DIRECTORY: Path = "/in-memory-plugin-test".toPath()
