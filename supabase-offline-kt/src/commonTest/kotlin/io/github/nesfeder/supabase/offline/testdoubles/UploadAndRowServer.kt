// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.toByteString

/**
 * One server answering PostgREST writes and Storage uploads, downloads, `info` and `exists` for
 * tests of queued uploads: it records every request in the order it arrived, so a test can tell
 * whether a file went before or after the rows around it. As hosted Storage does, it refuses an
 * upload that may not overwrite an object it already holds as a duplicate.
 *
 * A test sets the switches below on its own thread while the client's passes send on another, so
 * each is volatile, and [online] is read once per request: a request answered after the test turned
 * the network back on is recorded as reached, however the two interleave.
 */
internal class UploadAndRowServer {

    /**
     * One request as it reached the server.
     *
     * @property method the HTTP method.
     * @property target `rest/<table>` for PostgREST, `upload/<bucket>/<path>`, `exists/...`,
     *   `info/...` or `download/...` for Storage.
     * @property body the request's body, as text.
     * @property authorization the request's `Authorization` header.
     * @property headers the request's headers that a queued upload carries.
     * @property reached whether it reached the server, rather than failing for want of a network.
     */
    data class Recorded(
        val method: String,
        val target: String,
        val body: String,
        val authorization: String?,
        val headers: Map<String, String>,
        val reached: Boolean,
    )

    private val recorded = AtomicReference<List<Recorded>>(emptyList())

    /** Every request received, in order. */
    val requests: List<Recorded>
        get() = recorded.load()

    /** The targets of every write that reached the server, PostgREST's and Storage's, in order. */
    fun writes(): List<String> =
        requests
            .filter { request ->
                request.reached &&
                    (request.target.startsWith("rest/") && request.method != "GET" ||
                        request.target.startsWith("upload/"))
            }
            .map { request -> request.target }

    /** The uploads that reached Storage. */
    fun uploads(): List<Recorded> = requests.filter { request ->
        request.reached && request.target.startsWith("upload/")
    }

    /** Stored objects, keyed `"bucket/objectPath"`. */
    val objects: MutableStateFlow<Map<String, ByteArray>> = MutableStateFlow(emptyMap())

    /** When `false`, every request fails at the transport layer, as it does with no network. */
    @Volatile var online: Boolean = true

    /**
     * How Storage answers an upload of `bucket/path`: `null` stores it; otherwise a refusal, or a
     * store whose answer is lost.
     */
    @Volatile var uploadAnswer: ((key: String) -> UploadAnswer?)? = null

    /**
     * The ETag `info` answers for an object's bytes, or `null` for none: by default their MD5,
     * quoted, as S3 gives a single-part upload's.
     */
    @Volatile
    var etagOf: (ByteArray) -> String? = { bytes -> "\"" + bytes.toByteString().md5().hex() + "\"" }

    /**
     * How Storage answers `info` of `bucket/path` instead of from [objects], or `null` for that.
     */
    @Volatile var infoAnswer: ((key: String) -> InfoAnswer?)? = null

    /** What Storage does with an `info` request instead of answering it from [objects]. */
    sealed interface InfoAnswer {
        /** Answers [status] with [body]. */
        data class Respond(val status: Int, val body: String) : InfoAnswer

        /** Fails at the transport layer, as with no network. */
        data object Unreachable : InfoAnswer
    }

    /** How many requests arrived, as a flow a test can wait on. */
    val arrived: MutableStateFlow<Int> = MutableStateFlow(0)

    /** What Storage does with an upload instead of storing it and answering. */
    sealed interface UploadAnswer {
        /** Refuses it with [status] and Storage's error body. */
        data class Refuse(val status: Int, val innerStatus: Int, val error: String) : UploadAnswer

        /** Stores it, then the answer never arrives. */
        data object LoseAnswer : UploadAnswer
    }

    /** The engine to install on a client. */
    val engine: MockEngine = MockEngine { request ->
        val segments =
            request.url.encodedPath.removePrefix("/").split('/').map { it.decodeURLPart() }
        val target =
            when {
                segments.take(2) == listOf("rest", "v1") ->
                    "rest/" + segments.drop(2).joinToString("/")
                segments.take(4) == listOf("storage", "v1", "object", "authenticated") ->
                    "download/" + segments.drop(4).joinToString("/")
                segments.take(4) == listOf("storage", "v1", "object", "info") ->
                    "info/" + segments.drop(4).joinToString("/")
                segments.take(3) == listOf("storage", "v1", "object") &&
                    request.method == HttpMethod.Head ->
                    "exists/" + segments.drop(3).joinToString("/")
                segments.take(3) == listOf("storage", "v1", "object") ->
                    "upload/" + segments.drop(3).joinToString("/")
                else -> error("UploadAndRowServer has no route for ${request.url.encodedPath}")
            }
        val bytes = request.body.toByteArray()
        // Read once: recorded as it is answered, whatever the test does to it meanwhile
        val reached = online
        recorded.update { list ->
            list +
                Recorded(
                    method = request.method.value,
                    target = target,
                    body = bytes.decodeToString(),
                    authorization = request.headers[HttpHeaders.Authorization],
                    headers =
                        listOf("x-upsert", "x-metadata")
                            .mapNotNull { name -> request.headers[name]?.let { name to it } }
                            .toMap() +
                            listOfNotNull(
                                request.body.contentType?.let { type ->
                                    HttpHeaders.ContentType to type.toString()
                                }
                            ),
                    reached = reached,
                )
        }
        arrived.update { count -> count + 1 }
        if (!reached) throw IOException("network unreachable")
        val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val key = target.substringAfter('/')
        when {
            target.startsWith("rest/") ->
                respond(
                    "[]",
                    if (request.method == HttpMethod.Get) HttpStatusCode.OK
                    else HttpStatusCode.Created,
                    json,
                )
            target.startsWith("exists/") ->
                if (key in objects.value) respond(ByteArray(0), HttpStatusCode.OK)
                else
                    respond(
                        """{"statusCode":"404","error":"not_found","message":"Object not found"}""",
                        HttpStatusCode.BadRequest,
                        json,
                    )
            target.startsWith("info/") -> {
                when (val answer = infoAnswer?.invoke(key)) {
                    is InfoAnswer.Respond ->
                        return@MockEngine respond(
                            answer.body,
                            HttpStatusCode.fromValue(answer.status),
                            json,
                        )
                    InfoAnswer.Unreachable -> throw IOException("network unreachable")
                    null -> Unit
                }
                val stored =
                    objects.value[key]
                        ?: return@MockEngine respond(
                            """{"statusCode":"404","error":"not_found","message":"Object not found"}""",
                            HttpStatusCode.BadRequest,
                            json,
                        )
                val etag =
                    etagOf(stored)?.let { tag -> ",\"etag\":${JsonPrimitive(tag)}" }.orEmpty()
                respond(
                    """{"name":"${key.substringAfter('/')}","id":"object","version":"v1",""" +
                        """"created_at":"2026-10-27T00:00:00Z","size":${stored.size},""" +
                        """"content_type":"application/octet-stream"$etag}""",
                    HttpStatusCode.OK,
                    json,
                )
            }
            target.startsWith("download/") -> {
                val stored =
                    objects.value[key]
                        ?: return@MockEngine respond(
                            """{"statusCode":"404","error":"not_found","message":"Object not found"}""",
                            HttpStatusCode.BadRequest,
                            json,
                        )
                respond(
                    stored,
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType,
                        ContentType.Application.OctetStream.toString(),
                    ),
                )
            }
            else ->
                when (val answer = uploadAnswer?.invoke(key)) {
                    is UploadAnswer.Refuse ->
                        respond(
                            """{"statusCode":"${answer.innerStatus}","error":"${answer.error}",""" +
                                """"message":"refused by the test"}""",
                            HttpStatusCode.fromValue(answer.status),
                            json,
                        )
                    UploadAnswer.LoseAnswer -> {
                        objects.update { stored -> stored + (key to bytes) }
                        throw IOException("the answer was lost")
                    }
                    null if request.headers["x-upsert"] != "true" && key in objects.value ->
                        respond(
                            """{"statusCode":"409","error":"Duplicate",""" +
                                """"message":"The resource already exists"}""",
                            HttpStatusCode.BadRequest,
                            json,
                        )
                    null -> {
                        objects.update { stored -> stored + (key to bytes) }
                        respond(
                            """{"Key":"$key","Id":"id-${objects.value.size}"}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    }
                }
        }
    }

    /** A supabase-kt client with PostgREST and Storage, and no Auth, whose requests reach here. */
    fun supabaseClient(): SupabaseClient =
        newTestSupabaseClient(engine = engine) {
            install(Postgrest) { maxRetries = 0 }
            install(Storage) { resumable { cache = FakeResumableCache() } }
        }

    /**
     * A supabase-kt client with PostgREST, Storage and Auth over an in-memory session store, signed
     * out once it returns, whose requests reach here.
     */
    suspend fun supabaseClientWithAuth(): SupabaseClient {
        val supabase =
            newTestSupabaseClient(engine = engine) {
                install(Postgrest) { maxRetries = 0 }
                install(Storage) { resumable { cache = FakeResumableCache() } }
                install(Auth) {
                    sessionManager = MemorySessionManager()
                    codeVerifierCache = MemoryCodeVerifierCache()
                    autoLoadFromStorage = false
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                }
            }
        supabase.auth.sessionStatus.first { status -> status !is SessionStatus.Initializing }
        return supabase
    }
}
