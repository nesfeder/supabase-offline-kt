// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * The default database location on iOS: [OFFLINE_DATABASE_FILE_NAME] in the app's Documents
 * directory.
 */
internal class IosDbPathProvider : DbPathProvider {

    @OptIn(ExperimentalForeignApi::class)
    override fun dbPath(): Path {
        val url =
            NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = false,
                error = null,
            )
        val basePath = requireNotNull(url?.path) { "Could not resolve Documents directory path" }
        return ("$basePath/$OFFLINE_DATABASE_FILE_NAME").toPath()
    }
}
