package io.github.nesfeder.supabase.offline.internal.db

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.NativeSQLiteDriver

/**
 * iOS, system-SQLite build (`-Psupabase.offline.iosSqlite=system`): the offline database opens
 * with the operating system's libsqlite3. The bundled library is not linked in this build at all,
 * which is the only way a native driver really reaches the system SQLite.
 */
internal actual fun defaultSQLiteDriver(): SQLiteDriver = NativeSQLiteDriver()
