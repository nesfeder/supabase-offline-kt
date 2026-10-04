// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.isFromCache
import io.github.nesfeder.supabase.offline.offline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A description and the Kotlin call it stands for are the same request, and so the same cache
 * entry: rows a Swift read cached answer the Kotlin call offline.
 */
class DescriptionParityTest {

    @Test
    fun a_filtered_ordered_limited_read_matches_its_kotlin_call() =
        parity(
            description =
                """{"v":1,"table":"todos","columns":"id, title","filters":[
               {"kind":"filter","column":"done","operator":"eq","value":"false"}],
               "modifiers":[{"kind":"order","column":"created_at","ascending":false},
               {"kind":"limit","count":20}]}""",
            columns = Columns.raw("id,title"),
        ) {
            filter { eq("done", false) }
            order("created_at", Order.DESCENDING)
            limit(20)
        }

    @Test
    fun lists_negations_and_groups_match_their_kotlin_calls() =
        parity(
            description =
                """{"v":1,"table":"todos","filters":[
               {"kind":"filter","column":"id","operator":"in","values":["7","8"]},
               {"kind":"filter","column":"state","operator":"in","values":["a,b"],"negated":true},
               {"kind":"or","filters":[
                 {"kind":"filter","column":"done","operator":"is","value":"true"},
                 {"kind":"filter","column":"priority","operator":"gt","value":"2"}]}],
               "modifiers":[{"kind":"range","from":0,"to":9}]}""",
            columns = Columns.ALL,
        ) {
            filter {
                isIn("id", listOf(7, 8))
                filterNot("state", FilterOperator.IN, listOf("a,b"))
                or {
                    exact("done", true)
                    gt("priority", 2)
                }
            }
            range(0, 9)
        }

    @Test
    fun ranges_and_pattern_lists_match_their_kotlin_calls() =
        parity(
            description =
                """{"v":1,"table":"events","filters":[
               {"kind":"filter","column":"slot","operator":"sl","range":["1","5"]},
               {"kind":"filter","column":"tags","operator":"cs","values":["x","y"]},
               {"kind":"ilikeAll","column":"title","patterns":["a%","%b"]}]}""",
            table = "events",
            columns = Columns.ALL,
        ) {
            filter {
                sl("slot", 1 to 5)
                contains("tags", listOf("x", "y"))
                ilikeAll("title", listOf("a%", "%b"))
            }
        }

    /**
     * Reads [description] through the bridge, then the same rows through [request] in Kotlin: both
     * send one identical request, and offline the Kotlin call is answered from what the bridge's
     * read cached.
     */
    private fun parity(
        description: String,
        table: String = "todos",
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ) = bridgeTest {
        // Given — a server and two clients of it
        val server = RecordingServer()
        val swiftSide = openTestClient(server)
        val kotlinSide = openTestClient(server)

        // When — each reads once online
        val (bridgeRead, bridgeError) = swiftSide.selectAndWait(description)
        kotlinSide.supabase.offline.from(table).select(columns, ReadStrategy.NetworkOnly, request)

        // Then — the two requests are the same
        assertNull(bridgeError)
        assertEquals(SERVER_ROWS, bridgeRead?.data)
        val (fromBridge, fromKotlin) = server.requests
        assertEquals(fromKotlin.url, fromBridge.url)
        assertEquals(fromKotlin.method, fromBridge.method)
        assertEquals(fromKotlin.header("Prefer"), fromBridge.header("Prefer"))
        assertEquals(fromKotlin.header("Accept"), fromBridge.header("Accept"))

        // And When — offline, the Kotlin call reads only the cache the bridge's read filled
        server.online = false
        val cached =
            swiftSide.supabase.offline
                .from(table)
                .select(columns, ReadStrategy.CacheOnly(), request)

        // Then
        assertTrue(cached.isFromCache)
        assertEquals(SERVER_ROWS, cached.data)
        swiftSide.closeAndWait()
        kotlinSide.closeAndWait()
    }
}
