// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import java.io.File
import okio.Path

internal actual fun markFileUsed(path: Path, epochMillis: Long) {
    File(path.toString()).setLastModified(epochMillis)
}

/**
 * Android backs up files through the app's own backup rules, not a per-file attribute, so there is
 * nothing to set here. `Offline`'s documentation gives the rules an app adds.
 */
internal actual fun excludeFromBackup(path: Path): Boolean = false
