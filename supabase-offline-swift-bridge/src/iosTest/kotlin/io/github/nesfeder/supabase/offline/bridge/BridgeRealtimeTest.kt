// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * The Realtime tables an app names reach the library, the channel's status is reported and switched
 * through the bridge, and a fake Realtime stands in for the channel in memory.
 */
class BridgeRealtimeTest {

    private val server = RecordingServer()

    @Test
    fun the_named_tables_are_followed_and_a_reported_change_reaches_a_cached_read() = bridgeTest {
        // Given — a client following `todos` through the fake, with the table's rows cached
        val realtime = BridgeFakeRealtime()
        val client =
            openTestClient(server, realtime = realtime) {
                realtimeTables = listOf(BridgeRealtimeTable("todos", null, null))
            }
        val statuses = SubscriptionProbe<BridgeRealtimeStatus>()
        client.observeRealtimeStatus(statuses.onValue, statuses.onCompletion)
        statuses.awaitValue { status -> status.kind == BridgeRealtimeStatusKind.SUBSCRIBED }
        awaitUntil { realtime.joinCount == 1 }
        client.selectAndWait(readOf())

        // When — the server reports a row inserted, then the first row deleted
        val inserted = realtime.reportInsert("todos", """{"id":5,"title":"Jam"}""", null)
        val deleted = realtime.reportDelete("todos", """{"id":1}""", null)
        withTimeout(10.seconds) {
            while (true) {
                val (read, _) = client.selectAndWait(readOf(strategy = "cacheOnly"))
                val data = read?.data.orEmpty()
                if ("Jam" in data && "Buy milk" !in data) break
                delay(10)
            }
        }

        // Then
        assertNull(inserted)
        assertNull(deleted)
        assertEquals(setOf("todos"), realtime.subscribedTables)
        assertEquals(0, realtime.droppedReportCount)
        assertEquals(BridgeRealtimeStatusKind.SUBSCRIBED, client.currentRealtimeStatus().kind)
        client.closeAndWait()
        assertEquals(BridgeRealtimeStatusKind.OFF, client.currentRealtimeStatus().kind)
        assertNull(statuses.ending.await().second)
    }

    @Test
    fun a_filtered_table_hears_only_the_saved_rows_its_filter_admits() = bridgeTest {
        // Given — a client following the rows whose `n` is greater than 9, values crossing as text
        val realtime = BridgeFakeRealtime()
        val client =
            openTestClient(server, realtime = realtime) {
                realtimeTables =
                    listOf(
                        BridgeRealtimeTable(
                            "todos",
                            null,
                            BridgeRealtimeFilter("n", "gt", "9", null),
                        )
                    )
            }
        awaitUntil { realtime.joinCount == 1 }

        // When — a row the filter admits, compared as a number, one it turns away, and a delete
        assertNull(realtime.reportInsert("todos", """{"id":5,"n":10}""", null))
        assertNull(realtime.reportInsert("todos", """{"id":6,"n":8}""", null))
        assertNull(realtime.reportDelete("todos", """{"id":5}""", null))

        // Then — only the one the filter turns away was dropped
        assertEquals(1, realtime.droppedReportCount)
        client.closeAndWait()
    }

    @Test
    fun the_switch_leaves_the_channel_and_joins_it_again() = bridgeTest {
        // Given — joined
        val realtime = BridgeFakeRealtime()
        val client =
            openTestClient(server, realtime = realtime) {
                realtimeTables = listOf(BridgeRealtimeTable("todos", null, null))
            }
        awaitUntil { realtime.subscribedTables == setOf("todos") }

        // When / Then — off at once and left; then joined anew
        assertNull(awaitErrorOnly { client.disableRealtime(it) })
        assertEquals(BridgeRealtimeStatusKind.OFF, client.currentRealtimeStatus().kind)
        awaitUntil { realtime.subscribedTables.isEmpty() }
        assertNull(awaitErrorOnly { client.enableRealtime(it) })
        awaitUntil { realtime.subscribedTables == setOf("todos") }
        client.closeAndWait()
    }

    @Test
    fun a_failed_channel_is_reported_stalled_with_its_message_and_joined_again() = bridgeTest {
        // Given — joined
        val realtime = BridgeFakeRealtime()
        val client =
            openTestClient(server, realtime = realtime) {
                realtimeTables = listOf(BridgeRealtimeTable("todos", null, null))
            }
        val statuses = SubscriptionProbe<BridgeRealtimeStatus>()
        client.observeRealtimeStatus(statuses.onValue, statuses.onCompletion)
        statuses.awaitValue { status -> status.kind == BridgeRealtimeStatusKind.SUBSCRIBED }

        // When
        realtime.failChannel("not in the publication")

        // Then
        val stalled = statuses.awaitValue { status ->
            status.kind == BridgeRealtimeStatusKind.STALLED
        }
        assertTrue("not in the publication" in assertNotNull(stalled.cause).message)
        assertEquals(BridgeErrorKind.SERVER, assertNotNull(stalled.cause).kind)
        statuses.awaitValue { status -> status.kind == BridgeRealtimeStatusKind.SUBSCRIBED }
        client.closeAndWait()
    }

    @Test
    fun a_filter_the_server_cannot_evaluate_and_a_record_that_is_not_an_object_are_refused() =
        bridgeTest {
            // Given / When — an operator Realtime does not evaluate
            val opened =
                BridgeTesting.openInMemory(
                    testOptions {
                        realtimeTables =
                            listOf(
                                BridgeRealtimeTable(
                                    "todos",
                                    null,
                                    BridgeRealtimeFilter("title", "like", "%a%", null),
                                )
                            )
                    },
                    server,
                    null,
                    BridgeFakeRealtime(),
                    null,
                )

            // Then — the open is refused, naming the operator
            assertNull(opened.client)
            assertTrue("like" in assertNotNull(opened.error).message)

            // When / Then — a report that is not an object is refused, and nothing is followed
            val realtime = BridgeFakeRealtime()
            val refused = realtime.reportInsert("todos", "[1, 2]", null)
            assertNotNull(refused)
            assertEquals(BridgeErrorKind.INVALID_REQUEST, refused.kind)
            assertTrue(realtime.subscribedTables.isEmpty())
        }

    @Test
    fun a_filter_with_both_value_forms_or_the_wrong_one_for_its_operator_is_refused() = bridgeTest {
        // Given — shapes the server would refuse the join for: `in` with one value (sent as
        // `owner=in.<id>`), a comparison with values (`id=eq.[1]`), both forms at once, and `in`
        // with no value; each with the words its refusal must carry
        val wrongShapes =
            listOf(
                BridgeRealtimeFilter("owner", "in", "7d6f0c52", null) to "takes values",
                BridgeRealtimeFilter("id", "eq", null, listOf("1")) to "one value",
                BridgeRealtimeFilter("id", "in", "1", listOf("1")) to "not both",
                BridgeRealtimeFilter("id", "in", null, emptyList()) to "at least one",
            )
        wrongShapes.forEach { (filter, words) ->
            // When
            val opened =
                BridgeTesting.openInMemory(
                    testOptions {
                        realtimeTables = listOf(BridgeRealtimeTable("todos", null, filter))
                    },
                    server,
                    null,
                    BridgeFakeRealtime(),
                    null,
                )

            // Then — the open is refused before anything is joined, naming the shape, not the value
            assertNull(opened.client, "opened a client for the shape refused with: $words")
            val error = assertNotNull(opened.error, words)
            assertEquals(BridgeErrorKind.INVALID_REQUEST, error.kind, error.message)
            assertTrue(words in error.message, error.message)
            assertFalse("7d6f0c52" in error.message, error.message)
        }
    }

    @Test
    fun without_named_tables_the_status_is_off_and_nothing_is_joined() = bridgeTest {
        // Given / When
        val realtime = BridgeFakeRealtime()
        val client = openTestClient(server, realtime = realtime)

        // Then
        assertEquals(BridgeRealtimeStatusKind.OFF, client.currentRealtimeStatus().kind)
        assertTrue(realtime.subscribedTables.isEmpty())
        assertNull(awaitErrorOnly { client.disableRealtime(it) })
        assertNull(awaitErrorOnly { client.enableRealtime(it) })
        client.closeAndWait()
    }
}
