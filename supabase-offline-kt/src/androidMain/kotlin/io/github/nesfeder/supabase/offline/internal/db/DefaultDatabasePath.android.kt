// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import okio.Path
import okio.Path.Companion.toPath

/**
 * The default database file on Android, as an absolute path: [OFFLINE_DATABASE_FILE_NAME] in the
 * app's database directory.
 */
internal fun defaultDatabasePath(context: Context): Path {
    val appContext = context.applicationContext
    val databaseFile = appContext.getDatabasePath(OFFLINE_DATABASE_FILE_NAME)
    return databaseFile.absolutePath.toPath()
}
