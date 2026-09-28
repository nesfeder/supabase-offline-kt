package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingSqliteConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the one table in the offline database that cannot be thrown away.
 *
 * `object_cache` and `storage_objects` are disposable — they refill on the next read — which is why
 * the builder keeps `fallbackToDestructiveMigration`. `mutation_cache` is not: it holds writes the
 * user made that the server has never seen. Room only takes that destructive fallback when it
 * *cannot find a path* from the on-disk version to [OFFLINE_DB_VERSION], so a schema bump that
 * arrives without its migration step does not fail to build and does not crash — it silently drops
 * every pending mutation on the update that ships it, on every device that had one.
 *
 * These cases fail that bump instead, and pin the shape of the step that carries the queue across.
 */
class OfflineDatabaseMigrationTest {

    @Test
    fun `every schema version has a registered migration path`() {
        // Given / When — the steps the builder registers, in order
        val steps = OFFLINE_DB_MIGRATIONS.map { migration ->
            migration.startVersion to migration.endVersion
        }

        // Then — an unbroken chain from the first version to the current one. A bump with no step
        // to reach it leaves the queue to the destructive fallback, so it fails here instead.
        assertEquals(
            (1 until OFFLINE_DB_VERSION).map { version -> version to version + 1 },
            steps,
            "migrations must cover 1..$OFFLINE_DB_VERSION with no gap",
        )
    }

    @Test
    fun `the delta-column migration only adds a column`() {
        // Given
        val connection = RecordingSqliteConnection()

        // When
        MIGRATION_1_2.migrate(connection)

        // Then — one additive statement. Anything that recreates or empties the table would take
        // the pending mutations with it, which is the failure the migration exists to avoid.
        val statement = connection.statements.singleOrNull()
            ?: error("expected exactly one statement, got ${connection.statements}")
        assertEquals("ALTER TABLE mutation_cache ADD COLUMN delta_json TEXT", statement)
        for (destructive in listOf("DROP", "DELETE", "TRUNCATE", "RENAME")) {
            assertFalse(destructive in statement, "migration must not $destructive")
        }
    }

    @Test
    fun `the added column is nullable so existing queued mutations stay readable`() {
        // Given
        val connection = RecordingSqliteConnection()

        // When
        MIGRATION_1_2.migrate(connection)

        // Then — no NOT NULL and no DEFAULT: every row already in the table gets SQL NULL, which
        // [decodeNumericDeltaColumns] reads as "no declarations" and replays exactly as before.
        // (A NOT NULL add without a default is also the one form of ADD COLUMN SQLite rejects
        // outright, which would strand the upgrade on an exception instead.)
        val statement = connection.statements.single()
        assertFalse("NOT NULL" in statement, "the new column must be nullable")
        assertFalse("DEFAULT" in statement, "existing rows must read back as NULL")
        assertTrue(statement.endsWith("delta_json TEXT"), "column must carry TEXT affinity")
        assertEquals(
            emptyList(),
            decodeNumericDeltaColumns(deltaJson = null, json = OfflineJson),
            "a NULL delta_json must decode to no declarations",
        )
    }
}
