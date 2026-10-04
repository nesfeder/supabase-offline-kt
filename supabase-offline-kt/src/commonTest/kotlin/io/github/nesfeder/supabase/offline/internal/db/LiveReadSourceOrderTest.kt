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
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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

    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun a_replayed_insert_shows_when_the_readers_queue_read_is_held() =
        runTest(timeout = 3.minutes) {
            val failures = mutableListOf<String>()
            repeat(ITERATIONS) { iteration ->
                // Given — a row inserted offline and replayed; the list's first read of the queue
                // is held while its refresh writes the payload that holds the row and retires the
                // write
                val heldQueryCacheDao = FakeQueryCacheDao()
                val heldMutationDao = FakeMutationDao()
                val client =
                    newTestOfflineClient(
                        supabase = seededServer("a" to "Milk").supabaseClient(),
                        queryCacheDao = heldQueryCacheDao,
                        mutationDao = heldMutationDao,
                        nowMillis = { Clock.System.now().toEpochMilliseconds() },
                        config = Offline.createConfig { autoSync = false },
                    )

                // When — a list of the table opens and refreshes
                val emissions = onRealThreads {
                    LiveReadConsistency.replayedInsertEmissions(
                        client,
                        rowKey = "r$iteration",
                        beforeListOpens = {
                            val armed = AtomicBoolean(true)
                            heldMutationDao.beforeTableRead = { _ ->
                                if (armed.compareAndSet(expectedValue = true, newValue = false))
                                    delay(LONG_HOLD)
                            }
                        },
                        quiet = LONG_HOLD * 2,
                    )
                }
                client.close()
                if (emissions.any { seen -> seen.titles != null && seen.added != 1 })
                    failures += "iteration $iteration: $emissions"
            }

            // Then — read queue first, the reader sees the write or the payload that holds it, so
            // the row is in every emission that shows rows
            assertTrue(failures.isEmpty(), "$failures")
        }

    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun a_warm_refresh_is_not_idle_while_the_readers_payload_read_is_held() =
        runTest(timeout = 3.minutes) {
            val failures = mutableListOf<String>()
            repeat(ITERATIONS) { iteration ->
                // Given — a warm list whose first read of the payload is held while its refresh
                // writes the new payload and reports Idle
                val heldQueryCacheDao = FakeQueryCacheDao()
                val server = seededServer("a" to "Milk")
                val client =
                    newTestOfflineClient(
                        supabase = server.supabaseClient(),
                        queryCacheDao = heldQueryCacheDao,
                        mutationDao = FakeMutationDao(),
                        nowMillis = { Clock.System.now().toEpochMilliseconds() },
                        config = Offline.createConfig { autoSync = false },
                    )

                // When — the list opens and its refresh lands
                val emissions = onRealThreads {
                    LiveReadConsistency.refreshEmissions(
                        client,
                        server,
                        warm = true,
                        beforeListOpens = {
                            val armed = AtomicBoolean(true)
                            heldQueryCacheDao.onGetSnapshot = { _, _ ->
                                if (armed.compareAndSet(expectedValue = true, newValue = false))
                                    delay(LONG_HOLD)
                            }
                        },
                        quiet = LONG_HOLD * 2,
                    )
                }
                client.close()
                if (!isIdleOnlyWithFetchedRows(emissions))
                    failures += "iteration $iteration: $emissions"
            }

            // Then — read progress first, Idle comes with the rows it fetched
            assertTrue(failures.isEmpty(), "$failures")
        }

    private companion object {
        /** How long the held-back source is held: far longer than the others take. */
        val HOLD = 300.milliseconds

        /** How long one read inside the reader's view is held. */
        val LONG_HOLD = 400.milliseconds

        /** How often each case that holds one read inside the reader's view runs. */
        const val ITERATIONS = 10
    }
}
