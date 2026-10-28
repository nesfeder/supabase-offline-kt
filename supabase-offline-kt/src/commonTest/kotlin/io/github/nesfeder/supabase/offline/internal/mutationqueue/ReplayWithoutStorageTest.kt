// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.storage.StoredUpload
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * A replayer built with nothing to send uploads through refuses every queued upload it meets to the
 * rejected list, with the library's reason, and the pass goes on to the writes behind it.
 */
class ReplayWithoutStorageTest {

    @Test
    fun a_queued_upload_is_refused_when_nothing_can_send_it() = runTest {
        // Given — an upload queued for nobody signed in, with a row behind it
        val store = newTestStore()
        val server = RecordingPostgrestServer(answer = { okJson("[]") })
        val replayer =
            newTestMutationQueueReplayer(
                store = store,
                supabase = server.supabaseClient(),
                currentIdentity = { QueueIdentity.Anonymous },
                awaitSessionSettled = { true },
                isSessionSettledNow = { true },
            )
        store.mutationQueue.enqueueOwnedBy(QueueIdentity.Anonymous.storedValue) {
            enqueueUpload(
                StoredUpload(
                    bucket = "photos",
                    path = "a.jpg",
                    file = "0123456789abcdef0123456789abcdef",
                    size = 1,
                    upsert = false,
                )
            )
        }

        // When
        val report = replayer.sync()

        // Then
        val refused = report.rejectedUploads.single()
        assertEquals(STORAGE_NOT_INSTALLED_REASON, refused.message)
        assertEquals(null, refused.serverError)
        assertTrue(report.rejected.isEmpty())
        assertEquals(0, report.remainingCount)
    }
}
