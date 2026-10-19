// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import io.github.nesfeder.supabase.offline.Offline
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.io.IOException

/**
 * A server for one test, scripted through a Ktor `MockEngine`: PostgREST answers every read with
 * [rows] and every write with [writtenRows], and Storage serves [objectBytes] for every object.
 * While [online] is `false`, every request fails at the transport.
 */
class ScriptedServer(
    private val rows: String = """[{"id":1,"title":"Buy milk"}]""",
    private val objectBytes: ByteArray = "a photo".encodeToByteArray(),
    private val writtenRows: String = "[]",
) {
    /** Flipped by a test to take the server offline. */
    var online: Boolean = true

    /** Every PostgREST write that reached the server, in order. */
    val writes: MutableList<HttpRequestData> = mutableListOf()

    /**
     * A client of this server with PostgREST, Storage and the offline plugin installed, the plugin
     * configured by [offline]. PostgREST retries are off, so a failure surfaces at once.
     */
    fun supabaseClient(offline: Offline.Config.() -> Unit): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = "https://unit-test.supabase.co",
            supabaseKey = "unit-test-anon-key",
        ) {
            httpEngine = MockEngine { request ->
                if (!online) throw IOException("network down")
                when {
                    request.url.encodedPath.startsWith("/storage/") ->
                        respond(
                            content = objectBytes,
                            status = HttpStatusCode.OK,
                            headers =
                                headersOf(
                                    HttpHeaders.ContentType,
                                    ContentType.Application.OctetStream.toString(),
                                ),
                        )
                    request.method == HttpMethod.Get -> respondJson(rows)
                    else -> {
                        writes += request
                        respondJson(writtenRows, HttpStatusCode.Created)
                    }
                }
            }
            install(Postgrest) { maxRetries = 0 }
            // supabase-kt's own default keeps resumable uploads in the platform's settings
            install(Storage) { resumable { cache = MemoryResumableCache() } }
            install(Offline, offline)
        }
}

/** A JSON response of [body] with [status]. */
internal fun MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData =
    respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
