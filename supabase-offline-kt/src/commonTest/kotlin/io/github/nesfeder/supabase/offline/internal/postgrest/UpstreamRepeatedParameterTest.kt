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
 * What supabase-kt itself puts on the wire for two top-level filters on one column: every value, as
 * a repeated parameter, which PostgREST ANDs. The library sends that call, captures one condition
 * per value and replays them all; when upstream sends only some of the values, this fails.
 */
class UpstreamRepeatedParameterTest {

    @Test
    fun supabase_kt_sends_every_one_of_two_top_level_filters_on_one_column() = runTest {
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
            listOf("gte.1", "lte.10"),
            sent,
            "supabase-kt no longer sends every value of a repeated top-level parameter. Restore " +
                "the refusal of two filters on one column (a check that a top-level parameter " +
                "holds one value, run by refusingMisreadFilters and both read paths of " +
                "QueryReader) before moving the pin: such a request would reach more rows " +
                "than it names, while queryShapeOf, captureFilter and captureFilterExactly assume every " +
                "value applies.",
        )
    }
}
