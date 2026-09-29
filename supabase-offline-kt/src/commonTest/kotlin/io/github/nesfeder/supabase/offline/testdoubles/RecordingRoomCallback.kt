// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection

/**
 * [RoomDatabase.Callback] that records which lifecycle hooks Room fired while opening a database,
 * so a test can prove an existing file was opened as-is: never created, never destructively
 * migrated.
 */
internal class RecordingRoomCallback : RoomDatabase.Callback() {

    /** Times Room created the database from scratch. */
    var creates: Int = 0
        private set

    /** Times Room dropped and recreated the database because no migration path existed. */
    var destructiveMigrations: Int = 0
        private set

    /** Times Room finished opening the database. */
    var opens: Int = 0
        private set

    override fun onCreate(connection: SQLiteConnection) {
        creates++
    }

    override fun onDestructiveMigration(connection: SQLiteConnection) {
        destructiveMigrations++
    }

    override fun onOpen(connection: SQLiteConnection) {
        opens++
    }
}
