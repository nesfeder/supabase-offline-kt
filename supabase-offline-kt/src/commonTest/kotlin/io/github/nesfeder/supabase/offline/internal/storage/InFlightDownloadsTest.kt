// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

/**
 * The register of downloads in flight, on its own: a caller joins only a download still running,
 * and one arriving after a download has ended — resolved, but not yet left by its waiters — starts
 * afresh rather than take its outcome.
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
        assertEquals(CommitOutcome.SUPERSEDED, first.await())
        assertEquals(CommitOutcome.COMMITTED, second.await())
        assertEquals(2, started)
        assertEquals(0, downloads.waitersOf(KEY))
    }

    private companion object {
        const val KEY = "avatars::u-1/me.png"
    }
}
