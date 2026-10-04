// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency.isIdleOnlyWithFetchedRows
import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency.onRealThreads
import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency.seededServer
import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency.showsAddedOnceOnceShown
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * A live list is one consistent view of the cached payload, the table's queued mutations and its
 * refresh even when one of them reaches it well after the others: here one is held back, so the
 * order that is rare on its own happens every time.
 */
class LiveReadSourceOrderTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()

    private fun clientOf(server: FakeRelationalPostgrestServer): OfflineClient =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            queryCacheDao = queryCacheDao,
            mutationDao = mutationDao,
            nowMillis = { Clock.System.now().toEpochMilliseconds() },
            config = Offline.createConfig { autoSync = false },
        )

    @Test
    fun a_keyless_insert_is_shown_once_when_the_queue_change_arrives_late() =
        runTest(timeout = 2.minutes) {
            // Given — a live list whose reads of the queue are handed over late
            val client = clientOf(seededServer("a" to "Milk", "b" to "Eggs"))

            // When — one key-less insert the server applies
            val emissions = onRealThreads {
                LiveReadConsistency.appliedInsertEmissions(
                    client,
                    keyed = false,
                    returnsRows = true,
                    rowKey = "r",
                    beforeInsert = { mutationDao.afterTableRead = { delay(HOLD) } },
                    quiet = HOLD * 3,
                )
            }
            client.close()

            // Then — the row shows once, from the moment it first shows
            assertTrue(showsAddedOnceOnceShown(emissions), "$emissions")
        }

    @Test
    fun a_keyed_insert_is_shown_once_when_the_queue_change_arrives_late() =
        runTest(timeout = 2.minutes) {
            // Given — a live list whose reads of the queue are handed over late
            val client = clientOf(seededServer("a" to "Milk", "b" to "Eggs"))

            // When — one keyed insert the server applies
            val emissions = onRealThreads {
                LiveReadConsistency.appliedInsertEmissions(
                    client,
                    keyed = true,
                    returnsRows = false,
                    rowKey = "r",
                    beforeInsert = { mutationDao.afterTableRead = { delay(HOLD) } },
                    quiet = HOLD * 3,
                )
            }
            client.close()

            // Then — the row shows once, from the moment it first shows
            assertTrue(showsAddedOnceOnceShown(emissions), "$emissions")
        }

    @Test
    fun a_replayed_insert_shows_when_the_refreshed_payload_arrives_late() =
        runTest(timeout = 2.minutes) {
            // Given — a row inserted offline and replayed; the list's payload changes heard late
            val client = clientOf(seededServer("a" to "Milk"))

            // When — a list of the table opens and refreshes
            val emissions = onRealThreads {
                LiveReadConsistency.replayedInsertEmissions(
                    client,
                    rowKey = "r",
                    beforeListOpens = {
                        queryCacheDao.beforeObservedRowDelivered = { _, _ -> delay(HOLD) }
                    },
                    quiet = HOLD * 3,
                )
            }
            client.close()

            // Then — the replayed row is in every emission that shows rows
            assertTrue(
                emissions.none { seen -> seen.titles != null && seen.added != 1 },
                "$emissions",
            )
        }

    @Test
    fun a_cold_refresh_is_not_idle_before_its_payload_arrives() =
        runTest(timeout = 2.minutes) {
            // Given — a cold list whose payload changes are heard late
            val server = seededServer("a" to "Milk")
            val client = clientOf(server)

            // When — the list opens and its refresh lands
            val emissions = onRealThreads {
                LiveReadConsistency.refreshEmissions(
                    client,
                    server,
                    warm = false,
                    beforeListOpens = {
                        server.answerDelayMillis = 0L
                        queryCacheDao.beforeObservedRowDelivered = { _, _ -> delay(HOLD) }
                    },
                    quiet = HOLD * 3,
                )
            }
            client.close()

            // Then — Idle comes with the rows it fetched
            assertTrue(isIdleOnlyWithFetchedRows(emissions), "$emissions")
        }

    @Test
    fun a_warm_refresh_is_not_idle_before_its_payload_arrives() =
        runTest(timeout = 2.minutes) {
            // Given — a warm list whose payload changes are heard late
            val server = seededServer("a" to "Milk")
            val client = clientOf(server)

            // When — the list opens and its refresh lands
            val emissions = onRealThreads {
                LiveReadConsistency.refreshEmissions(
                    client,
                    server,
                    warm = true,
                    beforeListOpens = {
                        server.answerDelayMillis = 0L
                        queryCacheDao.beforeObservedRowDelivered = { _, _ -> delay(HOLD) }
                    },
                    quiet = HOLD * 3,
                )
            }
            client.close()

            // Then — Idle comes with the rows it fetched
            assertTrue(isIdleOnlyWithFetchedRows(emissions), "$emissions")
        }

    private companion object {
        /** How long the held-back source is held: far longer than the others take. */
        val HOLD = 300.milliseconds
    }
}
