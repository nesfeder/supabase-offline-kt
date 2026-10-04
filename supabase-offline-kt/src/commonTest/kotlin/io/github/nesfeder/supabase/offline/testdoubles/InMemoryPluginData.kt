// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.buildOfflineClient
import io.github.nesfeder.supabase.offline.internal.db.CachedFileDao
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import okio.Path
import okio.Path.Companion.toPath

/**
 * Makes `install(Offline)` open its client over in-memory tables through the platform opener's
 * wiring, so every setting applies as it does in an app. Rows are stamped `0L`, as [newTestStore]'s
 * are.
 *
 * @param mutationDao the mutation queue's table; pass one to read what the client queued.
 * @param coordinatorScope the parent of the client's own scope; see [newTestOfflineClient].
 * @param closeWait how long `close()` waits for calls in flight; shorten it with an idle
 *   [coordinatorScope], whose work never ends.
 * @param dataDirectory where the Storage file cache keeps its files; by default a directory nothing
 *   is written to.
 */
internal fun Offline.Config.openInMemory(
    queryCacheDao: QueryCacheDao = FakeQueryCacheDao(),
    cachedFileDao: CachedFileDao = InMemoryCachedFileDao(),
    mutationDao: MutationDao = FakeMutationDao(),
    coordinatorScope: CoroutineScope? = null,
    dataDirectory: Path = IN_MEMORY_TEST_DIRECTORY,
    closeWait: Duration = OfflineClient.CLOSE_WAIT,
) {
    openLocalData = { supabase, config ->
        config.buildOfflineClient(
            supabase = supabase,
            queryCacheDao = queryCacheDao,
            cachedFileDao = cachedFileDao,
            mutationDao = mutationDao,
            rowClock = { 0L },
            dataDirectory = dataDirectory,
            prepareFileCacheDirectories = false,
            coordinatorScope = coordinatorScope,
            closeWait = closeWait,
        )
    }
}

/** A data directory nothing is written to: the in-memory tables keep no files. */
private val IN_MEMORY_TEST_DIRECTORY: Path = "/in-memory-plugin-test".toPath()
