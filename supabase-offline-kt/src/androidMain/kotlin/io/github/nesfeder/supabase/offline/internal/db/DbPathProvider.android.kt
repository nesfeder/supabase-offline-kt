package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import okio.Path
import okio.Path.Companion.toPath

/**
 * Android implementation of [DbPathProvider] that resolves to the
 * Room database file used for the offline cache.
 */
internal class AndroidDbPathProvider(
    private val context: Context,
) : DbPathProvider {

    override fun dbPath(): Path {
        val appContext = context.applicationContext
        val dbFile = appContext.getDatabasePath(OFFLINE_DATABASE_FILE_NAME)
        return dbFile.absolutePath.toPath()
    }
}
