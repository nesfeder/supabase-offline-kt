// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest

/**
 * The register of downloads in flight, on its own: a caller joins a download registered and not yet
 * started, as one still running, and never one that has ended or been forgotten; a forgotten
 * download keeps its waiters, and the last of them to cancel still stops it.
 */
class InFlightDownloadsTest {

    @Test
    fun joining_with_no_download_in_flight_gives_nothing_to_wait_for() = runTest {
        // Given
        val downloads = InFlightDownloads()

        // When / Then
        assertNull(downloads.join(KEY))
        assertFalse(downloads.isInFlight(KEY))
        assertEquals(0, downloads.waitersOf(KEY))
    }

    @Test
    fun a_caller_landing_between_registration_and_start_joins_the_download() = runTest {
        // Given — a second caller that arrives, as one on another thread can, once the first has
        // registered its download and before it has started it
        var started = 0
        val release = CompletableDeferred<Unit>()
        var second: Deferred<SharedOutcome>? = null
        lateinit var downloads: InFlightDownloads
        downloads =
            InFlightDownloads(
                onRegistered = { key ->
                    assertTrue(downloads.isInFlight(key))
                    if (second == null) {
                        second =
                            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                                downloads.joinOrStart(key, backgroundScope) {
                                    started++
                                    CommitOutcome.COMMITTED
                                }
                            }
                    }
                }
            )

        // When
        val first = backgroundScope.async {
            downloads.joinOrStart(KEY, backgroundScope) {
                started++
                release.await()
                CommitOutcome.COMMITTED
            }
        }
        testScheduler.runCurrent()
        assertEquals(2, downloads.waitersOf(KEY))
        release.complete(Unit)

        // Then — one download ran, and the second caller got its outcome as a joiner
        val firstOutcome = assertIs<SharedOutcome.Ended>(first.await())
        val secondOutcome = assertIs<SharedOutcome.Ended>(checkNotNull(second).await())
        assertEquals(CommitOutcome.COMMITTED, firstOutcome.outcome)
        assertFalse(firstOutcome.joined)
        assertEquals(CommitOutcome.COMMITTED, secondOutcome.outcome)
        assertTrue(secondOutcome.joined)
        assertEquals(1, started)
    }

    @Test
    fun a_caller_arriving_after_a_download_has_ended_starts_afresh() = runTest {
        // Given — a download held until released, with one caller waiting for it, and a second
        // caller lined up to arrive the moment it ends, before the first has left
        val downloads = InFlightDownloads()
        val release = CompletableDeferred<Unit>()
        var started = 0
        val first = backgroundScope.async {
            downloads.joinOrStart(KEY, backgroundScope) {
                started++
                release.await()
                CommitOutcome.SUPERSEDED
            }
        }
        val arrive = CompletableDeferred<Unit>()
        val second = backgroundScope.async {
            arrive.await()
            // The ended download is still registered, but is nothing to join
            assertFalse(downloads.isInFlight(KEY))
            assertEquals(0, downloads.waitersOf(KEY))
            downloads.joinOrStart(KEY, backgroundScope) {
                started++
                CommitOutcome.COMMITTED
            }
        }
        testScheduler.runCurrent()
        assertEquals(1, downloads.waitersOf(KEY))

        // When — the download ends, and the second caller arrives before the first has left
        release.complete(Unit)
        arrive.complete(Unit)

        // Then — the first caller got the ended download's outcome; the second ran a download of
        // its own
        assertEquals(
            CommitOutcome.SUPERSEDED,
            assertIs<SharedOutcome.Ended>(first.await()).outcome,
        )
        assertEquals(
            CommitOutcome.COMMITTED,
            assertIs<SharedOutcome.Ended>(second.await()).outcome,
        )
        assertEquals(2, started)
        assertEquals(0, downloads.waitersOf(KEY))
    }

    @Test
    fun a_forgotten_download_keeps_its_waiters_and_is_never_joined() = runTest {
        // Given — a download that runs until canceled, with one caller waiting for it
        val downloads = InFlightDownloads()
        val stopped = CompletableDeferred<Unit>()
        val first = backgroundScope.async {
            downloads.joinOrStart(KEY, backgroundScope) {
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        }
        testScheduler.runCurrent()

        // When — the register forgets it, as a write of the object does, and a second caller
        // arrives
        downloads.forget(KEY)
        assertFalse(downloads.isInFlight(KEY))
        val second = backgroundScope.async {
            downloads.joinOrStart(KEY, backgroundScope) { CommitOutcome.COMMITTED }
        }

        // Then — the second ran a download of its own; the first still waits for the forgotten
        // one, and canceling it, its last waiter, stops that download
        val secondOutcome = assertIs<SharedOutcome.Ended>(second.await())
        assertFalse(secondOutcome.joined)
        assertFalse(stopped.isCompleted)
        first.cancelAndJoin()
        assertTrue(stopped.isCompleted)
    }

    @Test
    fun a_failure_of_the_download_reaches_each_caller_with_whether_it_joined() = runTest {
        // Given — a download that fails once released, with two callers
        val downloads = InFlightDownloads()
        val release = CompletableDeferred<Unit>()
        val first = backgroundScope.async {
            downloads.joinOrStart(KEY, backgroundScope) {
                release.await()
                throw IllegalStateException("refused")
            }
        }
        testScheduler.runCurrent()
        val second = backgroundScope.async { downloads.join(KEY) }
        testScheduler.runCurrent()

        // When
        release.complete(Unit)

        // Then
        val firstOutcome = assertIs<SharedOutcome.Failed>(first.await())
        val secondOutcome = assertIs<SharedOutcome.Failed>(second.await())
        assertFalse(firstOutcome.joined)
        assertTrue(secondOutcome.joined)
        assertEquals("refused", secondOutcome.cause.message)
    }

    private companion object {
        const val KEY = "avatars::u-1/me.png"
    }
}
