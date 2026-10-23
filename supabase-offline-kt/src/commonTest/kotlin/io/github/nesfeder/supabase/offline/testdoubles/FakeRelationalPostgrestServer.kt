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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A stateful PostgREST server over a [RecordingPostgrestServer] that keeps rows per table and
 * enforces primary and foreign keys, answering a violation as PostgREST does. It understands what
 * replay sends, `POST`, `PATCH`, `DELETE` and `GET` scoped by `eq`, `neq`, `in`, `is` and prefix
 * `like` filters on any column, returns only the columns a `GET`, `PATCH` or `DELETE` selects, and
 * answers anything else `400`. Requests are answered one at a time, and a write asking for its rows
 * back (`Prefer: return=representation`) is answered with the rows it wrote.
 *
 * @property primaryKey the primary-key column every table shares.
 * @property foreignKeys child table → the foreign key it must satisfy.
 * @property versionColumns table → the version column every `PATCH` of it stamps anew, as a trigger
 *   would.
 * @param engineDispatcher where the engine runs requests; see [RecordingPostgrestServer].
 */
internal class FakeRelationalPostgrestServer(
    private val primaryKey: String = "id",
    private val foreignKeys: Map<String, ForeignKey> = emptyMap(),
    private val versionColumns: Map<String, VersionColumn> = emptyMap(),
    engineDispatcher: CoroutineDispatcher? = null,
) {

    /**
     * A version column a `PATCH` stamps.
     *
     * @property column the column's name.
     * @property kind how it stamps.
     */
    data class VersionColumn(val column: String, val kind: VersionKind = VersionKind.TIMESTAMP)

    /** How a [VersionColumn] is stamped. */
    enum class VersionKind {
        /** A `timestamptz` as PostgREST writes one: microseconds and a `+00:00` offset. */
        TIMESTAMP,

        /** An integer counted up by one. */
        COUNTER,
    }

    /** How many versions this server has stamped, which orders the timestamps it writes. */
    private var stamped = 0

    /**
     * A foreign key: [column] of the child table must name an existing row of [parentTable].
     *
     * @property column the child's referencing column.
     * @property parentTable the referenced table, matched on its primary key.
     */
    data class ForeignKey(val column: String, val parentTable: String)

    private val tables = mutableMapOf<String, MutableMap<String, JsonObject>>()

    /** Held while a request is answered, so requests arriving at once see each other whole. */
    private val answering = Mutex()

    /** How long a `GET` waits before it is answered, as a slow server would; none by default. */
    var answerDelayMillis: Long = 0L

    /**
     * When set and returning `true` for a request, the request's write is committed and then the
     * connection drops before the response arrives — a lost response.
     */
    var loseResponseAfterCommit: ((RecordedRequest) -> Boolean)? = null

    /**
     * Tables with no unique constraint on [primaryKey]. An upsert on one is answered `400` with
     * SQLSTATE `42P10`, as Postgres answers an `ON CONFLICT` target no constraint matches.
     */
    var tablesWithoutUniqueKey: Set<String> = emptySet()

    /**
     * When set and returning `true` for a `PATCH` or `DELETE`, the write changes nothing and is
     * answered as one that matched no row: a row-level policy whose `USING` hides the row from it.
     */
    var hidesRowsFromWrite: ((RecordedRequest) -> Boolean)? = null

    /**
     * When set and returning `true` for a request, it fails as a connection that never reached the
     * server: nothing is read or written.
     */
    var failsRequest: ((RecordedRequest) -> Boolean)? = null

    /**
     * Runs before a request is answered, outside the server's turn: a test parks a request here to
     * act while it is on the wire. `null` answers at once.
     */
    var beforeAnswer: (suspend (RecordedRequest) -> Unit)? = null

    /** The recording server underneath; its `requests` list everything received. */
    val recorder: RecordingPostgrestServer =
        RecordingPostgrestServer(
            answer = { request ->
                if (request.method == "GET" && answerDelayMillis > 0) delay(answerDelayMillis)
                beforeAnswer?.invoke(request)
                if (failsRequest?.invoke(request) == true) connectivityFailure()
                answering.withLock { handle(request) }
            },
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

    /** The rows [table] holds. */
    fun rows(table: String): List<JsonObject> = tables[table].orEmpty().values.toList()

    /** Replaces [table]'s row with [row]'s key by [row], as another device's write would. */
    fun replaceRow(table: String, row: JsonObject) {
        tables.getOrPut(table) { mutableMapOf() }[keyOf(row)] = row
    }

    /** Deletes [table]'s row with the key [key], as another device would. */
    fun deleteRow(table: String, key: String) {
        tables[table]?.remove(key)
    }

    /** A new version for [table]'s [row], as its trigger would write it. */
    fun nextVersion(table: String, row: JsonObject? = null): JsonPrimitive {
        val version = checkNotNull(versionColumns[table]) { "$table has no version column" }
        stamped++
        return when (version.kind) {
            VersionKind.TIMESTAMP -> JsonPrimitive(timestampAt(stamped))
            VersionKind.COUNTER ->
                JsonPrimitive(
                    ((row?.get(version.column) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                        ?: 0L) + 1
                )
        }
    }

    private fun MockRequestHandleScope.handle(request: RecordedRequest): HttpResponseData {
        val response =
            when (request.method) {
                "POST" -> insert(request)
                "PATCH" -> update(request)
                "DELETE" -> delete(request)
                "GET" ->
                    okJson(JsonArray(matching(request).map { projected(it, request) }).toString())
                else -> postgrestError(HttpStatusCode.BadRequest, code = "PGRST000")
            }
        if (loseResponseAfterCommit?.invoke(request) == true) {
            loseResponseAfterCommit = null
            connectivityFailure()
        }
        return response
    }

    /**
     * A `POST`, honoring `on_conflict` and the `ignore-duplicates` and `merge-duplicates`
     * resolutions. With `Prefer: count=exact` it answers a `Content-Range` counting the rows it
     * wrote, as PostgREST does.
     */
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
        val writtenRows = mutableListOf<JsonObject>()
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
                    val inserted = withKey(row, key)
                    staged[if (existing == null) key else "$key-${staged.size + 1}"] = inserted
                    writtenRows += inserted
                    written++
                }
                "resolution=ignore-duplicates" in resolution -> Unit
                "resolution=merge-duplicates" in resolution -> {
                    val merged = JsonObject(existing + row)
                    staged[key] = merged
                    writtenRows += merged
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
        val returned = if ("return=representation" in resolution) JsonArray(writtenRows) else null
        return okJson(returned?.toString() ?: "[]", countHeader)
    }

    private fun MockRequestHandleScope.update(request: RecordedRequest): HttpResponseData {
        val patch = Json.parseToJsonElement(request.body) as JsonObject
        val stored = tables.getOrPut(request.table) { mutableMapOf() }
        val version = versionColumns[request.table]
        val written =
            writable(request).map { row ->
                var updated = JsonObject(row + patch)
                if (version != null) {
                    updated =
                        JsonObject(updated + (version.column to nextVersion(request.table, row)))
                }
                stored[keyOf(row)] = updated
                updated
            }
        return writeAnswer(request, written)
    }

    private fun MockRequestHandleScope.delete(request: RecordedRequest): HttpResponseData {
        val stored = tables.getOrPut(request.table) { mutableMapOf() }
        val deleted = writable(request).onEach { row -> stored.remove(keyOf(row)) }
        return writeAnswer(request, deleted)
    }

    /** The rows a `PATCH` or `DELETE` may change: none when [hidesRowsFromWrite] hides them. */
    private fun writable(request: RecordedRequest): List<JsonObject> =
        if (hidesRowsFromWrite?.invoke(request) == true) emptyList() else matching(request)

    /** A write's answer: [rows] when it asked for them back, `[]` otherwise, both `200`. */
    private fun MockRequestHandleScope.writeAnswer(
        request: RecordedRequest,
        rows: List<JsonObject>,
    ): HttpResponseData {
        val preferences = request.prefer.orEmpty().split(',').map { it.trim() }
        return okJson(
            if ("return=representation" in preferences)
                JsonArray(rows.map { row -> projected(row, request) }).toString()
            else "[]"
        )
    }

    /**
     * [row] with only the columns [request]'s `select` names, as PostgREST answers; the whole row
     * for none, `*`, or a list with an embedded resource this server does not model.
     */
    private fun projected(row: JsonObject, request: RecordedRequest): JsonObject {
        val select = request.query["select"] ?: return row
        if (select == "*" || '(' in select) return row
        val columns = select.split(',').map { column -> column.trim().removeSurrounding("\"") }
        return JsonObject(row.filterKeys { column -> column in columns })
    }

    private fun matching(request: RecordedRequest): List<JsonObject> {
        val conditions = request.query.filterKeys { key -> key !in NON_FILTER_PARAMS }
        val rows =
            tables[request.table].orEmpty().values.filter { row ->
                conditions.all { (column, condition) -> matches(row[column], condition) }
            }
        val limit = request.query["limit"]?.toIntOrNull() ?: return rows
        return rows.take(limit)
    }

    /** Whether [value] meets [condition], a PostgREST `operator.value` filter. */
    private fun matches(value: JsonElement?, condition: String): Boolean {
        val operator = condition.substringBefore('.')
        val operand = condition.substringAfter('.')
        val content = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
        return when (operator) {
            "eq" -> content == operand
            "neq" -> content != null && content != operand
            "in" -> {
                val listed =
                    operand.removePrefix("(").removeSuffix(")").split(',').map { listed ->
                        listed.trim().removeSurrounding("\"")
                    }
                // PostgREST reads a list of one empty element, `()` or `("")`, as the empty list
                content != null && listed != listOf("") && content in listed
            }
            // A prefix pattern only (`abc*`), enough to stand for an operator the engine does not
            // evaluate itself
            "like" -> content != null && content.startsWith(operand.removeSuffix("*"))
            "is" ->
                when (operand) {
                    "null" -> content == null
                    "true" -> content == "true"
                    "false" -> content == "false"
                    else -> false
                }
            else -> false
        }
    }

    private fun keyOf(row: JsonObject, generated: Int = 0): String =
        (row[primaryKey] as? JsonPrimitive)?.contentOrNull ?: "generated-$generated"

    private fun withKey(row: JsonObject, key: String): JsonObject =
        if (row.containsKey(primaryKey)) row
        else JsonObject(row + (primaryKey to JsonPrimitive(key) as JsonElement))

    private companion object {
        /** A `timestamptz` text for the [count]th stamp: microseconds and a `+00:00` offset. */
        fun timestampAt(count: Int): String {
            val minutes = (count / 60).toString().padStart(2, '0')
            val seconds = (count % 60).toString().padStart(2, '0')
            return "2026-10-18T13:$minutes:$seconds.486123+00:00"
        }

        val NON_FILTER_PARAMS =
            setOf("select", "columns", "on_conflict", "order", "limit", "offset")
        const val CONTENT_RANGE = "Content-Range"
    }
}
