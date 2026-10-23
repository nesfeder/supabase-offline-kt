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
 * configuration applies as in production, through the same wiring. Each client opened starts empty.
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
    openLocalData = { supabase, config ->
        config.openInMemoryClient(supabase, clock, InMemoryLocalData())
    }
}

/**
 * [keepLocalDataInMemory], over [localData]: the client opened starts with what an earlier client
 * left there, and leaves its own there when it closes. The open fails with an
 * [IllegalStateException] while another client holds [localData], open or still closing.
 *
 * This is the hook behind `inMemory(clock, store)` in the `supabase-offline-kt-testing` artifact,
 * which is the supported entry point.
 *
 * @param clock the time the client sees: when rows were fetched, mutations queued, files used.
 * @param localData the local data the client keeps, shared with the clients before and after it.
 */
@OfflineInternal
@HiddenFromObjC
public fun Offline.Config.keepLocalDataInMemory(clock: Clock, localData: InMemoryLocalData) {
    openLocalData = { supabase, config -> config.openInMemoryClient(supabase, clock, localData) }
}

/**
 * The client [keepLocalDataInMemory] opens: [buildOfflineClient] over [localData], which it claims
 * until its close releases it.
 *
 * @throws IllegalStateException when another client holds [localData].
 */
@OptIn(OfflineInternal::class)
private fun Offline.Config.openInMemoryClient(
    supabase: SupabaseClient,
    clock: Clock,
    localData: InMemoryLocalData,
): OfflineClient {
    val claim = localData.claim(clock)
    try {
        return buildOfflineClient(
            supabase = supabase,
            queryCacheDao = localData.queryCacheDao,
            cachedFileDao = localData.cachedFileDao,
            mutationDao = localData.mutationDao,
            dataDirectory = IN_MEMORY_BASE_DIRECTORY.toPath(),
            prepareFileCacheDirectories = false,
            onClose = claim::release,
            onStillClosing = claim::markStillClosing,
            fileSystem = localData.fileSystem,
            markFileUsed = localData.fileSystem::markUsed,
            // Nothing of an in-memory file system reaches a disk, so there is nothing to write
            // through
            syncFile = {},
            clock = clock,
        )
    } catch (failure: Throwable) {
        claim.release()
        throw failure
    }
}
