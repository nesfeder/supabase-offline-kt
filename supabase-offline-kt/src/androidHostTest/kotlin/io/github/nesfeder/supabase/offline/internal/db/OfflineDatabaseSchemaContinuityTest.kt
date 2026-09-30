// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pins the exported Room schema that existing installs are on.
 *
 * Every device that ran the engine before it moved here holds `supabase_offline.db` at schema
 * version 2 with identity hash `5a519d822fb8c3ead14608102c6846d9`, and that file holds users'
 * unsent writes. Any change to a table, column, index or the version number changes the hash, and
 * Room then takes the destructive fallback on the device — deleting those writes. The exported
 * schema is the record of what the build compiles; these cases fail the build if it drifts.
 *
 * Reads the checked-in `schemas/` directory (its location is passed in by the build), so it runs on
 * the JVM host only.
 */
class OfflineDatabaseSchemaContinuityTest {

    /**
     * The exported schemas, found through the property the build sets rather than the working
     * directory, which differs between Gradle and an IDE.
     */
    private val schemaDirectory =
        File(
            checkNotNull(System.getProperty(SCHEMA_DIRECTORY_PROPERTY)) {
                "$SCHEMA_DIRECTORY_PROPERTY is not set: run this test through Gradle, which " +
                    "passes the checked-in schemas directory"
            },
            "io.github.nesfeder.supabase.offline.internal.db.OfflineDatabase",
        )

    private fun exportedSchema(version: Int): JsonObject {
        val file = File(schemaDirectory, "$version.json")
        assertTrue(file.isFile, "missing exported schema ${file.absolutePath}")
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
    }

    @Test
    fun exported_schema_2_has_the_shipped_identity_hash() {
        // Given
        val schema = exportedSchema(version = 2)

        // When
        val version = schema.getValue("version").jsonPrimitive.int
        val identityHash = schema.getValue("identityHash").jsonPrimitive.content

        // Then
        assertEquals(2, version)
        assertEquals(ShippedDatabaseFixture.IDENTITY_HASH, identityHash)
    }

    @Test
    fun the_current_schema_version_is_the_shipped_one() {
        // Given / When — the version the database class compiles with
        val current = OFFLINE_DB_VERSION

        // Then — raising it is a deliberate schema change that needs its own migration
        assertEquals(ShippedDatabaseFixture.VERSION.toInt(), current)
        assertTrue(
            File(schemaDirectory, "$current.json").isFile,
            "the current version must be exported",
        )
    }

    @Test
    fun exported_schema_1_is_kept_for_the_1_to_2_migration() {
        // Given
        val schema = exportedSchema(version = 1)

        // When
        val version = schema.getValue("version").jsonPrimitive.int
        val identityHash = schema.getValue("identityHash").jsonPrimitive.content

        // Then
        assertEquals(1, version)
        assertEquals(ShippedDatabaseFixture.VERSION_1_IDENTITY_HASH, identityHash)
    }

    @Test
    fun the_open_in_place_fixture_is_the_exported_schema_2_verbatim() {
        // Given
        val schema = exportedSchema(version = 2)

        // When — every statement Room would run to create schema version 2
        val statements = creationStatements(schema)

        // Then — the fixture that the open-in-place tests write is exactly this schema
        assertEquals(statements, ShippedDatabaseFixture.SCHEMA_STATEMENTS)
    }

    @Test
    fun the_version_1_fixture_is_the_exported_schema_1_verbatim() {
        // Given
        val schema = exportedSchema(version = 1)

        // When
        val statements = creationStatements(schema)

        // Then
        assertEquals(statements, ShippedDatabaseFixture.VERSION_1_SCHEMA_STATEMENTS)
        assertEquals(
            ShippedDatabaseFixture.VERSION_1_IDENTITY_HASH,
            schema.getValue("identityHash").jsonPrimitive.content,
        )
    }

    /**
     * Every statement Room runs to create [schema]: each table, its indices, then setup queries.
     */
    private fun creationStatements(schema: JsonObject): List<String> = buildList {
        schema.getValue("entities").jsonArray.forEach { element ->
            val entity = element.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            add(entity.getValue("createSql").jsonPrimitive.content.withTableName(tableName))
            entity["indices"]?.jsonArray?.forEach { index ->
                add(
                    index.jsonObject
                        .getValue("createSql")
                        .jsonPrimitive
                        .content
                        .withTableName(tableName)
                )
            }
        }
        schema.getValue("setupQueries").jsonArray.forEach { query ->
            add(query.jsonPrimitive.content)
        }
    }

    private fun String.withTableName(tableName: String): String =
        replace(TABLE_NAME_PLACEHOLDER, tableName)

    private companion object {
        const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"

        /** Set by the build (supabase-offline.room) to the module's `schemas/` directory. */
        const val SCHEMA_DIRECTORY_PROPERTY = "supabase.offline.schemaDirectory"
    }
}
