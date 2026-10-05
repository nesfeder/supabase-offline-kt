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
 * What supabase-kt itself puts on the wire for two top-level filters on one column: the first value
 * only. The library refuses that call, and three of its functions read a column's first value alone
 * because that is what upstream sends; when upstream sends every value, this fails.
 */
class UpstreamRepeatedParameterTest {

    @Test
    fun supabase_kt_sends_only_the_first_of_two_top_level_filters_on_one_column() = runTest {
        // Given — plain supabase-kt, no offline plugin
        var sent: List<String>? = null
        val supabase =
            newTestSupabaseClient(
                engine =
                    MockEngine { request ->
                        sent = request.url.parameters.getAll("age")
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
        supabase.from("people").select {
            filter {
                gte("age", 1)
                lte("age", 10)
            }
        }

        // Then
        assertEquals(
            listOf("gte.1"),
            sent,
            "supabase-kt no longer sends only the first value of a repeated top-level parameter. " +
                "Revisit the refusal of two filters on one column " +
                "(requireOneValuePerTopLevelParameter) and the three functions that read a " +
                "column's first value alone (queryShapeOf, captureFilter, captureFilterExactly) " +
                "before moving the pin.",
        )
    }
}
