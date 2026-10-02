// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One lock per table, held across every read-modify-write of the table's cached payloads, so two
 * writers never interleave a snapshot and a write-back and lose one's rows. [QueryCache] owns them;
 * the patch and the mutation queue's retirement take the same ones. Per table, so one table's
 * costly patch never holds up another's writes.
 */
internal class TableWriteLocks {

    /** Guards [locks]; held only to look one up. */
    private val locksMutex = Mutex()

    private val locks = mutableMapOf<String, Mutex>()

    /** [table]'s lock, made on first use; hold it with `withLock` around the whole write. */
    suspend fun of(table: String): Mutex = locksMutex.withLock { locks.getOrPut(table) { Mutex() } }
}
