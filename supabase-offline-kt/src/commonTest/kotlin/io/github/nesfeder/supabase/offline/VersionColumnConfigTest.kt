// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * `versionColumn()` and `conflictResolver` in `install(Offline) { … }`: each table's column is kept
 * by its name as `primaryKeys` keeps keys, and a column that cannot be a version is refused.
 */
class VersionColumnConfigTest {

    @Test
    fun version_columns_are_kept_by_table_and_schema_qualified_table() {
        // When
        val config = Offline.createConfig {
            versionColumn("tasks", "updated_at")
            versionColumn("app.tasks", "version")
        }

        // Then
        assertEquals(
            mapOf("tasks" to "updated_at", "app.tasks" to "version"),
            config.versionColumns,
        )
    }

    @Test
    fun the_map_may_be_written_directly() {
        // When
        val config = Offline.createConfig { versionColumns["tasks"] = "updated_at" }

        // Then
        assertEquals("updated_at", config.versionColumns["tasks"])
    }

    @Test
    fun a_blank_version_column_is_refused() {
        assertFailsWith<IllegalArgumentException> {
            Offline.createConfig { versionColumn("tasks", " ") }
        }
        assertFailsWith<IllegalArgumentException> {
            Offline.createConfig { versionColumns["tasks"] = "" }
        }
    }

    @Test
    fun a_version_column_that_is_the_default_key_is_refused() {
        assertFailsWith<IllegalArgumentException> {
            Offline.createConfig { versionColumn("tasks", "id") }
        }
    }

    @Test
    fun a_version_column_among_a_configured_key_is_refused_for_that_table_only() {
        // Then — refused where the column is part of the key
        assertFailsWith<IllegalArgumentException> {
            Offline.createConfig {
                primaryKey("app.memberships", "team_id", "user_id")
                versionColumn("app.memberships", "user_id")
            }
        }

        // Then — kept where the configured key no longer includes `id`, and for another table
        val config = Offline.createConfig {
            primaryKey("app.memberships", "team_id", "user_id")
            versionColumn("app.memberships", "id")
            versionColumn("memberships", "user_id")
        }
        assertEquals("id", config.versionColumns["app.memberships"])
        assertEquals("user_id", config.versionColumns["memberships"])
    }

    @Test
    fun the_conflict_resolver_is_unset_by_default_and_kept_when_set() {
        // Given
        val resolver = ConflictResolver { ConflictResolution.KeepServer }

        // Then
        assertNull(Offline.createConfig {}.conflictResolver)
        assertSame(resolver, Offline.createConfig { conflictResolver = resolver }.conflictResolver)
    }
}
