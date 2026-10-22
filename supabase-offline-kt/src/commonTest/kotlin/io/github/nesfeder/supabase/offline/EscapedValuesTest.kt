// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import io.github.jan.supabase.postgrest.query.filter.TextSearchType
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.everyFlowReadStrategy
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * An `in` element, or a value inside an `or { }` or `and { }` group, holding `"` or `\` and none of
 * `,.:()` is refused before anything is sent, queued or read from the cache, for reads and writes,
 * online and offline: supabase-kt escapes such a value but does not quote it, and the server reads
 * the escapes as the value, so the request would match other rows than the call names. A value that
 * also holds one of `,.:()` goes out quoted, and a top-level comparison as written; both are sent
 * as before.
 */
class EscapedValuesTest {

    private val server = RecordingPostgrestServer(answer = { okJson(ROWS) })

    /** [value] in an `in` list and inside groups, at the top level and nested, negated or not. */
    private fun placements(value: String): List<Pair<String, PostgrestFilterBuilder.() -> Unit>> =
        listOf(
            "in" to { isIn(COLUMN, listOf("ok", value)) },
            "in" to { filterNot(COLUMN, FilterOperator.IN, listOf(value)) },
            "eq" to
                {
                    or {
                        eq(COLUMN, value)
                        eq("done", true)
                    }
                },
            "ilike" to
                {
                    and {
                        eq("done", true)
                        ilike(COLUMN, value)
                    }
                },
            "neq" to { or { filterNot(COLUMN, FilterOperator.NEQ, value) } },
            "in" to
                {
                    and {
                        eq("done", false)
                        or {
                            eq("title", "a")
                            isIn(COLUMN, listOf(value))
                        }
                    }
                },
            "gt" to { or(referencedTable = "authors") { gt(COLUMN, value) } },
        )

    /** Runs [block] against a client whose server answers, then one whose transport fails. */
    private suspend fun onlineAndOffline(block: suspend (OfflineClient) -> Unit) {
        listOf(true, false).forEach { online ->
            server.answer = if (online) ({ okJson(ROWS) }) else ({ connectivityFailure() })
            val client = newTestOfflineClient(supabase = server.supabaseClient())
            try {
                block(client)
            } finally {
                client.close()
            }
        }
    }

    /**
     * Every read and write with [conditions] is refused, naming [operator] and not the value, of
     * which [fingerprint] is a part; nothing is sent or queued.
     */
    private suspend fun assertRefusedSendingNothing(
        fingerprint: String,
        operator: String,
        conditions: PostgrestFilterBuilder.() -> Unit,
    ) {
        val request: PostgrestRequestBuilder.() -> Unit = { filter(conditions) }
        onlineAndOffline { client ->
            val table = client.from(TABLE)
            val failures = buildList {
                listOf(ReadStrategy.NetworkOnly, ReadStrategy.CacheFirst()).forEach { strategy ->
                    add(assertRefused { table.select(readStrategy = strategy, request = request) })
                }
                everyFlowReadStrategy.forEach { strategy ->
                    add(
                        assertRefused {
                            table.selectAsFlow(readStrategy = strategy, request = request).first()
                        }
                    )
                }
                listOf(MutationStrategy.OnlineOnly, MutationStrategy.QueueIfOffline).forEach {
                    add(assertRefused { table.update({ set("done", true) }, it, request) })
                    add(assertRefused { table.delete(it, request) })
                }
            }
            failures.forEach { failure -> assertNamesOnlyTheColumn(failure, operator, fingerprint) }
            assertEquals(0, client.pendingMutationCount())
        }
        assertEquals(emptyList(), server.requests)
    }

    private suspend fun assertRefused(call: suspend () -> Unit): IllegalArgumentException =
        assertFailsWith<IllegalArgumentException> { call() }

    private fun assertNamesOnlyTheColumn(
        failure: IllegalArgumentException,
        operator: String,
        fingerprint: String,
    ) {
        val message = failure.message.orEmpty()
        assertTrue("`$COLUMN`" in message, "the message does not name the column: $message")
        assertTrue("`$operator`" in message, "the message does not name the operator: $message")
        assertFalse(fingerprint in message, "the message carries the value: $message")
    }

    @Test
    fun a_value_holding_a_quote_is_refused_and_nothing_is_sent_or_queued() = runTest {
        placements(QUOTED).forEach { (operator, filter) ->
            assertRefusedSendingNothing("Say", operator, filter)
        }
    }

    @Test
    fun a_value_holding_a_backslash_is_refused_and_nothing_is_sent_or_queued() = runTest {
        placements(BACKSLASHED).forEach { (operator, filter) ->
            assertRefusedSendingNothing("50", operator, filter)
        }
    }

    @Test
    fun quoted_values_top_level_comparisons_and_literals_are_sent_as_before() = runTest {
        // Given
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        val table = client.from(TABLE)

        // When — a value beside one of ,.:() goes out quoted, which the server reads as written
        table.select(readStrategy = ReadStrategy.NetworkOnly) {
            filter { isIn(COLUMN, listOf("a,b", "x", "say \"hi\".")) }
        }
        val deleted =
            table.delete(MutationStrategy.OnlineOnly) {
                filter {
                    or {
                        eq(COLUMN, "Say \"hi\", ok")
                        eq("note", "a:b\\c")
                    }
                }
            }
        // A top-level comparison is sent as written, and the server reads it so
        table.select(readStrategy = ReadStrategy.NetworkOnly) { filter { eq(COLUMN, QUOTED) } }
        // A pattern list is read by the server as an array literal, whose backslashes are escapes;
        // an array literal or a range of the array operators and a text search query are sent as
        // written
        table.select(readStrategy = ReadStrategy.NetworkOnly) {
            filter {
                likeAll(COLUMN, listOf(BACKSLASHED))
                or {
                    likeAny("note", listOf(BACKSLASHED))
                    filter("tags", FilterOperator.CS, listOf("a\\b"))
                    textSearch("body", "a\\b", TextSearchType.NONE)
                    filter("during", FilterOperator.OV, "2026-01-01" to "2026-02-01")
                    filter("labels", FilterOperator.CS, listOf("\"a}b\""))
                    textSearch("tsv", "x.eq.a\\b", TextSearchType.NONE)
                    eq("tag", "{a")
                    eq("slot", "[b")
                    filter("hour", FilterOperator.SL, "1" to "5")
                }
            }
        }

        // Then
        assertIs<MutationOutcome.Applied>(deleted)
        assertEquals(
            listOf(
                """in.("a,b",x,"say \"hi\".")""",
                null,
                """eq.Say "hi"""",
                """like(all).{%50\\%%}""",
            ),
            server.requests.map { it.query[COLUMN] },
        )
        assertEquals(
            """(code.eq."Say \"hi\", ok",note.eq."a:b\\c")""",
            server.requests[1].query["or"],
        )
        assertEquals(
            """(note.like(any).{%50\\%%},tags.cs.{a\b},body.fts.a\b,""" +
                """during.ov.[2026-01-01,2026-02-01],labels.cs.{"a}b"},tsv.fts.x.eq.a\b,tag.eq.{a,slot.eq.[b,hour.sl.(1,5))""",
            server.requests[3].query["or"],
        )
        client.close()
    }

    private companion object {
        const val TABLE = "codes"
        const val COLUMN = "code"
        const val ROWS = """[{"id":1,"code":"a"}]"""

        /** A value with a double quote: `Say "hi"`. */
        const val QUOTED = "Say \"hi\""

        /** A `like` pattern with an escaped percent sign: `%50\%%`. */
        const val BACKSLASHED = "%50\\%%"
    }
}
