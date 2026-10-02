// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.buildOfflineClient
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import kotlinx.coroutines.CoroutineScope
import okio.Path
import okio.Path.Companion.toPath

/**
 * Makes `install(Offline)` open its client over an in-memory store instead of the platform's
 * database, through the same wiring the platform opener uses, so every setting of this
 * configuration reaches the engine as it does in an app. For tests of the plugin that need no
 * database file.
 *
 * @param store the store the client keeps its data in; by default a new one, with the query-cache
 *   limits this configuration asks for.
 * @param coordinatorScope the parent of the client's own scope; see [newTestOfflineClient].
 * @param dataDirectory where the Storage file cache keeps its files; by default a directory nothing
 *   is written to.
 */
internal fun Offline.Config.openInMemory(
    store: OfflineStore? = null,
    coordinatorScope: CoroutineScope? = null,
    dataDirectory: Path = IN_MEMORY_TEST_DIRECTORY,
) {
    openLocalData = { supabase, config ->
        config.buildOfflineClient(
            supabase = supabase,
            openStore = { limits: QueryCacheLimits ->
                store ?: newTestOfflineStore(queryCacheLimits = limits)
            },
            dataDirectory = dataDirectory,
            prepareFileCacheDirectories = false,
            coordinatorScope = coordinatorScope,
        )
    }
}

/** A data directory nothing is written to: the in-memory store keeps no files. */
private val IN_MEMORY_TEST_DIRECTORY: Path = "/in-memory-plugin-test".toPath()
