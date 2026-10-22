// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import java.io.File
import java.io.RandomAccessFile
import okio.Path

internal actual fun markFileUsed(path: Path, epochMillis: Long) {
    File(path.toString()).setLastModified(epochMillis)
}

/**
 * `fsync` on the file's own descriptor, opened for reading so a file that is not there is reported
 * rather than created; what the JDK throws for either is an `IOException`.
 */
internal actual fun syncFileToDisk(path: Path) {
    RandomAccessFile(path.toString(), "r").use { file -> file.fd.sync() }
}

/**
 * Android backs up files through the app's own backup rules, not a per-file attribute, so there is
 * nothing to set here. `Offline`'s documentation gives the rules an app adds.
 */
internal actual fun excludeFromBackup(path: Path): Boolean = false
