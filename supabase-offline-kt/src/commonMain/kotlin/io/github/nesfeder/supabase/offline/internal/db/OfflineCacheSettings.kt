// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * The bounds the store's eviction pass holds the object cache to.
 *
 * @param maxDbSizeBytes the bound on the summed length of cached payloads, not on the database
 *   file, which never shrinks after a `DELETE`; `null` for no bound.
 * @param evictionBatchSize how many of the oldest rows one pass deletes at a time; the pass treats
 *   anything below one as one.
 * @param minRetainedBytes how far below the bound a pass drains, so a burst of writes does not
 *   start a pass on every write; a value that is not positive, or not below the bound, drains to
 *   the bound itself.
 */
internal data class OfflineCacheSettings(
    val maxDbSizeBytes: Long?,
    val evictionBatchSize: Int,
    val minRetainedBytes: Long,
)

/** Where the offline database file lives on this platform. */
internal interface DbPathProvider {
    /** The database file, as an absolute path. */
    fun dbPath(): Path
}

/** The size of the file at [dbPath], in bytes, or `null` when it is missing or unreadable. */
internal fun currentDbSizeBytes(dbPath: Path): Long? = runCatching {
    FileSystem.SYSTEM.metadata(dbPath).size
}
    .getOrNull()
