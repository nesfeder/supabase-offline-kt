// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import kotlinx.cinterop.ExperimentalForeignApi
import okio.IOException
import okio.Path
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.posix.F_FULLFSYNC
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fsync
import platform.posix.open

@OptIn(ExperimentalForeignApi::class)
internal actual fun markFileUsed(path: Path, epochMillis: Long) {
    NSFileManager.defaultManager.setAttributes(
        attributes =
            mapOf<Any?, Any?>(
                NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
            ),
        ofItemAtPath = path.toString(),
        error = null,
    )
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun excludeFromBackup(path: Path): Boolean =
    NSURL.fileURLWithPath(path.toString())
        .setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)

/**
 * `F_FULLFSYNC`, which asks the drive itself to flush, as SQLite does on Apple platforms; a file
 * system that refuses it gets a plain `fsync`.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun syncFileToDisk(path: Path) {
    val descriptor = open(path.toString(), O_RDONLY)
    if (descriptor < 0) throw IOException("could not open the file to sync it (errno $errno)")
    try {
        if (fcntl(descriptor, F_FULLFSYNC) != 0 && fsync(descriptor) != 0) {
            throw IOException("could not sync the file to disk (errno $errno)")
        }
    } finally {
        close(descriptor)
    }
}
