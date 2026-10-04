// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * fixtures/request-rendering.json: every description in it produces the request it records, or
 * fails with the error kind it records before anything is sent. The Swift package's tests read the
 * same file.
 */
class RequestRenderingFixtureTest {

    private val fixture = Json.parseToJsonElement(RENDERING_FIXTURE_JSON).jsonObject

    @Test
    fun every_case_produces_the_request_the_fixture_records() = bridgeTest {
        // Given — every case of the fixture
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }

        // When — each runs on a client of its own
        val mismatches = cases.flatMap { case -> mismatchesOf(case) }

        // Then
        assertEquals(emptyList(), mismatches)
        assertTrue(cases.size >= 30, "the fixture holds ${cases.size} cases")
    }

    @Test
    fun the_fixture_names_the_url_the_tests_use() {
        // Then — a request's recorded path is relative to this URL
        assertEquals(TEST_URL, fixture.getValue("baseUrl").jsonPrimitive.content)
    }

    /** What [case] got wrong, one line each; empty when it matches. */
    private suspend fun mismatchesOf(case: JsonObject): List<String> {
        val name = case.getValue("name").jsonPrimitive.content
        val server = RecordingServer()
        val client = openTestClient(server)
        val error = run(client, case)
        client.closeAndWait()
        val expectedError = case["error"]?.jsonPrimitive?.content
        if (expectedError != null) {
            return buildList {
                if (error?.kind?.name != expectedError) add("$name: expected $expectedError, got $error")
                if (server.requests.isNotEmpty()) add("$name: a refused description sent a request")
            }
        }
        val request =
            server.requests.firstOrNull() ?: return listOf("$name: nothing was sent ($error)")
        return requestMismatches(name, case.getValue("request").jsonObject, request)
    }

    /** Runs [case]'s call on [client] and returns its error, if any. */
    private suspend fun run(client: BridgeClient, case: JsonObject): BridgeError? {
        val description = case.getValue("description").toString()
        val payload = case["payload"]?.jsonPrimitive?.content
        return when (val call = case.getValue("call").jsonPrimitive.content) {
            "select" -> client.selectAndWait(description).second
            "mutate" -> client.mutateAndWait(description, payload).second
            "rpc" -> {
                val probe = CompletionProbe<BridgeQueryResult>()
                client.rpc(description, payload, probe.completion)
                probe.await().second
            }
            else -> error("unknown call $call")
        }
    }

    /** How [request] differs from [expected]. */
    private fun requestMismatches(
        name: String,
        expected: JsonObject,
        request: RecordedRequest,
    ): List<String> = buildList {
        val method = expected.getValue("method").jsonPrimitive.content
        if (request.method != method) add("$name: method ${request.method}, expected $method")
        val path = expected.getValue("path").jsonPrimitive.content
        if (request.path != path) add("$name: path ${request.path}, expected $path")
        val query =
            expected.getValue("query").jsonArray.map { pair ->
                pair.jsonArray[0].jsonPrimitive.content to pair.jsonArray[1].jsonPrimitive.content
            }
        if (request.queryParameters != query) {
            add("$name: query ${request.queryParameters}, expected $query")
        }
        expected["headers"]?.jsonObject?.forEach { (header, value) ->
            val sent = request.header(header)
            if (sent != value.jsonPrimitive.content) {
                add("$name: header $header is $sent, expected ${value.jsonPrimitive.content}")
            }
        }
        val body = expected["body"]
        val sentBody = request.body.ifEmpty { null }?.let(Json::parseToJsonElement)
        if (sentBody != body) add("$name: body $sentBody, expected $body")
    }
}
