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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An empty cache is never seeded with `[]`. The in-memory overlay,
 * [OfflineStore.overlaidPayloadFlow], shows a queued INSERT without a cache write; a write that
 * succeeds online becomes a real cache row for a live reader with nothing cached; and nothing is
 * written when no reader is live.
 */
class OfflineStoreEmptyCacheReactivityTest {

    @Test
    fun substrate_reports_no_data_when_no_cache_and_no_mutations() = runTest {
        // Given — an empty cache and no queued mutations
        val store = buildStore(FakeQueryCacheDao(), FakeMutationDao())

        // When — the overlay substrate is observed
        val emitted = store.overlaidPayloadFlow("projects", "sig-A").first()

        // Then — it reports the typed no-data state (null), never a fabricated empty array
        assertNull(emitted, "cache-less, mutation-less signature is no-data, not empty []")
    }

    @Test
    fun queued_offline_insert_is_visible_via_substrate_without_writing_a_room_row() = runTest {
        // Given — an empty cache and a queued offline INSERT for the table
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        val store = buildStore(queryCacheDao, mutationDao)
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
        val writesBefore = queryCacheDao.writes

        // When — the overlay substrate is observed
        val emitted = store.overlaidPayloadFlow("projects", "sig-A").first()

        // Then — the optimistic row surfaces from the in-memory `[]` substrate, and nothing was
        // persisted to Room for the signature
        assertNotNull(emitted, "a queued INSERT must be visible even with no cached payload")
        assertTrue(emitted.contains("project-1"))
        assertNull(
            queryCacheDao.getRow("projects", "sig-A"),
            "the substrate must not persist a Room row",
        )
        assertEquals(
            writesBefore,
            queryCacheDao.writes,
            "the substrate must not write to the object cache",
        )
    }

    @Test
    fun live_cache_less_subscriber_sees_a_materialized_online_upsert() =
        runTest(UnconfinedTestDispatcher()) {
            // Given — a live subscriber on a signature whose object cache is empty
            val queryCacheDao = FakeQueryCacheDao()
            val store = buildStore(queryCacheDao, FakeMutationDao())
            val table = "note_projects"
            val cacheKey = "sig-A"
            val cacheFlow = store.overlaidPayloadFlow(table, cacheKey)
            val firstRealRow = async {
                withTimeoutOrNull(timeMillis = 500) {
                    cacheFlow.first { it != null && it.contains("project-1") }
                }
            }

            // When — an online-success upsert fires while the subscriber is live but cache-less;
            // the client passes the live signature so materialize-on-write can create the row
            store.patchCachedQueries(
                table = table,
                operation = MutationOperation.UPSERT,
                bodyJson = """[{"note_id":"log-1","project_id":"project-1","user_id":"u-1"}]""",
                filterJson = null,
                defaultToNull = false,
                onConflict = "note_id",
                liveCacheKeys = setOf(cacheKey),
            )

            // Then — the collector observes the materialized row (honoring the "UI reflects
            // immediately" contract for first-time saves), and a real row now exists in Room
            val emitted = firstRealRow.await()
            assertNotNull(
                emitted,
                "live collector must observe the materialized upsert even on an empty cache",
            )
            assertTrue(emitted.contains("project-1"))
            assertNotNull(
                queryCacheDao.getRow(table, cacheKey),
                "materialize-on-write must persist the real row",
            )
        }

    @Test
    fun online_success_mutation_writes_nothing_when_no_subscriber_is_live() = runTest {
        // Given — an empty cache and no live subscribers
        val queryCacheDao = FakeQueryCacheDao()
        val store = buildStore(queryCacheDao, FakeMutationDao())

        // When — an online-success upsert fires on an uncached table
        store.patchCachedQueries(
            table = "note_projects",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"note_id":"log-1","project_id":"project-1","user_id":"u-1"}]""",
            filterJson = null,
            defaultToNull = false,
            onConflict = "note_id",
        )

        // Then — nothing is materialized (self-GC: no live subscriber → no write)
        assertEquals(0, queryCacheDao.writes, "no live subscriber means no materialized row")
    }

    private fun buildStore(queryCacheDao: QueryCacheDao, mutationDao: MutationDao): OfflineStore =
        newTestOfflineStore(
            queryCacheDao = queryCacheDao,
            mutationDao = mutationDao,
        )
}
