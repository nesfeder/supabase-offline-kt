// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Emission-dedup contract of [OfflineStore.overlaidPayloadFlow]: identical payloads and
 * unrelated-table mutation writes must not re-emit (and so must never fan out as re-decode work in
 * downstream repositories), while a genuinely-changed payload always propagates.
 *
 * Background: every cached SELECT payload lives in one Room table and every queued mutation in
 * another, and Room invalidation is table-granular — so before this dedup, any write for any
 * feature re-emitted every live observer app-wide with byte-identical payloads.
 */
class OfflineStoreEmissionDedupTest {

    @Test
    fun identical_payload_rewrite_does_not_reemit() =
        runTest(UnconfinedTestDispatcher()) {
            // Given — a live overlay collector and a cached payload
            var now = 0L
            val store = buildStore(FakeQueryCacheDao(), FakeMutationDao(), nowMillis = { now })
            val emissions = mutableListOf<String?>()
            val collector = launch {
                store.overlaidPayloadFlow("ratings", "sig-A").onEach { emissions.add(it) }.collect()
            }
            now = 1L
            store.addPayload(table = "ratings", cacheKey = "sig-A", payload = PAYLOAD_ONE_ROW)
            val emissionsAfterFirstWrite = emissions.size

            // When — a background refresh rewrites the byte-identical payload with a fresh
            // timestamp
            now = 2L
            store.addPayload(table = "ratings", cacheKey = "sig-A", payload = PAYLOAD_ONE_ROW)

            // Then — the rewrite is invisible downstream: no new emission, so no re-decode fan-out
            assertEquals(
                emissionsAfterFirstWrite,
                emissions.size,
                "an identical payload rewrite must not re-emit to live collectors",
            )
            collector.cancel()
        }

    @Test
    fun mutation_write_for_an_unrelated_table_does_not_reemit() =
        runTest(UnconfinedTestDispatcher()) {
            // Given — a live overlay collector on `ratings` with a cached payload
            val store = buildStore(FakeQueryCacheDao(), FakeMutationDao(), nowMillis = { 1L })
            val emissions = mutableListOf<String?>()
            val collector = launch {
                store.overlaidPayloadFlow("ratings", "sig-A").onEach { emissions.add(it) }.collect()
            }
            store.addPayload(table = "ratings", cacheKey = "sig-A", payload = PAYLOAD_ONE_ROW)
            val emissionsBeforeUnrelatedWrite = emissions.size

            // When — a mutation is queued for a different table (the mutation queue is one shared
            // Room table, so this write invalidates every table's mutation flow)
            store.enqueueInsert(
                table = "projects",
                body =
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "project-1")
                                put("user_id", "u-1")
                            }
                        )
                    },
            )

            // Then — the ratings collector is not re-emitted an identical overlay
            assertEquals(
                emissionsBeforeUnrelatedWrite,
                emissions.size,
                "a mutation write for an unrelated table must not re-emit other tables' overlays",
            )
            collector.cancel()
        }

    @Test
    fun changed_payload_still_reemits() =
        runTest(UnconfinedTestDispatcher()) {
            // Given — a live overlay collector and a cached payload
            var now = 0L
            val store = buildStore(FakeQueryCacheDao(), FakeMutationDao(), nowMillis = { now })
            val emissions = mutableListOf<String?>()
            val collector = launch {
                store.overlaidPayloadFlow("ratings", "sig-A").onEach { emissions.add(it) }.collect()
            }
            now = 1L
            store.addPayload(table = "ratings", cacheKey = "sig-A", payload = PAYLOAD_ONE_ROW)

            // When — a refresh writes a genuinely different payload
            now = 2L
            store.addPayload(table = "ratings", cacheKey = "sig-A", payload = PAYLOAD_TWO_ROWS)

            // Then — the change propagates and the latest emission carries the new payload
            assertEquals(
                PAYLOAD_TWO_ROWS,
                emissions.last(),
                "a changed payload must always reach live collectors",
            )
            collector.cancel()
        }

    private fun buildStore(
        queryCacheDao: QueryCacheDao,
        mutationDao: MutationDao,
        nowMillis: () -> Long,
    ): OfflineStore =
        newTestOfflineStore(
            queryCacheDao = queryCacheDao,
            mutationDao = mutationDao,
            nowMillis = nowMillis,
        )

    private companion object {
        const val PAYLOAD_ONE_ROW = """[{"id":"row-1","user_id":"u-1"}]"""
        const val PAYLOAD_TWO_ROWS =
            """[{"id":"row-1","user_id":"u-1"},{"id":"row-2","user_id":"u-1"}]"""
    }
}
