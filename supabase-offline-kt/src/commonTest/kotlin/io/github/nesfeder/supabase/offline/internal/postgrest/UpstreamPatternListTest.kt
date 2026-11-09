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
 * What supabase-kt itself puts on the wire for a pattern list and for the array literal of `cs`,
 * `cd` and `ov`: an element that is empty, `NULL`, edged with white space or holds one of `, { } "
 * \` goes out quoted, a `"` or `\` in it escaped, and every other element bare, `.:()` included.
 * That is how Postgres reads an array literal, so the library sends such a list as written and
 * refuses only an empty pattern list, which still goes out as `{}`. Replay decodes an array literal
 * by the same rules ([parseArrayLiteral]); when upstream quotes otherwise, this fails.
 */
class UpstreamPatternListTest {

    @Test
    fun supabase_kt_quotes_array_literal_elements_as_postgres_reads_them() = runTest {
        // Given — plain supabase-kt, no offline plugin
        val sent = mutableListOf<String?>()
        val supabase =
            newTestSupabaseClient(
                engine =
                    MockEngine { request ->
                        sent += request.url.parameters["code"] ?: request.url.parameters["tags"]
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
        supabase.from("codes").delete { filter { likeAll("code", emptyList()) } }
        supabase.from("codes").delete { filter { likeAll("code", listOf("")) } }
        supabase.from("codes").select {
            filter { ilikeAny("code", listOf("NULL", " a", "b{c}", "d,e", "f\"g", "h\\i")) }
        }
        supabase.from("codes").select { filter { likeAny("code", listOf("a.b", "c:d", "(e)")) } }
        supabase.from("posts").select { filter { cs("tags", listOf("a,b", "x\"y", "NULL", "")) } }
        supabase.from("posts").select { filter { ov("tags", listOf("p.q", "r s")) } }

        // Then
        assertEquals(
            listOf<String?>(
                "like(all).{}",
                """like(all).{""}""",
                """ilike(any).{"NULL"," a","b{c}","d,e","f\"g","h\\i"}""",
                "like(any).{a.b,c:d,(e)}",
                """cs.{"a,b","x\"y","NULL",""}""",
                "ov.{p.q,r s}",
            ),
            sent,
            "supabase-kt no longer quotes array literal elements as Postgres reads them. Revisit " +
                "the empty pattern list refusal (requireReadablePatternLists), replay's reading " +
                "of a cs, cd or ov literal (parseArrayLiteral), the Swift bridge's and the Swift " +
                "package's pattern-list rules, and the pattern-list and array cases of " +
                "cache-key-fixture.json before moving the pin.",
        )
    }
}
