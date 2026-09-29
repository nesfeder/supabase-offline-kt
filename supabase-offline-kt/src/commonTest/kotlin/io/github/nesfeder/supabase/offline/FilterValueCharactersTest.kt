@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A filter value holding a character that means something in a cache key — `&`, `=`, `?`, `|range=`,
 * `,`, `.`, `(`, `%`, `#`, `+`, a newline — decides which cached list a locally written row belongs
 * to exactly as it does on the server. The cache key is a fingerprint: admission never reads a
 * filter back out of it.
 */
class FilterValueCharactersTest {

    /** Whether the server answers; off, every request fails to connect. */
    private var online = true

    private val server = RecordingPostgrestServer(
        answer = { request ->
            if (!online) connectivityFailure()
            when (request.method) {
                "GET" -> {
                    val value = request.query["company"]?.removePrefix("eq.")
                    val team = request.query["team"]
                    okJson(
                        buildJsonArray {
                            if (value != null) add(row(1, company = value, team = "Ops"))
                            if (team != null) add(row(1, company = "c", team = "Ops"))
                        }.toString(),
                    )
                }
                else -> okJson("")
            }
        },
    )
    private val client = newTestOfflineClient(supabase = server.supabaseClient(), coordinatorScope = idleCoroutineScope())
    private val items = client.from(TABLE)

    private fun companyList(value: String): SelectRequestBuilder.() -> Unit = {
        filter { eq("company", value) }
        order("position", Order.ASCENDING)
    }

    private suspend fun shownIds(table: String, request: SelectRequestBuilder.() -> Unit): List<Int> =
        Json.parseToJsonElement(client.from(table).select(readStrategy = ReadStrategy.CacheOnly(), request = request).data).jsonArray
            .map { element -> element.jsonObject.getValue("id").jsonPrimitive.content.toInt() }

    /**
     * Writes, for each value, a row of that company and one of another into the value's own table, with
     * the connection [online] or not, and returns what went wrong: the list of the company must show its
     * own row, and, where the engine can tell two text values apart, only that.
     */
    private suspend fun wrongListsAfterWriting(writeOnline: Boolean): List<String> = VALUES.mapIndexedNotNull { index, value ->
        // Given — the list of one company cached, each value in its own table
        val table = "$TABLE$index"
        online = true
        client.from(table).select(readStrategy = ReadStrategy.NetworkOnly, request = companyList(value))
        online = writeOnline

        // When — a row for that company and a row for another are written
        client.from(table).insertJson(JsonArray(listOf(row(2, company = value, team = "Ops"))))
        client.from(table).insertJson(JsonArray(listOf(row(3, company = value + "x", team = "Ops"))))

        // Then
        val shown = shownIds(table, companyList(value))
        val expected = if (value in UNDECIDABLE_FROM_ANOTHER_VALUE) shown.takeIf { 2 in it } else listOf(1, 2)
        if (shown == expected) null else "<$value> showed $shown"
    }

    @Test
    fun a_queued_row_shows_in_its_own_list_whatever_its_filter_value_holds() = runTest {
        assertEquals(emptyList(), wrongListsAfterWriting(writeOnline = false))
    }

    @Test
    fun an_online_row_lands_in_its_own_list_whatever_its_filter_value_holds() = runTest {
        assertEquals(emptyList(), wrongListsAfterWriting(writeOnline = true))
    }

    @Test
    fun an_in_list_member_holding_an_ampersand_admits_its_own_row() = runTest {
        // Given — a list of two teams, one holding `&`, cached; then no connection
        val teams: SelectRequestBuilder.() -> Unit = { filter { isIn("team", listOf("R&D", "Ops")) } }
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = teams)
        online = false

        // When
        items.insertJson(JsonArray(listOf(row(2, company = "c", team = "R&D"))))
        items.insertJson(JsonArray(listOf(row(3, company = "c", team = "QA"))))

        // Then
        assertEquals(listOf(1, 2), shownIds(TABLE, teams))
    }

    private companion object {
        const val TABLE = "companies"

        /** Values holding each character with a meaning in a cache key's format. */
        val VALUES = listOf(
            "AT&T", "Food & Drink", "a=b", "x?y", "p|range=0-5", "a,b", "Inc.", "(paren)", "50%", "a#b", "a+b", "line\nbreak",
        )

        /**
         * Values the engine cannot tell from another value without knowing the column's type, so a row
         * of another company may show too: unknown admits.
         */
        val UNDECIDABLE_FROM_ANOTHER_VALUE = setOf("(paren)", "50%", "line\nbreak")

        fun row(id: Int, company: String, team: String) = buildJsonObject {
            put("id", id)
            put("company", JsonPrimitive(company))
            put("team", team)
            put("position", id)
        }
    }
}
