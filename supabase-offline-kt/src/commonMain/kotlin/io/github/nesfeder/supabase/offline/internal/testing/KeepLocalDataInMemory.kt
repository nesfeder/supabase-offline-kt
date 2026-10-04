// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalObjCRefinement::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.buildOfflineClient
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlin.time.Clock
import okio.Path.Companion.toPath

/**
 * Makes `install(Offline)` keep the plugin's local data in memory: the cache, the mutation queue
 * and the Storage file cache, with no database and nothing on disk. Every other setting of this
 * configuration applies as in production, through the same wiring.
 *
 * This is the hook behind `inMemory()` in the `supabase-offline-kt-testing` artifact, which is the
 * supported entry point. The opt-in gates Kotlin callers; [HiddenFromObjC] keeps it out of the
 * Objective-C export, where an opt-in cannot be enforced.
 *
 * @param clock the time the client sees: when rows were fetched, mutations queued, files used.
 */
@OfflineInternal
@HiddenFromObjC
public fun Offline.Config.keepLocalDataInMemory(clock: Clock) {
    openLocalData = { supabase, config -> config.openInMemoryClient(supabase, clock) }
}

/** The client [keepLocalDataInMemory] opens: [buildOfflineClient] over in-memory storage. */
private fun Offline.Config.openInMemoryClient(
    supabase: SupabaseClient,
    clock: Clock,
): OfflineClient {
    val fileSystem = InMemoryFileSystem(clock)
    return buildOfflineClient(
        supabase = supabase,
        queryCacheDao = InMemoryQueryCacheDao(),
        cachedFileDao = InMemoryCachedFileDao(),
        mutationDao = InMemoryMutationDao(),
        dataDirectory = IN_MEMORY_BASE_DIRECTORY.toPath(),
        prepareFileCacheDirectories = false,
        fileSystem = fileSystem,
        markFileUsed = fileSystem::markUsed,
        clock = clock,
    )
}
