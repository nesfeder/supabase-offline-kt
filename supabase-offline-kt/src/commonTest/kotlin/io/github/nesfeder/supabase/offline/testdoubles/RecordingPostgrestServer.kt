// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.io.IOException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A scripted PostgREST server on a Ktor [MockEngine] that records every request it receives, so a
 * test can assert exactly what the engine put on the wire: method, table, query parameters
 * (filters, `on_conflict`, `columns`), the `Prefer` header, the schema headers and the body.
 *
 * Build a client on it with [supabaseClient]. Every request is answered by [answer], which can
 * return a canned response ([okJson], [postgrestError]) or throw to simulate a transport failure
 * ([connectivityFailure]).
 *
 * @property answer decides the response for each recorded request; by default `200 []`.
 * @param engineDispatcher where the engine runs requests; by default its own I/O threads. A test
 *   that passes its own test dispatcher keeps every request on the test scheduler.
 */
internal class RecordingPostgrestServer(
    var answer: suspend MockRequestHandleScope.(RecordedRequest) -> HttpResponseData = {
        okJson("[]")
    },
    engineDispatcher: CoroutineDispatcher? = null,
) {

    /**
     * One request as it reached the server.
     *
     * @property method the HTTP method, e.g. `"POST"`.
     * @property table the last path segment, i.e. the table or view the request addresses.
     * @property query the decoded query parameters. supabase-kt sends one value per name.
     * @property prefer the raw `Prefer` header, or `null` when absent.
     * @property contentProfile the `Content-Profile` header (the schema of a write), or `null`.
     * @property acceptProfile the `Accept-Profile` header (the schema of a read), or `null`.
     * @property body the request body as text; empty when there is none.
     */
    data class RecordedRequest(
        val method: String,
        val table: String,
        val query: Map<String, String>,
        val prefer: String?,
        val contentProfile: String?,
        val acceptProfile: String?,
        val body: String,
    ) {
        /**
         * The `Accept` header values, comma-joined, or `null`: the shape a read asks for. Outside
         * equality, so a test that pins a write's wire form need not spell the client's own value.
         */
        var accept: String? = null

        /**
         * The `Authorization` header, or `null`: whose token the request carried. Outside equality
         * too.
         */
        var authorization: String? = null
    }

    /** Every request received, in order. */
    val requests: MutableList<RecordedRequest> = mutableListOf()

    /** The engine to install on a client (see [supabaseClient]). */
    val engine: MockEngine =
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = engineDispatcher
                addHandler { request ->
                    val recorded =
                        RecordedRequest(
                                method = request.method.value,
                                table = request.url.encodedPath.substringAfterLast('/'),
                                query =
                                    request.url.parameters.names().associateWith { name ->
                                        request.url.parameters[name].orEmpty()
                                    },
                                prefer = request.headers["Prefer"],
                                contentProfile = request.headers["Content-Profile"],
                                acceptProfile = request.headers["Accept-Profile"],
                                body = request.body.toByteArray().decodeToString(),
                            )
                            .apply {
                                accept = request.headers.getAll("Accept")?.joinToString(",")
                                authorization = request.headers["Authorization"]
                            }
                    requests += recorded
                    answer(recorded)
                }
            }
        )

    /**
     * A supabase-kt client whose PostgREST requests reach this server. Automatic retries are off,
     * so a scripted failure surfaces on the first attempt.
     */
    fun supabaseClient(): SupabaseClient =
        newTestSupabaseClient(engine = engine) {
            install(Postgrest) { maxRetries = 0 }
        }

    companion object {
        /**
         * A `200 OK` JSON response carrying [body].
         *
         * @param extraHeaders further response headers, e.g. `Preference-Applied`.
         */
        fun MockRequestHandleScope.okJson(
            body: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): HttpResponseData =
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers =
                    headers {
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        extraHeaders.forEach { (name, value) -> append(name, value) }
                    },
            )

        /**
         * A PostgREST error response: [status] with PostgREST's error body, which supabase-kt
         * raises as a `PostgrestRestException` carrying that status and these fields.
         *
         * @param code the Postgres SQLSTATE or PostgREST error code.
         * @param message PostgREST's `message` field.
         * @param details PostgREST's `details` field, or `null`.
         * @param hint PostgREST's `hint` field, or `null`.
         */
        fun MockRequestHandleScope.postgrestError(
            status: HttpStatusCode,
            code: String = "42501",
            message: String = "rejected by the test server",
            details: String? = null,
            hint: String? = null,
        ): HttpResponseData =
            respond(
                content = buildJsonObject {
                        put("code", code)
                        put("message", message)
                        put("details", details)
                        put("hint", hint)
                    }
                        .toString(),
                status = status,
                headers =
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )

        /** A transport failure: the request never reaches a server. */
        fun connectivityFailure(): Nothing = throw IOException("network unreachable")
    }
}
