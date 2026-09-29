// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/**
 * A [SQLiteConnection] that executes nothing and records the SQL it is handed.
 *
 * Lets a migration be *inspected* rather than run: the statements a
 * [androidx.room.migration.Migration] issues are the whole of its contract with an existing
 * database, and asserting on them needs no native SQLite and therefore runs on every target the
 * module builds for.
 */
internal class RecordingSqliteConnection : SQLiteConnection {

    /** Every SQL string prepared through this connection, in order. */
    val statements: MutableList<String> = mutableListOf()

    override fun prepare(sql: String): SQLiteStatement {
        statements += sql
        return NoOpStatement
    }

    override fun close() = Unit
}

/**
 * The statement [RecordingSqliteConnection] hands back: stepping it completes immediately with no
 * row, which is all `execSQL` asks of it. Every reader is unreachable — a migration that tried to
 * read one would be doing something this fake cannot honestly stand in for, so they fail loudly
 * rather than return a plausible zero.
 */
private object NoOpStatement : SQLiteStatement {

    override fun bindBlob(index: Int, value: ByteArray) = Unit

    override fun bindDouble(index: Int, value: Double) = Unit

    override fun bindLong(index: Int, value: Long) = Unit

    override fun bindText(index: Int, value: String) = Unit

    override fun bindNull(index: Int) = Unit

    override fun getBlob(index: Int): ByteArray = unsupported()

    override fun getDouble(index: Int): Double = unsupported()

    override fun getLong(index: Int): Long = unsupported()

    override fun getText(index: Int): String = unsupported()

    override fun isNull(index: Int): Boolean = unsupported()

    override fun getColumnCount(): Int = 0

    override fun getColumnName(index: Int): String = unsupported()

    override fun getColumnType(index: Int): Int = unsupported()

    override fun step(): Boolean = false

    override fun reset() = Unit

    override fun clearBindings() = Unit

    override fun close() = Unit

    private fun unsupported(): Nothing =
        error("RecordingSqliteConnection records statements; it cannot answer reads")
}
