// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/** iOS actual: resolve Documents/supabase_offline */
internal actual class PlatformBaseDirectory {

    actual val path: Path by lazy {
        val urls =
            NSFileManager.defaultManager.URLsForDirectory(
                directory = NSDocumentDirectory,
                inDomains = NSUserDomainMask,
            )
        val docs = (urls.lastOrNull() as? NSURL)?.path ?: NSTemporaryDirectory()
        "$docs/supabase_offline".toPath()
    }
}
