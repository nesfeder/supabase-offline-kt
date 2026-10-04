// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineTable
import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * An overlay already patching the cache when local data is cleared must not write the outgoing
 * user's rows back: the teardown check and each write it guards are one step with the wipe.
 */
class OverlayTeardownRaceTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val store = newTestOfflineStore(queryCacheDao = queryCacheDao)

    @Test
    fun an_overlay_in_progress_when_local_data_is_cleared_does_not_resurrect_the_cache() = runTest {
        // Given — user A's cached list, and A's online insert whose overlay has read the cache
        queryCacheDao.seedPayload(NOTES, "notes", """[{"id":"a1","title":"A's private row"}]""")
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        queryCacheDao.onAllForTableSnapshot = { table ->
            if (table == NOTES && !parked.isCompleted) {
                parked.complete(Unit)
                release.await()
            }
        }
        val server = RecordingPostgrestServer()
        val builder =
            newTestOfflineTable(
                delegate = server.supabaseClient().postgrest.from(NOTES),
                store = store,
            )
        val write = async {
            builder.insert(
                Json.parseToJsonElement("""[{"id":"a2","title":"A's new row"}]""") as JsonArray
            )
        }
        parked.await()

        // When — local data is cleared (sign-out), then the overlay resumes
        store.localData.clearLocalData()
        release.complete(Unit)
        write.await()

        // Then — nothing of A's is back in the cache
        assertNull(queryCacheDao.getRow(NOTES, "notes")?.payload)
    }

    private companion object {
        const val NOTES = "notes"
    }
}
