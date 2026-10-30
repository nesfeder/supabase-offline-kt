// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.internal.cache.LastSyncedAt
import io.github.nesfeder.supabase.offline.internal.cache.LastSyncedAtRecord
import io.github.nesfeder.supabase.offline.internal.db.LAST_SYNCED_AT_CACHE_KEY
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * What the tracker does with the last-synced time at a change of cache owner, which no public call
 * drives deterministically: the identity before's goes, the new owner's own stays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncStatusTrackerTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val store = newTestStore(queryCacheDao = queryCacheDao)
    private var now = Instant.fromEpochMilliseconds(1_761_700_000_000)

    private fun TestScope.newTracker(): SyncStatusTracker =
        SyncStatusTracker(
            scope = backgroundScope,
            networkAccess = NetworkAccess(),
            mutationQueue = store.mutationQueue,
            lastSyncedAtRecord = LastSyncedAtRecord(queryCacheDao, EngineJson),
            engineLogger = EngineLogger(null, EngineLogLevel.INFO),
            clock =
                object : Clock {
                    override fun now(): Instant = now
                },
            replayIdentityNow = { null },
            cacheOwner = { null },
        )

    @Test
    fun a_change_of_cache_owner_takes_the_identity_befores_time_and_keeps_the_new_owners() =
        runTest {
            // Given — A's queue emptied by sending
            val tracker = newTracker()
            tracker.recordQueueEmptiedBySending(A)
            val forA = tracker.status.value.lastSyncedAt

            // When — the cache is made A's again (a read that found it another's asked), then B's
            tracker.recordCacheOwnerChanged(A.storedValue)
            val stillA = tracker.status.value.lastSyncedAt
            tracker.recordCacheOwnerChanged(B.storedValue)

            // Then — A's time stayed through the first, and went with the second
            assertEquals(now, forA)
            assertEquals(now, stillA)
            assertNull(tracker.status.value.lastSyncedAt)
        }

    @Test
    fun a_time_restored_at_open_is_the_recorded_owners_and_goes_with_a_change_of_owner() = runTest {
        // Given — a time an earlier client kept for A
        LastSyncedAtRecord(queryCacheDao, EngineJson).save(LastSyncedAt(A.storedValue, now))
        val tracker = newTracker()

        // When — the tracker starts and counts, then the cache passes to B
        tracker.start(passRunning = emptyFlow())
        runCurrent()
        val restored = tracker.status.value
        tracker.recordCacheOwnerChanged(B.storedValue)

        // Then
        assertEquals(true, restored.isQueueCounted)
        assertEquals(now, restored.lastSyncedAt)
        assertNull(tracker.status.value.lastSyncedAt)
    }

    @Test
    fun nothing_is_kept_or_changed_once_the_status_is_final() = runTest {
        // Given — a closed client's tracker
        val tracker = newTracker()
        tracker.publishFinal()

        // When — a send empties the queue, data is cleared, the owner changes
        tracker.recordQueueEmptiedBySending(A)
        tracker.recordLocalDataCleared()
        tracker.recordCacheOwnerChanged(B.storedValue)

        // Then — the status is as it was, and nothing was written
        assertNull(tracker.status.value.lastSyncedAt)
        assertNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY))
    }

    private companion object {
        val A = QueueIdentity.User("a")
        val B = QueueIdentity.User("b")
    }
}
