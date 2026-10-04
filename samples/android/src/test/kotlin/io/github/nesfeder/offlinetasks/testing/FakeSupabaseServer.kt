// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.testing

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.testing.inMemory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The bucket the fake Storage serves, as the samples' schema names it. */
private const val BUCKET = "task-photos"

/** The longest title the schema's check accepts. */
private const val TITLE_LIMIT = 200

/**
 * The samples' Supabase project, faked on a Ktor [MockEngine] for one test: the `tasks` and
 * `task_photos` tables with the schema's title check, and the `task-photos` bucket. It answers the
 * PostgREST and Storage requests the sample makes, and records them.
 */
class FakeSupabaseServer {

    /** Each table's rows, by table name, in insertion order. */
    val tables: MutableMap<String, MutableList<JsonObject>> =
        mutableMapOf("tasks" to mutableListOf(), "task_photos" to mutableListOf())

    /** The bucket's files, by object path. */
    val files: MutableMap<String, ByteArray> = mutableMapOf()

    /** Every request that reached the server, as `"METHOD /path"`, in order. */
    val requests: MutableList<String> = mutableListOf()

    /** While true, every PostgREST request answers HTTP 500, as a failing database does. */
    var isFailing: Boolean = false

    /** While false, every request fails at the transport, as it does with no network. */
    var isReachable: Boolean = true

    private val lock = Any()

    /**
     * A client of this server with PostgREST, Storage and the offline plugin keeping its data in
     * memory; no Auth, so every request is the one anonymous identity.
     */
    fun supabaseClient(): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = "https://sample.supabase.test",
            supabaseKey = "test-key",
        ) {
            httpEngine = MockEngine { request -> answer(request) }
            install(Postgrest) { maxRetries = 0 }
            install(Storage) { resumable { cache = MemoryResumableCache() } }
            install(Offline) { inMemory(Clock.System) }
        }

    /** Adds [row] to [table] as the server stores it, as if another device had sent it. */
    fun insertRow(table: String, row: JsonObject) {
        synchronized(lock) { tables.getValue(table) += row }
    }

    /** The rows of [table], as the test reads the server's copy. */
    fun rows(table: String): List<JsonObject> =
        synchronized(lock) { tables.getValue(table).toList() }

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        synchronized(lock) { requests += "${request.method.value} $path" }
        if (!isReachable) throw IOException("network unreachable")
        val body = request.body.toByteArray()
        return synchronized(lock) {
            when {
                path.startsWith("/rest/v1/") ->
                    postgrest(request, path.removePrefix("/rest/v1/"), body)
                path.startsWith("/storage/v1/") ->
                    storage(request, path.removePrefix("/storage/v1/"), body)
                else -> error("FakeSupabaseServer has no route for $path")
            }
        }
    }

    private fun MockRequestHandleScope.postgrest(
        request: HttpRequestData,
        table: String,
        body: ByteArray,
    ): HttpResponseData {
        if (isFailing)
            return json(
                """{"code":"XX000","message":"failing on purpose"}""",
                HttpStatusCode.InternalServerError,
            )
        val rows = tables.getValue(table)
        val filters = equalityFilters(request)
        val wantsRows = request.headers["Prefer"].orEmpty().contains("return=representation")
        return when (request.method) {
            HttpMethod.Get ->
                json(JsonArray(select(rows, filters, request)).toString(), HttpStatusCode.OK)
            HttpMethod.Post -> {
                val inserted = Json.parseToJsonElement(body.decodeToString()).asRows()
                inserted
                    .firstNotNullOfOrNull { refusalOf(table, it) }
                    ?.let {
                        return it
                    }
                val stored = inserted.map { stamped(it) }
                rows += stored
                json(if (wantsRows) JsonArray(stored).toString() else "", HttpStatusCode.Created)
            }
            HttpMethod.Patch -> {
                val changes = Json.parseToJsonElement(body.decodeToString()).jsonObject
                val updated =
                    rows
                        .filter { it.matches(filters) }
                        .associateWith { JsonObject(it + changes + ("updated_at" to now())) }
                updated.values
                    .firstNotNullOfOrNull { refusalOf(table, it) }
                    ?.let {
                        return it
                    }
                rows.replaceAll { row -> updated[row] ?: row }
                json(
                    if (wantsRows) JsonArray(updated.values.toList()).toString() else "",
                    HttpStatusCode.OK,
                )
            }
            HttpMethod.Delete -> {
                val deleted = rows.filter { it.matches(filters) }
                rows.removeAll(deleted)
                json(if (wantsRows) JsonArray(deleted).toString() else "", HttpStatusCode.OK)
            }
            else -> error("FakeSupabaseServer has no ${request.method.value} for PostgREST")
        }
    }

    private fun MockRequestHandleScope.storage(
        request: HttpRequestData,
        route: String,
        body: ByteArray,
    ): HttpResponseData {
        val segments = route.split('/').map { it.decodeURLPart() }
        return when {
            request.method == HttpMethod.Get &&
                segments.take(2) == listOf("object", "authenticated") -> {
                val objectPath = segments.drop(3).joinToString("/")
                files[objectPath]?.let { bytes ->
                    respond(
                        bytes,
                        HttpStatusCode.OK,
                        headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.OctetStream.toString(),
                        ),
                    )
                }
                    ?: json(
                        """{"statusCode":404,"error":"not_found","message":"Object not found"}""",
                        HttpStatusCode.BadRequest,
                    )
            }
            request.method == HttpMethod.Delete && segments == listOf("object", BUCKET) -> {
                val prefixes =
                    Json.parseToJsonElement(body.decodeToString())
                        .jsonObject
                        .getValue("prefixes")
                        .jsonArray
                prefixes.forEach { files.remove(it.jsonPrimitive.content) }
                json("[]", HttpStatusCode.OK)
            }
            request.method == HttpMethod.Post && segments.take(2) == listOf("object", BUCKET) -> {
                val objectPath = segments.drop(2).joinToString("/")
                if (objectPath in files) {
                    json(
                        """{"statusCode":"409","error":"Duplicate","message":"The resource already exists"}""",
                        HttpStatusCode.Conflict,
                    )
                } else {
                    files[objectPath] = body
                    json(
                        """{"Key":"$BUCKET/$objectPath","Id":"id-${files.size}"}""",
                        HttpStatusCode.OK,
                    )
                }
            }
            else ->
                error("FakeSupabaseServer has no Storage route for ${request.method.value} $route")
        }
    }

    /** The schema's check on a task's title: not blank, at most 200 characters; else 23514. */
    private fun MockRequestHandleScope.refusalOf(
        table: String,
        row: JsonObject,
    ): HttpResponseData? {
        if (table != "tasks") return null
        val title = row["title"]?.jsonPrimitive?.contentOrNull ?: return null
        if (
            title.trim(' ', '\t', '\r', '\n').isNotEmpty() &&
                title.codePointCount(0, title.length) <= TITLE_LIMIT
        )
            return null
        return json(
            """{"code":"23514","details":null,"hint":null,"message":"new row for relation \"tasks\" violates check constraint \"tasks_title_length\""}""",
            HttpStatusCode.BadRequest,
        )
    }

    /** The rows [filters] selects, in the `order` asked for, with only the `select`ed columns. */
    private fun select(
        rows: List<JsonObject>,
        filters: Map<String, String>,
        request: HttpRequestData,
    ): List<JsonObject> {
        val order = request.url.parameters["order"]?.split('.')
        val selected = rows.filter { it.matches(filters) }
        val ordered =
            if (order == null) selected
            else
                selected
                    .sortedBy { it[order[0]]?.jsonPrimitive?.contentOrNull }
                    .let { if (order.getOrNull(1) == "desc") it.reversed() else it }
        val columns = request.url.parameters["select"]?.takeUnless { it == "*" }?.split(',')
        return if (columns == null) ordered
        else ordered.map { row -> JsonObject(row.filterKeys { it in columns }) }
    }

    /** A new row as the database stores it: `updated_at` set, defaults filled in. */
    private fun stamped(row: JsonObject): JsonObject =
        JsonObject(
            buildMap {
                put("notes", JsonNull)
                put("done", JsonPrimitive(false))
                put("width", JsonNull)
                put("height", JsonNull)
                putAll(row)
                put("updated_at", now())
            }
        )

    private fun now(): JsonPrimitive = JsonPrimitive(Clock.System.now().toString())

    private fun MockRequestHandleScope.json(
        content: String,
        status: HttpStatusCode,
    ): HttpResponseData =
        respond(
            content,
            status,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
}

/** The request's `column=eq.value` filters, by column. */
private fun equalityFilters(request: HttpRequestData): Map<String, String> =
    request.url.parameters
        .names()
        .mapNotNull { name ->
            request.url.parameters[name]
                ?.takeIf { it.startsWith("eq.") }
                ?.let { name to it.removePrefix("eq.") }
        }
        .toMap()

private fun JsonObject.matches(filters: Map<String, String>): Boolean =
    filters.all { (column, value) ->
        this[column]?.jsonPrimitive?.contentOrNull == value
    }

private fun JsonElement.asRows(): List<JsonObject> =
    if (this is JsonArray) map { it.jsonObject } else listOf(jsonObject)
