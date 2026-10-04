// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.QueryOverlay
import io.github.nesfeder.supabase.offline.internal.cache.ServedPayload
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Emission-dedup contract of [QueryOverlay.servedPayloadFlow], what every live read collects: it
 * emits when what a reader is served changes, and only then. An unrelated table's mutation write
 * does not re-emit, and an identical payload rewritten re-emits only its new fetch time, so neither
 * fans out as re-decode work downstream; a changed payload always propagates.
 *
 * Every cached SELECT payload lives in one Room table and every queued mutation in another, and
 * Room invalidation is table-granular: without this, any write re-emitted every live reader.
 */
class ServedPayloadDedupTest {

    @Test
    fun identical_payload_rewrite_reemits_only_its_new_fetch_time() =
        runTest(UnconfinedTestDispatcher()) {
            // Given — a live overlay collector and a cached payload
            var now = 0L
            val store = buildStore(FakeQueryCacheDao(), FakeMutationDao(), nowMillis = { now })
            val emissions = mutableListOf<ServedPayload?>()
            val collector = launch {
                servedPayloads(store, "ratings", "sig-A").onEach { emissions.add(it) }.collect()
            }
            now = 1L
            store.queryCache.cacheResponse(
                table = "ratings",
                cacheKey = "sig-A",
                payload = PAYLOAD_ONE_ROW,
            )
            val emissionsAfterFirstWrite = emissions.size

            // When — a background refresh rewrites the byte-identical payload with a fresh
            // timestamp
            now = 2L
            store.queryCache.cacheResponse(
                table = "ratings",
                cacheKey = "sig-A",
                payload = PAYLOAD_ONE_ROW,
            )

            // Then — one emission, of the same rows with the new fetch time
            assertEquals(emissionsAfterFirstWrite + 1, emissions.size)
            assertEquals(PAYLOAD_ONE_ROW, emissions.last()?.payload)
            assertEquals(2L, emissions.last()?.fetchedAtMillis)
            collector.cancel()
        }

    @Test
    fun mutation_write_for_an_unrelated_table_does_not_reemit() =
        runTest(UnconfinedTestDispatcher()) {
            // Given — a live overlay collector on `ratings` with a cached payload
            val store = buildStore(FakeQueryCacheDao(), FakeMutationDao(), nowMillis = { 1L })
            val emissions = mutableListOf<ServedPayload?>()
            val collector = launch {
                servedPayloads(store, "ratings", "sig-A").onEach { emissions.add(it) }.collect()
            }
            store.queryCache.cacheResponse(
                table = "ratings",
                cacheKey = "sig-A",
                payload = PAYLOAD_ONE_ROW,
            )
            val emissionsBeforeUnrelatedWrite = emissions.size

            // When — a mutation is queued for a different table (the mutation queue is one shared
            // Room table, so this write invalidates every table's mutation flow)
            store.mutationQueue.enqueueInsert(
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
            val emissions = mutableListOf<ServedPayload?>()
            val collector = launch {
                servedPayloads(store, "ratings", "sig-A").onEach { emissions.add(it) }.collect()
            }
            now = 1L
            store.queryCache.cacheResponse(
                table = "ratings",
                cacheKey = "sig-A",
                payload = PAYLOAD_ONE_ROW,
            )

            // When — a refresh writes a genuinely different payload
            now = 2L
            store.queryCache.cacheResponse(
                table = "ratings",
                cacheKey = "sig-A",
                payload = PAYLOAD_TWO_ROWS,
            )

            // Then — the change propagates and the latest emission carries the new payload
            assertEquals(
                PAYLOAD_TWO_ROWS,
                emissions.last()?.payload,
                "a changed payload must always reach live collectors",
            )
            collector.cancel()
        }

    /** What a live read of [table]/[cacheKey] is served, with nothing alongside. */
    private fun servedPayloads(
        store: TestStore,
        table: String,
        cacheKey: String,
    ): Flow<ServedPayload?> =
        store.queryOverlay
            .servedPayloadFlow(table, cacheKey, shape = null, MutableStateFlow(Unit))
            .map { (served, _) -> served }

    private fun buildStore(
        queryCacheDao: QueryCacheDao,
        mutationDao: MutationDao,
        nowMillis: () -> Long,
    ): TestStore =
        newTestStore(
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
