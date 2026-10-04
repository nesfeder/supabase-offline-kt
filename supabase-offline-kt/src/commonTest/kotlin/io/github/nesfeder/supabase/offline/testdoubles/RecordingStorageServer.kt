// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.IOException

/**
 * A Supabase Storage server on a Ktor [MockEngine] that answers supabase-kt's `BucketApi` as the
 * service does, recording each request as it reached the wire. It serves downloads, transformed or
 * not, uploads, updates and signed-URL uploads.
 *
 * A transformed download answers the transform's query before the original's bytes
 * (`"render?height=64&width=64:" + original`). A missing object answers HTTP 400 with a
 * `statusCode` of 404, which supabase-kt raises as a `NotFoundRestException`.
 */
internal class RecordingStorageServer {

    /**
     * One request as it reached the server.
     *
     * @property method the HTTP method.
     * @property encodedPath the URL path exactly as sent, percent-encoding included.
     * @property query the decoded query parameters.
     */
    data class RecordedRequest(
        val method: String,
        val encodedPath: String,
        val query: Map<String, String>,
    )

    /** Stored objects, keyed `"bucket/objectPath"` with the object path decoded. */
    val objects: MutableMap<String, ByteArray> = mutableMapOf()

    /** Every request received, in order. */
    val requests: MutableList<RecordedRequest> = mutableListOf()

    /** When `false`, every request fails at the transport layer, as it does with no network. */
    var online: Boolean = true

    /**
     * When set, every request waits for it before answering, so a test can act while a download is
     * in flight. Complete it to let the requests through.
     */
    var gate: CompletableDeferred<Unit>? = null

    /**
     * When set, every download is refused with this status and Storage's error body for it, as a
     * policy (`403`) or a fault (`500`) refuses one; the object stays stored.
     */
    var downloadRefusal: HttpStatusCode? = null

    /**
     * Downloads Storage answers with a redirect: the `bucket/path` key to the `bucket/path` the
     * `302`'s `Location` names, as a proxy or CDN in front of Storage may.
     */
    val redirects: MutableMap<String, String> = mutableMapOf()

    /** When set, each of [redirects] points to plain http, with a page as its body. */
    var redirectsToPlainHttp: Boolean = false

    /** Completed each time a request reaches the server (and before it waits on [gate]). */
    var requestArrived: CompletableDeferred<Unit> = CompletableDeferred()

    /** The engine to install on a client (see [supabaseClient]). */
    val engine: MockEngine = MockEngine { request ->
        val recorded =
            RecordedRequest(
                method = request.method.value,
                encodedPath = request.url.encodedPath,
                query =
                    request.url.parameters.names().associateWith { name ->
                        request.url.parameters[name].orEmpty()
                    },
            )
        requests += recorded
        requestArrived.complete(Unit)
        gate?.await()
        if (!online) throw IOException("network unreachable")
        val segments =
            request.url.encodedPath.removePrefix("/").split('/').map { it.decodeURLPart() }
        val redirect =
            redirects.entries.firstOrNull { (from, _) ->
                request.url.encodedPath.endsWith("/$from")
            }
        // storage/v1/<kind...>/<bucket>/<path...>
        when {
            redirect != null -> {
                val target = request.url.toString().replace(redirect.key, redirect.value)
                respond(
                    content = if (redirectsToPlainHttp) "<html>Moved</html>" else "",
                    status = HttpStatusCode.Found,
                    headers =
                        headersOf(
                            HttpHeaders.Location,
                            if (redirectsToPlainHttp) target.replaceFirst("https://", "http://")
                            else target,
                        ),
                )
            }

            segments.take(4) == listOf("storage", "v1", "upload", "resumable") ->
                resumable(request.method.value, segments.drop(4).firstOrNull(), request)

            segments.take(4) == listOf("storage", "v1", "object", "authenticated") ||
                segments.take(4) == listOf("storage", "v1", "object", "public") ->
                download(segments.drop(4), transform = null)

            segments.take(4) == listOf("storage", "v1", "render", "image") ->
                download(
                    segments.drop(5),
                    transform = recorded.query.entries.sortedBy { it.key }.joinToString("&"),
                )

            segments.take(5) == listOf("storage", "v1", "object", "upload", "sign") ->
                store(segments.drop(5).joinToString("/"), request.body.toByteArray())

            segments.take(3) == listOf("storage", "v1", "object") ->
                store(segments.drop(3).joinToString("/"), request.body.toByteArray())

            else -> error("RecordingStorageServer has no route for ${request.url.encodedPath}")
        }
    }

    /**
     * A resumable upload in progress: the object key it writes, its declared length and what
     * arrived.
     */
    private class PendingUpload(val key: String, val length: Long, var received: ByteArray)

    private val pendingUploads = mutableMapOf<String, PendingUpload>()

    /**
     * The TUS endpoints supabase-kt's resumable client speaks: `POST` creates an upload and answers
     * its location, `PATCH` appends a chunk at `Upload-Offset`, `HEAD` reports the offset. The
     * object is stored once its last byte arrives.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun MockRequestHandleScope.resumable(
        method: String,
        uploadId: String?,
        request: HttpRequestData,
    ): HttpResponseData {
        if (method == "POST" && uploadId == null) {
            val metadata =
                request.headers["Upload-Metadata"].orEmpty().split(',').associate { entry ->
                    val (name, value) = entry.trim().split(' ', limit = 2)
                    name to Base64.decode(value).decodeToString()
                }
            val id = "upload-${pendingUploads.size + 1}"
            pendingUploads[id] =
                PendingUpload(
                    key = "${metadata.getValue("bucketName")}/${metadata.getValue("objectName")}",
                    length = request.headers["Upload-Length"]?.toLong() ?: 0L,
                    received = ByteArray(0),
                )
            return respond(
                content = "",
                status = HttpStatusCode.Created,
                headers =
                    headersOf(
                        HttpHeaders.Location,
                        "$TEST_SUPABASE_URL/storage/v1/upload/resumable/$id",
                    ),
            )
        }
        val upload =
            pendingUploads[uploadId]
                ?: error("RecordingStorageServer has no resumable upload $uploadId")
        if (method == "PATCH") {
            upload.received += request.body.toByteArray()
            if (upload.received.size.toLong() == upload.length)
                objects[upload.key] = upload.received
            return respond(
                content = "",
                status = HttpStatusCode.NoContent,
                headers = headersOf("Upload-Offset", upload.received.size.toString()),
            )
        }
        return respond(
            content = "",
            status = HttpStatusCode.OK,
            headers = headersOf("Upload-Offset", upload.received.size.toString()),
        )
    }

    private fun MockRequestHandleScope.store(key: String, bytes: ByteArray): HttpResponseData {
        objects[key] = bytes
        return respond(
            content = """{"Key":"$key","Id":"id-${objects.size}"}""",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun MockRequestHandleScope.download(
        bucketAndPath: List<String>,
        transform: String?,
    ): HttpResponseData {
        downloadRefusal?.let { status ->
            return respond(
                content =
                    """{"statusCode":"${status.value}","error":"refused","message":"${status.description}"}""",
                status = status,
                headers =
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val original =
            objects[bucketAndPath.joinToString("/")]
                ?: return respond(
                    content =
                        """{"statusCode":404,"error":"not_found","message":"Object not found"}""",
                    status = HttpStatusCode.BadRequest,
                    headers =
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
        val body =
            if (transform == null) original else "render?$transform:".encodeToByteArray() + original
        return respond(
            content = body,
            status = HttpStatusCode.OK,
            headers =
                headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
        )
    }

    /**
     * A supabase-kt client with the Storage plugin whose requests reach this server. Resumable
     * uploads keep their state in a [FakeResumableCache]: the default store needs an Android
     * context.
     */
    fun supabaseClient(): SupabaseClient =
        newTestSupabaseClient(engine = engine) {
            install(Storage) {
                resumable { cache = FakeResumableCache() }
            }
        }
}
