// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.RecordedRequest
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A PostgREST server with state, on top of a [RecordingPostgrestServer]: it keeps rows per table,
 * enforces each table's primary key and the foreign keys it is given, and answers the way PostgREST
 * does — a duplicate key is `409` with SQLSTATE `23505`, a missing parent `409` with `23503`.
 *
 * It understands exactly what the engine's replay sends: `POST` (with `on_conflict` and the
 * `resolution=ignore-duplicates` / `merge-duplicates` preferences), `PATCH` and `DELETE` scoped by
 * `eq` filters, and `GET` scoped the same way. Anything else is answered `400`. With `Prefer:
 * count=exact` a `POST` answers a `Content-Range` whose count is the rows it wrote, as PostgREST
 * does.
 *
 * @property primaryKey the primary-key column every table shares.
 * @property foreignKeys child table → the foreign key it must satisfy.
 * @param engineDispatcher where the engine runs requests; see [RecordingPostgrestServer].
 */
internal class FakeRelationalPostgrestServer(
    private val primaryKey: String = "id",
    private val foreignKeys: Map<String, ForeignKey> = emptyMap(),
    engineDispatcher: CoroutineDispatcher? = null,
) {

    /**
     * A foreign key: [column] of the child table must name an existing row of [parentTable].
     *
     * @property column the child's referencing column.
     * @property parentTable the referenced table, matched on its primary key.
     */
    data class ForeignKey(val column: String, val parentTable: String)

    private val tables = mutableMapOf<String, MutableMap<String, JsonObject>>()

    /**
     * When set and returning `true` for a request, the request's write is committed and then the
     * connection drops before the response arrives — a lost response.
     */
    var loseResponseAfterCommit: ((RecordedRequest) -> Boolean)? = null

    /**
     * Tables with no unique constraint on [primaryKey]: an upsert on them is answered `400` with
     * SQLSTATE `42P10`, as Postgres answers an `ON CONFLICT` target that matches no constraint.
     */
    var tablesWithoutUniqueKey: Set<String> = emptySet()

    /** The recording server underneath; its `requests` list everything received. */
    val recorder: RecordingPostgrestServer =
        RecordingPostgrestServer(
            answer = { request -> handle(request) },
            engineDispatcher = engineDispatcher,
        )

    /** A supabase-kt client whose PostgREST requests reach this server. */
    fun supabaseClient(): SupabaseClient = recorder.supabaseClient()

    /** Every request received, in order. */
    val requests: List<RecordedRequest>
        get() = recorder.requests

    /** Puts [rows] in [table] without any request. */
    fun seed(table: String, vararg rows: JsonObject) {
        val stored = tables.getOrPut(table) { mutableMapOf() }
        rows.forEach { row -> stored[keyOf(row)] = row }
    }

    /** The rows [table] holds now. */
    fun rows(table: String): List<JsonObject> = tables[table].orEmpty().values.toList()

    private fun MockRequestHandleScope.handle(request: RecordedRequest): HttpResponseData {
        val response =
            when (request.method) {
                "POST" -> insert(request)
                "PATCH" -> update(request)
                "DELETE" -> delete(request)
                "GET" -> okJson(JsonArray(matching(request)).toString())
                else -> postgrestError(HttpStatusCode.BadRequest, code = "PGRST000")
            }
        if (loseResponseAfterCommit?.invoke(request) == true) {
            loseResponseAfterCommit = null
            connectivityFailure()
        }
        return response
    }

    private fun MockRequestHandleScope.insert(request: RecordedRequest): HttpResponseData {
        val body = Json.parseToJsonElement(request.body) as JsonArray
        val resolution = request.prefer.orEmpty().split(',').map { it.trim() }
        if ("on_conflict" in request.query && request.table in tablesWithoutUniqueKey) {
            return postgrestError(
                HttpStatusCode.BadRequest,
                code = "42P10",
                message =
                    "there is no unique or exclusion constraint matching the ON CONFLICT specification",
            )
        }
        val stored = tables.getOrPut(request.table) { mutableMapOf() }
        val staged = stored.toMutableMap()
        var written = 0
        for (element in body) {
            val row = element as JsonObject
            val foreignKey = foreignKeys[request.table]
            if (foreignKey != null) {
                val parentKey = (row[foreignKey.column] as? JsonPrimitive)?.contentOrNull
                if (
                    parentKey == null ||
                        tables[foreignKey.parentTable]?.containsKey(parentKey) != true
                ) {
                    return postgrestError(
                        HttpStatusCode.Conflict,
                        code = "23503",
                        message = "insert or update violates foreign key constraint",
                    )
                }
            }
            val key = keyOf(row, generated = staged.size + 1)
            val existing = staged[key]
            when {
                existing == null || request.table in tablesWithoutUniqueKey -> {
                    staged[if (existing == null) key else "$key-${staged.size + 1}"] =
                        withKey(row, key)
                    written++
                }
                "resolution=ignore-duplicates" in resolution -> Unit
                "resolution=merge-duplicates" in resolution -> {
                    staged[key] = JsonObject(existing + row)
                    written++
                }
                else ->
                    return postgrestError(
                        HttpStatusCode.Conflict,
                        code = "23505",
                        message = "duplicate key value violates unique constraint",
                    )
            }
        }
        stored.clear()
        stored.putAll(staged)
        val countHeader =
            if ("count=exact" in resolution) mapOf(CONTENT_RANGE to "*/$written") else emptyMap()
        return okJson("[]", countHeader)
    }

    private fun MockRequestHandleScope.update(request: RecordedRequest): HttpResponseData {
        val patch = Json.parseToJsonElement(request.body) as JsonObject
        val stored = tables.getOrPut(request.table) { mutableMapOf() }
        matching(request).forEach { row ->
            val key = keyOf(row)
            stored[key] = JsonObject(row + patch)
        }
        return okJson("[]")
    }

    private fun MockRequestHandleScope.delete(request: RecordedRequest): HttpResponseData {
        val stored = tables.getOrPut(request.table) { mutableMapOf() }
        matching(request).forEach { row -> stored.remove(keyOf(row)) }
        return okJson("[]")
    }

    private fun matching(request: RecordedRequest): List<JsonObject> {
        val conditions =
            request.query
                .filterKeys { key -> key !in NON_FILTER_PARAMS }
                .mapValues { (_, value) -> value.removePrefix("eq.") }
        return tables[request.table].orEmpty().values.filter { row ->
            conditions.all { (column, value) ->
                (row[column] as? JsonPrimitive)?.contentOrNull == value
            }
        }
    }

    private fun keyOf(row: JsonObject, generated: Int = 0): String =
        (row[primaryKey] as? JsonPrimitive)?.contentOrNull ?: "generated-$generated"

    private fun withKey(row: JsonObject, key: String): JsonObject =
        if (row.containsKey(primaryKey)) row
        else JsonObject(row + (primaryKey to JsonPrimitive(key) as JsonElement))

    private companion object {
        val NON_FILTER_PARAMS =
            setOf("select", "columns", "on_conflict", "order", "limit", "offset")
        const val CONTENT_RANGE = "Content-Range"
    }
}
