// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
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
 * Two top-level filters on one column are refused before anything is sent or queued, under every
 * strategy, online and offline: supabase-kt sends only the first, so the read would show more rows
 * than it names, and a write would reach more rows, now or when the queue replays it. One `and { }`
 * group sends both, and filters on different columns are untouched.
 */
class SameColumnFiltersTest {

    private val server = RecordingPostgrestServer(answer = { okJson(ROWS) })

    private val readStrategies =
        listOf(
            ReadStrategy.NetworkOnly,
            ReadStrategy.NetworkFirst(),
            ReadStrategy.CacheFirst(),
            ReadStrategy.CacheOnly(),
        )

    private val mutationStrategies =
        listOf(MutationStrategy.OnlineOnly, MutationStrategy.QueueIfOffline)

    /** The window `1 <= age <= 10`, written as two top-level filters on `age`. */
    private val ageWindow: PostgrestRequestBuilder.() -> Unit = {
        filter {
            gte(COLUMN, 1)
            lte(COLUMN, 10)
        }
    }

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

    private fun assertRefusal(failure: IllegalArgumentException) {
        val message = failure.message.orEmpty()
        assertTrue("`$COLUMN`" in message, "the message does not name the column: $message")
        assertTrue("and { }" in message, "the message does not say what to write: $message")
        assertFalse("10" in message, "the message carries a filter value: $message")
    }

    @Test
    fun a_one_shot_read_with_two_filters_on_one_column_is_refused_and_sends_nothing() = runTest {
        onlineAndOffline { client ->
            readStrategies.forEach { strategy ->
                // When
                val failure =
                    assertFailsWith<IllegalArgumentException>("$strategy") {
                        client.from(TABLE).select(readStrategy = strategy, request = ageWindow)
                    }

                // Then
                assertRefusal(failure)
            }
        }
        assertEquals(emptyList(), server.requests)
    }

    @Test
    fun a_live_read_with_two_filters_on_one_column_fails_and_sends_nothing() = runTest {
        onlineAndOffline { client ->
            everyFlowReadStrategy.forEach { strategy ->
                // When
                val failure =
                    assertFailsWith<IllegalArgumentException>("$strategy") {
                        client
                            .from(TABLE)
                            .selectAsFlow(readStrategy = strategy, request = ageWindow)
                            .first()
                    }

                // Then
                assertRefusal(failure)
            }
        }
        assertEquals(emptyList(), server.requests)
    }

    @Test
    fun an_update_or_a_delete_with_two_filters_on_one_column_is_refused_sending_and_queueing_nothing() =
        runTest {
            onlineAndOffline { client ->
                mutationStrategies.forEach { strategy ->
                    // When
                    val update =
                        assertFailsWith<IllegalArgumentException>("update, $strategy") {
                            client
                                .from(TABLE)
                                .update({ set("title", "renamed") }, strategy, ageWindow)
                        }
                    val delete =
                        assertFailsWith<IllegalArgumentException>("delete, $strategy") {
                            client.from(TABLE).delete(strategy, ageWindow)
                        }

                    // Then
                    assertRefusal(update)
                    assertRefusal(delete)
                    assertEquals(0, client.pendingMutationCount())
                }
            }
            assertEquals(emptyList(), server.requests)
        }

    @Test
    fun the_two_filters_in_one_and_group_are_both_sent() = runTest {
        // Given
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        val window: PostgrestRequestBuilder.() -> Unit = {
            filter {
                and {
                    gte(COLUMN, 1)
                    lte(COLUMN, 10)
                }
            }
        }

        // When
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly, request = window)
        val deleted = client.from(TABLE).delete(MutationStrategy.OnlineOnly, window)

        // Then — one parameter carrying both conditions, for the read and the write alike
        assertIs<MutationOutcome.Applied>(deleted)
        assertEquals(listOf("GET", "DELETE"), server.requests.map { it.method })
        server.requests.forEach { request ->
            assertEquals("(age.gte.1,age.lte.10)", request.query["and"])
            assertFalse(COLUMN in request.query)
        }
        client.close()
    }

    @Test
    fun filters_on_different_columns_and_single_valued_parameters_are_untouched() = runTest {
        // Given
        val client = newTestOfflineClient(supabase = server.supabaseClient())

        // When — two columns filtered, two orders (merged into one parameter) and a limit
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly) {
            filter {
                gte(COLUMN, 1)
                lte("height", 10)
            }
            order(COLUMN, Order.ASCENDING)
            order("name", Order.DESCENDING)
            limit(5)
        }

        // Then
        val sent = server.requests.single().query
        assertEquals("gte.1", sent[COLUMN])
        assertEquals("lte.10", sent["height"])
        assertEquals("age.asc.nullslast,name.desc.nullslast", sent["order"])
        assertEquals("5", sent["limit"])
        client.close()
    }

    private companion object {
        const val TABLE = "people"
        const val COLUMN = "age"
        const val ROWS = """[{"id":1,"age":5}]"""
    }
}
