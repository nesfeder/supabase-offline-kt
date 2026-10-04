// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One lock per table between its readers and a change to both its cached payloads and its queued
 * mutations: a reader holds it while it reads the two, the change while it makes them, so no read
 * sees the change half made. Outermost of the cache's locks; a reader takes only leaf locks under
 * it.
 */
internal class TableSnapshotLocks {

    /** Guards [locks]; held only to look one up. */
    private val locksMutex = Mutex()

    private val locks = mutableMapOf<String, Mutex>()

    /** [table]'s lock, made on first use. */
    suspend fun of(table: String): Mutex = locksMutex.withLock { locks.getOrPut(table) { Mutex() } }
}
