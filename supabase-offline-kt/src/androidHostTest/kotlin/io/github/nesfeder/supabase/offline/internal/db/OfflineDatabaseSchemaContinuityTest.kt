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
 * Pins the exported Room schema the library ships: version 1, identity hash
 * `b6a7e9150b888e5e54cb2be30f46a734`, and no other version. Any change to a table, column or index
 * changes the hash, and Room then takes the destructive fallback on a device — deleting the unsent
 * writes the file holds. The exported schema is the record of what the build compiles; these cases
 * fail the build if it drifts.
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
    fun exported_schema_1_has_the_pinned_identity_hash() {
        // Given
        val schema = exportedSchema(version = 1)

        // When
        val version = schema.getValue("version").jsonPrimitive.int
        val identityHash = schema.getValue("identityHash").jsonPrimitive.content

        // Then
        assertEquals(1, version)
        assertEquals(ShippedDatabaseFixture.IDENTITY_HASH, identityHash)
    }

    @Test
    fun the_current_schema_version_is_the_exported_one_and_the_only_one() {
        // Given / When — the version the database class compiles with, and the exported files
        val current = OFFLINE_DB_VERSION
        val exported = schemaDirectory.list().orEmpty().sorted()

        // Then — raising it is a deliberate schema change that needs its own migration
        assertEquals(ShippedDatabaseFixture.VERSION.toInt(), current)
        assertEquals(listOf("$current.json"), exported)
    }

    @Test
    fun the_open_in_place_fixture_is_the_exported_schema_verbatim() {
        // Given
        val schema = exportedSchema(version = 1)

        // When — every statement Room would run to create schema version 1
        val statements = creationStatements(schema)

        // Then — the fixture that the open-in-place tests write is exactly this schema
        assertEquals(statements, ShippedDatabaseFixture.SCHEMA_STATEMENTS)
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
