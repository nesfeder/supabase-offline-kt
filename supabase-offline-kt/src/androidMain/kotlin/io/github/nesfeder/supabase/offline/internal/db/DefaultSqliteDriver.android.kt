package io.github.nesfeder.supabase.offline.internal.db

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** Android always opens the offline database with the bundled SQLite. */
internal actual fun defaultSQLiteDriver(): SQLiteDriver = BundledSQLiteDriver()
