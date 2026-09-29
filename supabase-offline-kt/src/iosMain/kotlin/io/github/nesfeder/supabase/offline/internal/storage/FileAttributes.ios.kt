// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.dateWithTimeIntervalSince1970

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
