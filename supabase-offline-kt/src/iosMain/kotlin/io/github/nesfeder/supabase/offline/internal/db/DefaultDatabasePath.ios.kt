// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.DataLocation
import io.github.nesfeder.supabase.offline.internal.storage.PLATFORM_DATA_DIRECTORY_NAME
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * The default database file on iOS, as an absolute path: [OFFLINE_DATABASE_FILE_NAME] in the app's
 * `Library/Application Support` directory, which the opener creates when it is missing.
 */
internal fun defaultDatabasePath(): Path =
    "${userDirectoryPath(NSApplicationSupportDirectory, "Application Support")}/$OFFLINE_DATABASE_FILE_NAME"
        .toPath()

/**
 * Where the plugin's data sits in the app's `Documents` directory, a default an earlier build used.
 * The opener moves a database it finds there, with its file cache, to the current default.
 */
internal fun dataLocationInDocuments(): DataLocation {
    val documents = userDirectoryPath(NSDocumentDirectory, "Documents")
    return DataLocation(
        databasePath = "$documents/$OFFLINE_DATABASE_FILE_NAME".toPath(),
        dataDirectory = "$documents/$PLATFORM_DATA_DIRECTORY_NAME".toPath(),
    )
}

/**
 * The absolute path of the app's own [directory], whether or not it exists yet.
 *
 * @param name the directory's name, for the error when iOS gives none.
 */
private fun userDirectoryPath(directory: NSSearchPathDirectory, name: String): String {
    val urls = NSFileManager.defaultManager.URLsForDirectory(directory, NSUserDomainMask)
    return requireNotNull((urls.lastOrNull() as? NSURL)?.path) {
        "Could not resolve the $name directory path"
    }
}
