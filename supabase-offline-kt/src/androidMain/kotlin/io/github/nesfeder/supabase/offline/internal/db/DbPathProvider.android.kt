// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import okio.Path
import okio.Path.Companion.toPath

/**
 * The default database location on Android: [OFFLINE_DATABASE_FILE_NAME] in the app's database
 * directory.
 */
internal class AndroidDbPathProvider(private val context: Context) : DbPathProvider {

    override fun dbPath(): Path {
        val appContext = context.applicationContext
        val dbFile = appContext.getDatabasePath(OFFLINE_DATABASE_FILE_NAME)
        return dbFile.absolutePath.toPath()
    }
}
