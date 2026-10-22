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
 * What supabase-kt itself puts on the wire for a value holding `"` or `\`: an `in` element and a
 * value inside a group are escaped, and quoted only when they also hold one of `,.:()`, so an
 * unquoted one reaches the server with its escapes, which PostgREST reads as the value; a top-level
 * comparison is sent as written. The library refuses the unquoted ones; when upstream quotes them,
 * this fails.
 */
class UpstreamInListEscapingTest {

    @Test
    fun supabase_kt_escapes_a_quote_and_a_backslash_but_quotes_only_beside_a_comma() = runTest {
        // Given — plain supabase-kt, no offline plugin
        val sent = mutableListOf<String?>()
        val supabase =
            newTestSupabaseClient(
                engine =
                    MockEngine { request ->
                        sent += request.url.parameters["title"]
                        sent += request.url.parameters["or"]
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
        supabase.from("todos").select { filter { isIn("title", listOf("Say \"hi\"", "a,b")) } }
        supabase.from("todos").delete {
            filter {
                or {
                    ilike("title", "%50\\%%")
                    eq("note", "x\\y, z")
                }
            }
        }
        supabase.from("todos").select { filter { eq("title", "Say \"hi\"") } }

        // Then
        assertEquals(
            listOf(
                """in.(Say \"hi\","a,b")""",
                null,
                null,
                """(title.ilike.%50\\%%,note.eq."x\\y, z")""",
                """eq.Say "hi"""",
                null,
            ),
            sent,
            "supabase-kt no longer sends these values escaped but unquoted. Revisit the refusal " +
                "of a value the server would read with its escapes (requireReadableEscapedValues), " +
                "the Swift bridge's matching refusal and the Swift package's, before moving the pin.",
        )
    }
}
