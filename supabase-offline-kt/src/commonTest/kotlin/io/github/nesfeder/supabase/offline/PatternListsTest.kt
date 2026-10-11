// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
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
 * A pattern list (`likeAll`, `likeAny`, `ilikeAll`, `ilikeAny`) the server would read as another
 * list is refused before anything is sent, queued or read from the cache: an empty list, or a
 * pattern that is empty, `NULL` in any case, edged with white space or holding a brace, at the top
 * level or inside a group, for reads and writes, online and offline. supabase-kt quotes a pattern
 * only when it holds one of `,.:()`, and `LIKE ALL` over no pattern matches every row.
 */
class PatternListsTest {

    private val server = RecordingPostgrestServer(answer = { okJson(ROWS) })

    /** [patterns] at the top level and in groups, each under one of the four operators. */
    private fun placements(patterns: List<String>): List<PostgrestFilterBuilder.() -> Unit> =
        listOf(
            { likeAll(COLUMN, patterns) },
            { ilikeAny(COLUMN, patterns) },
            { or { likeAny(COLUMN, patterns) } },
            {
                and {
                    eq("done", false)
                    or {
                        ilikeAll(COLUMN, patterns)
                        eq("title", "a")
                    }
                }
            },
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

    /** Every read and write with [conditions] is refused, and nothing is sent or queued. */
    private suspend fun assertRefusedSendingNothing(
        patterns: List<String>,
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
            failures.forEach { failure -> assertNamesOnlyTheColumn(failure, patterns) }
            assertEquals(0, client.pendingMutationCount())
        }
        assertEquals(emptyList(), server.requests)
    }

    private suspend fun assertRefused(call: suspend () -> Unit): IllegalArgumentException =
        assertFailsWith<IllegalArgumentException> { call() }

    private fun assertNamesOnlyTheColumn(
        failure: IllegalArgumentException,
        patterns: List<String>,
    ) {
        val message = failure.message.orEmpty()
        assertTrue("`$COLUMN`" in message, "the message does not name the column: $message")
        patterns
            .filter { it.isNotBlank() }
            .forEach { pattern ->
                assertFalse(pattern in message, "the message carries a pattern: $message")
            }
    }

    @Test
    fun an_empty_pattern_list_is_refused_and_nothing_is_sent_or_queued() = runTest {
        placements(emptyList()).forEach { filter ->
            assertRefusedSendingNothing(emptyList(), filter)
        }
    }

    @Test
    fun an_empty_pattern_is_refused_and_nothing_is_sent_or_queued() = runTest {
        val patterns = listOf("%q7%", "")
        placements(patterns).forEach { filter -> assertRefusedSendingNothing(patterns, filter) }
    }

    @Test
    fun a_null_pattern_in_any_case_is_refused_and_nothing_is_sent_or_queued() = runTest {
        listOf("nUlL", "null").forEach { pattern ->
            placements(listOf(pattern)).forEach { filter ->
                assertRefusedSendingNothing(listOf(pattern), filter)
            }
        }
    }

    @Test
    fun a_pattern_edged_with_white_space_is_refused_and_nothing_is_sent_or_queued() = runTest {
        listOf(" ", " q7%", "%q7\t").forEach { pattern ->
            placements(listOf(pattern)).forEach { filter ->
                assertRefusedSendingNothing(listOf(pattern), filter)
            }
        }
    }

    @Test
    fun a_pattern_holding_a_brace_is_refused_and_nothing_is_sent_or_queued() = runTest {
        listOf(listOf("q7{"), listOf("}"), listOf("q7}", "z9"), listOf("z9", "q7}w")).forEach {
            patterns ->
            placements(patterns).forEach { filter -> assertRefusedSendingNothing(patterns, filter) }
        }
    }

    @Test
    fun plain_and_quoted_pattern_lists_are_sent_as_before() = runTest {
        // Given
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        val table = client.from(TABLE)

        // When — patterns holding one of ,.:() go out quoted, which the server reads as written
        table.select(readStrategy = ReadStrategy.NetworkOnly) {
            filter { likeAll(COLUMN, listOf("%a%", "b_c")) }
        }
        val deleted =
            table.delete(MutationStrategy.OnlineOnly) {
                filter { ilikeAny(COLUMN, listOf("%x%", " a.", "NULL:", "{b,c}")) }
            }
        table.select(readStrategy = ReadStrategy.NetworkOnly) {
            filter { or { likeAny(COLUMN, listOf("%a%", "b")) } }
        }
        // A value that only reads like a pattern list is no pattern list
        table.select(readStrategy = ReadStrategy.NetworkOnly) {
            filter {
                eq("title", "like(all).{}")
                or { eq("note", "x.ilike(any).{}") }
            }
        }

        // Then
        assertIs<MutationOutcome.Applied>(deleted)
        assertEquals(
            listOf(
                "like(all).{%a%,b_c}",
                """ilike(any).{%x%," a.","NULL:","{b,c}"}""",
                null,
                null,
            ),
            server.requests.map { it.query[COLUMN] },
        )
        assertEquals("(code.like(any).{%a%,b})", server.requests[2].query["or"])
        assertEquals("eq.like(all).{}", server.requests[3].query["title"])
        assertEquals("""(note.eq."x.ilike(any).{}")""", server.requests[3].query["or"])
        client.close()
    }

    private companion object {
        const val TABLE = "codes"
        const val COLUMN = "code"
        const val ROWS = """[{"id":1,"code":"a"}]"""
    }
}
