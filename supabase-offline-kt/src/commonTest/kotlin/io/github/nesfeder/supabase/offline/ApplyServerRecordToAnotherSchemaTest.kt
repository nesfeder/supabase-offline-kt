// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A row the server reports for a table of another schema is applied to that schema's cached
 * queries, which are kept apart from those of a table of the same name in the default schema.
 */
class ApplyServerRecordToAnotherSchemaTest {

    private val server =
        RecordingPostgrestServer(
            answer = { request ->
                okJson(
                    if (request.acceptProfile == SCHEMA) """[{"id":1,"title":"in app"}]"""
                    else """[{"id":1,"title":"in public"}]"""
                )
            }
        )

    @Test
    fun a_record_and_a_delete_reach_the_named_schemas_table_only() = runTest {
        // Given — a table of the same name cached in both schemas
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        client.from(SCHEMA, TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)

        // When — the other schema's row changes, then a row is deleted there
        client.applyServerRecord(
            TABLE,
            buildJsonObject {
                put("id", 1)
                put("title", "renamed in app")
            },
            schema = SCHEMA,
        )
        val renamed = client.from(SCHEMA, TABLE).select(readStrategy = ReadStrategy.CacheOnly())
        client.applyServerDelete(TABLE, buildJsonObject { put("id", 1) }, schema = SCHEMA)
        val deleted = client.from(SCHEMA, TABLE).select(readStrategy = ReadStrategy.CacheOnly())
        val defaultSchema = client.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly())

        // Then
        assertEquals("""[{"id":1,"title":"renamed in app"}]""", renamed.data)
        assertEquals("[]", deleted.data)
        assertEquals("""[{"id":1,"title":"in public"}]""", defaultSchema.data)
        client.close()
    }

    private companion object {
        const val SCHEMA = "app"
        const val TABLE = "todos"
    }
}
