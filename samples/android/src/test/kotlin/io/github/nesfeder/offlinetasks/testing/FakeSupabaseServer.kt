// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.testing

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.offlinetasks.data.supabase.UPDATED_AT
import io.github.nesfeder.offlinetasks.platform.holdTitleConflicts
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.testing.FakeRealtime
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
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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

/** The path a photo row is inserted at. */
private const val PHOTO_ROWS_PATH = "/rest/v1/task_photos"

/** The path every upload to the bucket is sent to, before the object's own path. */
private const val UPLOAD_PREFIX = "/storage/v1/object/$BUCKET/"

/**
 * The samples' Supabase project, faked on a Ktor [MockEngine] for one test: the `tasks` and
 * `task_photos` tables with the schema's title check and a photo's foreign key to its task, and the
 * `task-photos` bucket. It answers the PostgREST and Storage requests the sample makes, and records
 * them. Like the schema's `moddatetime` trigger it stamps a new `updated_at` on every change, and a
 * write's `eq` filters, the version precondition among them, pick the rows it changes.
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

    /** While true, every upload to the bucket answers HTTP 500, as a failing Storage does. */
    var isUploadFailing: Boolean = false

    /** While true, every delete from the bucket answers HTTP 403, as a refusing policy would. */
    var isDeleteRefused: Boolean = false

    /** The most rows one read returns, as a hosted project's row limit caps it. */
    var maxRows: Int = 1_000

    /**
     * While true, an upload's answer is lost once the file is stored, as a connection that drops on
     * the way back would lose it: the file is in the bucket, and the app hears a network failure.
     */
    var loseUploadAnswers: Boolean = false

    /**
     * While true, an upload finds a file already at its path, as one an earlier try stored would
     * be: the bytes are kept, and the answer is Storage's "already exists".
     */
    var uploadsFindTheirFileStored: Boolean = false

    /**
     * While set, an upload's answer waits for it once the file is stored, as a slow network would
     * keep it: the file is in the bucket before the app hears so.
     */
    var holdUploadAnswers: CompletableDeferred<Unit>? = null

    /** How many uploads are stored and waiting on [holdUploadAnswers]. */
    val uploadsHeld: MutableStateFlow<Int> = MutableStateFlow(0)

    /**
     * While set, a photo row's insert waits for it before the row is stored, and fails at the
     * transport when it completes with `false`, as a connection lost on the way would.
     */
    var holdPhotoRowInserts: CompletableDeferred<Boolean>? = null

    /** How many photo row inserts are waiting on [holdPhotoRowInserts]. */
    val photoRowInsertsHeld: MutableStateFlow<Int> = MutableStateFlow(0)

    private val lock = Any()

    /** The Realtime channel's stand-in: a test reports another device's changes here. */
    val realtime: FakeRealtime = FakeRealtime()

    /** The last `updated_at` stamped, so every stamp is later than the one before. */
    private var lastStamp: Instant = Instant.DISTANT_PAST

    // --8<-- [start:in-memory-client]
    /**
     * A client of this server with PostgREST, Storage and the offline plugin keeping its data in
     * memory, its Realtime tables followed through [realtime] (so no Realtime plugin) and its
     * version columns and resolver as the app sets them; no Auth, so every request is the one
     * anonymous identity.
     */
    fun supabaseClient(): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = "https://sample.supabase.test",
            supabaseKey = "test-key",
        ) {
            httpEngine = MockEngine { request -> answer(request) } // this fake server
            install(Postgrest) { maxRetries = 0 } // a failed read fails now, not after retries
            install(Storage) { resumable { cache = MemoryResumableCache() } }
            install(Offline) {
                inMemory(Clock.System, realtime) // the cache and the queue in memory
                realtime { // the app's two tables, as the app names them
                    table(Tables.TASKS)
                    table(Tables.TASK_PHOTOS)
                }
                versionColumn(Tables.TASKS, UPDATED_AT) // and their version columns
                versionColumn(Tables.TASK_PHOTOS, UPDATED_AT)
                conflictResolver = holdTitleConflicts
            }
        }

    // --8<-- [end:in-memory-client]

    /** Adds [row] to [table] as the server stores it, as if another device had sent it. */
    fun insertRow(table: String, row: JsonObject) {
        synchronized(lock) { tables.getValue(table) += row }
    }

    /** Deletes every row of [table] on the server, as if another device had deleted them. */
    fun deleteRows(table: String) {
        synchronized(lock) { tables.getValue(table).clear() }
    }

    // --8<-- [start:stage-server-edit]
    /**
     * Sets [values] on the row of [table] with [id], as another device's update would: the server
     * stamps a new `updated_at`, and nothing reaches this client's channel. A device that queued a
     * change to the row before this finds it moved when the change replays.
     */
    fun replaceRow(table: String, id: String, values: JsonObject) {
        synchronized(lock) {
            val rows = tables.getValue(table)
            val index = rows.indexOfFirst { it["id"]?.jsonPrimitive?.contentOrNull == id }
            check(index >= 0) { "No row $id in $table" }
            rows[index] = JsonObject(rows[index] + values + (UPDATED_AT to now()))
        }
    }

    // --8<-- [end:stage-server-edit]

    /** The rows of [table], as the test reads the server's copy. */
    fun rows(table: String): List<JsonObject> =
        synchronized(lock) { tables.getValue(table).toList() }

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        synchronized(lock) { requests += "${request.method.value} $path" }
        if (!isReachable) throw IOException("network unreachable")
        val rowHold = holdPhotoRowInserts
        if (rowHold != null && request.method == HttpMethod.Post && path == PHOTO_ROWS_PATH) {
            photoRowInsertsHeld.update { it + 1 }
            if (!rowHold.await()) throw IOException("connection lost")
        }
        val body = request.body.toByteArray()
        val response =
            synchronized(lock) {
                when {
                    path.startsWith("/rest/v1/") ->
                        postgrest(request, path.removePrefix("/rest/v1/"), body)
                    path.startsWith("/storage/v1/") ->
                        storage(request, path.removePrefix("/storage/v1/"), body)
                    else -> error("FakeSupabaseServer has no route for $path")
                }
            }
        val isUpload = request.method == HttpMethod.Post && path.startsWith(UPLOAD_PREFIX)
        if (loseUploadAnswers && isUpload) throw IOException("connection lost after the upload")
        val hold = holdUploadAnswers
        if (hold != null && isUpload) {
            uploadsHeld.update { it + 1 }
            hold.await()
        }
        return response
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
        if (table.startsWith("rpc/")) return function(table.removePrefix("rpc/"))
        val rows = tables.getValue(table)
        val filters = equalityFilters(request)
        val prefer = request.headers["Prefer"].orEmpty()
        val wantsRows = prefer.contains("return=representation")
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
                val stored = upserted(rows, inserted.map { stamped(it) }, prefer)
                json(if (wantsRows) JsonArray(stored).toString() else "", HttpStatusCode.Created)
            }
            HttpMethod.Patch -> {
                val changes = Json.parseToJsonElement(body.decodeToString()).jsonObject
                val updated =
                    rows
                        .filter { it.matches(filters) }
                        .associateWith { JsonObject(it + changes + (UPDATED_AT to now())) }
                updated.values
                    .firstNotNullOfOrNull { refusalOf(table, it) }
                    ?.let {
                        return it
                    }
                rows.replaceAll { row -> updated[row] ?: row }
                val written = updated.values.map { projected(it, request) }
                json(if (wantsRows) JsonArray(written).toString() else "", HttpStatusCode.OK)
            }
            HttpMethod.Delete -> {
                val deleted = rows.filter { it.matches(filters) }
                rows.removeAll(deleted)
                val written = deleted.map { projected(it, request) }
                json(if (wantsRows) JsonArray(written).toString() else "", HttpStatusCode.OK)
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
            request.method == HttpMethod.Delete &&
                segments == listOf("object", BUCKET) &&
                isDeleteRefused ->
                json(
                    """{"statusCode":"403","error":"Unauthorized","message":"refused on purpose"}""",
                    HttpStatusCode.Forbidden,
                )
            request.method == HttpMethod.Delete && segments == listOf("object", BUCKET) -> {
                val prefixes =
                    Json.parseToJsonElement(body.decodeToString())
                        .jsonObject
                        .getValue("prefixes")
                        .jsonArray
                prefixes.forEach { files.remove(it.jsonPrimitive.content) }
                json("[]", HttpStatusCode.OK)
            }
            request.method == HttpMethod.Head && segments.take(2) == listOf("object", BUCKET) -> {
                // Storage answers a missing object with HTTP 400, which `exists` reads as false.
                val objectPath = segments.drop(2).joinToString("/")
                json("", if (objectPath in files) HttpStatusCode.OK else HttpStatusCode.BadRequest)
            }
            request.method == HttpMethod.Post &&
                segments.take(2) == listOf("object", BUCKET) &&
                isUploadFailing ->
                json(
                    """{"statusCode":"500","error":"internal","message":"failing on purpose"}""",
                    HttpStatusCode.InternalServerError,
                )
            request.method == HttpMethod.Post && segments.take(2) == listOf("object", BUCKET) -> {
                val objectPath = segments.drop(2).joinToString("/")
                if (uploadsFindTheirFileStored) files[objectPath] = body
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

    /**
     * Stores [incoming] in [rows], as an insert or, with a `resolution` preference, an upsert on
     * the id: duplicates kept as stored, or merged. Returns the rows it wrote.
     */
    private fun upserted(
        rows: MutableList<JsonObject>,
        incoming: List<JsonObject>,
        prefer: String,
    ): List<JsonObject> {
        val written = mutableListOf<JsonObject>()
        for (row in incoming) {
            val index = rows.indexOfFirst { it["id"] == row["id"] }
            when {
                index < 0 -> rows += row
                "resolution=ignore-duplicates" in prefer -> continue
                "resolution=merge-duplicates" in prefer ->
                    rows[index] = JsonObject(rows[index] + row)
                else -> rows += row
            }
            written += row
        }
        return written
    }

    /** The samples' two database functions, for the one anonymous identity. */
    private fun MockRequestHandleScope.function(name: String): HttpResponseData {
        val tasks = tables.getValue("tasks")
        val result =
            when (name) {
                "complete_all_tasks" -> {
                    val open = tasks.indices.filter { tasks[it]["done"] == JsonPrimitive(false) }
                    open.forEach {
                        tasks[it] =
                            JsonObject(
                                tasks[it] + ("done" to JsonPrimitive(true)) + (UPDATED_AT to now())
                            )
                    }
                    open.size
                }
                "tasks_done_count" -> tasks.count { it["done"] == JsonPrimitive(true) }
                else -> error("FakeSupabaseServer has no function $name")
            }
        return json(result.toString(), HttpStatusCode.OK)
    }

    /**
     * The schema's checks: a task's title is not blank and at most 200 characters, else 23514; a
     * photo's task exists, else 23503.
     */
    private fun MockRequestHandleScope.refusalOf(
        table: String,
        row: JsonObject,
    ): HttpResponseData? {
        if (table == "task_photos") return missingTaskOf(row)
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

    /** The foreign key from a photo to its task: PostgREST's 409 with 23503 when it names none. */
    private fun MockRequestHandleScope.missingTaskOf(photo: JsonObject): HttpResponseData? {
        val taskId = photo["task_id"]?.jsonPrimitive?.contentOrNull
        if (tables.getValue("tasks").any { it["id"]?.jsonPrimitive?.contentOrNull == taskId })
            return null
        return json(
            """{"code":"23503","details":null,"hint":null,"message":"insert or update on table \"task_photos\" violates foreign key constraint \"task_photos_task_fkey\""}""",
            HttpStatusCode.Conflict,
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
        val offset = request.url.parameters["offset"]?.toInt() ?: 0
        val limit = minOf(request.url.parameters["limit"]?.toInt() ?: maxRows, maxRows)
        return ordered.drop(offset).take(limit).map { projected(it, request) }
    }

    /** [row] with only the columns the request's `select` names; every column for `*` or none. */
    private fun projected(row: JsonObject, request: HttpRequestData): JsonObject {
        val columns = request.url.parameters["select"]?.takeUnless { it == "*" }?.split(',')
        return if (columns == null) row else JsonObject(row.filterKeys { it in columns })
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
                put(UPDATED_AT, now())
            }
        )

    /**
     * A new `updated_at`, later than every earlier one even within a clock tick, so a version never
     * repeats: two changes in one microsecond would otherwise look like none to a precondition.
     */
    private fun now(): JsonPrimitive {
        val clockNow = Clock.System.now()
        lastStamp = if (clockNow > lastStamp) clockNow else lastStamp + 1.microseconds
        return JsonPrimitive(lastStamp.toString())
    }

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
