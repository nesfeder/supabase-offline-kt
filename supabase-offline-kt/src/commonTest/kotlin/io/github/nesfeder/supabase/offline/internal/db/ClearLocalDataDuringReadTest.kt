// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.postgrest.queryShapeOf
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * A read that took its snapshot of the cache before `clearLocalData()` records nothing about its
 * query afterward: the shape record, which holds the query's filter values (a user id, a search),
 * is written under the clear lock and only beside a payload, so a clear in the gap leaves no row of
 * the outgoing user's behind.
 */
class ClearLocalDataDuringReadTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val store = newTestStore(queryCacheDao = queryCacheDao)

    /** The query's shape, as a read captures it: a filter on the user's id. */
    private val shape: QueryShape =
        queryShapeOf(
            Columns.ALL,
            SelectRequestBuilder("public", PropertyConversionMethod.NONE).apply {
                filter { eq("user_id", "u-1") }
            },
        )

    private val parked = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    /** Parks the next read of [NOTES]'s payload between the snapshot and what follows it. */
    private fun parkNextPayloadRead() {
        queryCacheDao.seedPayload(NOTES, CACHE_KEY, """[{"id":"n1","user_id":"u-1"}]""")
        queryCacheDao.onGetSnapshot = { table, _ ->
            if (table == NOTES && !parked.isCompleted) {
                parked.complete(Unit)
                release.await()
            }
        }
    }

    private fun shapeRecordKeys(): List<String> =
        queryCacheDao
            .rowsOf(RESERVED_TABLE_NAME)
            .map { row -> row.cacheKey }
            .filter { cacheKey -> cacheKey.startsWith(QUERY_SHAPE_CACHE_KEY_PREFIX) }

    @Test
    fun a_one_shot_read_through_the_clear_leaves_no_shape_record() = runTest {
        // Given — a read of the user's notes parked after it read the payload
        parkNextPayloadRead()
        val read = async { store.queryOverlay.cachedPayloadOverlaid(NOTES, CACHE_KEY, shape) }
        parked.await()

        // When — local data is cleared (sign-out), then the read goes on
        store.localData.clearLocalData()
        release.complete(Unit)
        read.await()

        // Then — no record of the query, with its filter value, is on disk
        assertEquals(emptyList(), shapeRecordKeys())
        assertNull(queryCacheDao.getRow(NOTES, CACHE_KEY))
    }

    @Test
    fun a_live_read_through_the_clear_leaves_no_shape_record() = runTest {
        // Given — a live read of the user's notes parked after it read the payload
        parkNextPayloadRead()
        val read = async {
            store.queryOverlay
                .servedPayloadFlow(NOTES, CACHE_KEY, shape, MutableStateFlow(Unit))
                .first()
        }
        parked.await()

        // When
        store.localData.clearLocalData()
        release.complete(Unit)
        read.await()

        // Then
        assertEquals(emptyList(), shapeRecordKeys())
        assertNull(queryCacheDao.getRow(NOTES, CACHE_KEY))
    }

    private companion object {
        const val NOTES = "notes"
        const val CACHE_KEY = "notes?user_id=eq.u-1"
    }
}
