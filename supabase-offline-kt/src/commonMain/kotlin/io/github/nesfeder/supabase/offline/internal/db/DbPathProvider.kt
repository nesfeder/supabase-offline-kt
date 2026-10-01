// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import okio.FileSystem
import okio.Path
import okio.SYSTEM

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
