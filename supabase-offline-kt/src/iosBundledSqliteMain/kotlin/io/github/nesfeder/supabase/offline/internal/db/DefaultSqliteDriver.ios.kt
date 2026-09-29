// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * iOS, bundled-SQLite build (the default): the offline database opens with the SQLite compiled into
 * this library. The system-SQLite build compiles a different file in this one's place.
 */
internal actual fun defaultSQLiteDriver(): SQLiteDriver = BundledSQLiteDriver()
