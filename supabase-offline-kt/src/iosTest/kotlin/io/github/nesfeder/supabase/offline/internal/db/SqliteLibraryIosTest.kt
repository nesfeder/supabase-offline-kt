package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteConnection
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which SQLite the iOS build really runs on, and how Room sets it up.
 *
 * The bundled and system SQLite libraries export the same symbols, so a build that links both runs
 * every driver on whichever the linker picked. This pins that the default driver reaches the
 * library the build is meant to link, and prints the version so a run's log records it.
 */
class SqliteLibraryIosTest {

    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-sqlite-library-${Random.nextLong()}"

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    @Test
    fun the_default_driver_runs_on_the_sqlite_this_build_links() {
        // Given
        FileSystem.SYSTEM.createDirectories(directory)
        val connection = defaultSQLiteDriver().open((directory / "probe.db").toString())

        // When
        val (version, sourceId, compileOptions) = try {
            Triple(
                connection.singleText("SELECT sqlite_version()"),
                connection.singleText("SELECT sqlite_source_id()"),
                connection.allText("PRAGMA compile_options").toSet(),
            )
        } finally {
            connection.close()
        }
        println("SQLite build=${SqliteVariant.NAME} version=$version source=$sourceId")

        // Then
        assertTrue(
            SqliteVariant.isExpectedLibrary(compileOptions),
            "the ${SqliteVariant.NAME} build reached the wrong SQLite ($version, $sourceId)",
        )
    }

    @Test
    fun room_opens_the_offline_database_in_wal_mode() = runTest {
        // Given
        FileSystem.SYSTEM.createDirectories(directory)
        val database = getRoomDatabase(getDatabaseBuilder((directory / "supabase_offline.db").toString()))

        // When
        val journalMode = try {
            database.useReaderConnection { transactor ->
                transactor.usePrepared("PRAGMA journal_mode") { statement ->
                    statement.step()
                    statement.getText(0)
                }
            }
        } finally {
            database.close()
        }

        // Then
        assertEquals("wal", journalMode)
    }

    private fun SQLiteConnection.singleText(sql: String): String {
        val statement = prepare(sql)
        try {
            check(statement.step()) { "$sql returned no row" }
            return statement.getText(0)
        } finally {
            statement.close()
        }
    }

    private fun SQLiteConnection.allText(sql: String): List<String> {
        val statement = prepare(sql)
        try {
            val values = mutableListOf<String>()
            while (statement.step()) values += statement.getText(0)
            return values
        } finally {
            statement.close()
        }
    }
}
