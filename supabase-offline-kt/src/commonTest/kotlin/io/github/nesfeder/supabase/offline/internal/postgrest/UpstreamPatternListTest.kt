// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.from
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * What supabase-kt itself puts on the wire for a pattern list: a pattern holding none of `,.:()`
 * goes out unquoted, so an empty list or pattern, `NULL`, edge white space and braces reach the
 * server as another list. The library refuses such a list; when upstream quotes them, this fails.
 */
class UpstreamPatternListTest {

    @Test
    fun supabase_kt_sends_an_empty_pattern_and_other_misread_patterns_unquoted() = runTest {
        // Given — plain supabase-kt, no offline plugin
        val sent = mutableListOf<String?>()
        val supabase =
            newTestSupabaseClient(
                engine =
                    MockEngine { request ->
                        sent += request.url.parameters["code"]
                        respond(
                            "[]",
                            HttpStatusCode.OK,
                            headersOf(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json.toString(),
                            ),
                        )
                    }
            )

        // When
        supabase.from("codes").delete { filter { likeAll("code", listOf("")) } }
        supabase.from("codes").select { filter { ilikeAny("code", listOf("NULL", " a", "b{c}")) } }

        // Then
        assertEquals(
            listOf<String?>("like(all).{}", "ilike(any).{NULL, a,b{c}}"),
            sent,
            "supabase-kt no longer sends these patterns unquoted. Revisit the refusal of a " +
                "pattern list the server would misread (requireReadablePatternLists), and the " +
                "Swift package's matching refusal, before moving the pin.",
        )
    }
}
