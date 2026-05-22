package io.github.nesfeder.supabase.internal.db

import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * iOS implementation of [DbPathProvider] that resolves to the same
 * database file path used by the Room builder.
 */
internal class IosDbPathProvider : DbPathProvider {

    @OptIn(ExperimentalForeignApi::class)
    override fun dbPath(): Path {
        val url = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        )
        val basePath = requireNotNull(url?.path) { "Could not resolve Documents directory path" }
        return ("$basePath/supabase_offline.db").toPath()
    }
}
