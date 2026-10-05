// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * `Library/Application Support/supabase_offline`, or the same name in the temporary directory when
 * iOS gives no Application Support directory.
 */
internal actual class PlatformBaseDirectory {

    actual val path: Path by lazy {
        val urls =
            NSFileManager.defaultManager.URLsForDirectory(
                directory = NSApplicationSupportDirectory,
                inDomains = NSUserDomainMask,
            )
        val applicationSupport = (urls.lastOrNull() as? NSURL)?.path ?: NSTemporaryDirectory()
        "$applicationSupport/$PLATFORM_DATA_DIRECTORY_NAME".toPath()
    }
}
