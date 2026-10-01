// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.postgrest.OfflineTable
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRemoteTable
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.postgrestResultOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Behavioral contract for the mutation verbs on [OfflineTable], which return a [MutationOutcome]
 * ([MutationOutcome.Applied] online / [MutationOutcome.Queued] offline).
 *
 * Verbs run against a scripted [FakeRemoteTable] delegate (no HTTP transport) and a real
 * [OfflineStore] over in-memory DAO fakes, so the enqueue side effect is observable via the fake
 * mutation DAO. A connectivity failure is scripted as a [kotlinx.io.IOException] — the shape
 * `isNetworkOrConnectivityFailure` classifies as a transport failure and therefore queues.
 */
class MutationOutcomeTest {

    // ── INSERT ──────────────────────────────────────────────────────────────────

    @Test
    fun insert_online_success_returns_applied_with_delegate_result() = runTest {
        // Given — the delegate INSERT succeeds and echoes the written row
        val postgrest = testPostgrest()
        val serverRow = """[{"id":"row-1","name":"Widget"}]"""
        val mutationDao = FakeMutationDao()
        val builder =
            buildBuilder(
                remoteTable =
                    FakeRemoteTable(
                        table = "attachments",
                        postgrest = postgrest,
                        onInsert = { postgrestResultOf(serverRow, postgrest) },
                    ),
                mutationDao = mutationDao,
            )

        // When
        val outcome = builder.insertJson(singleRowBody(), MutationStrategy.QueueIfOffline)

        // Then — Applied carries the live response, and nothing was queued
        val applied = assertIs<MutationOutcome.Applied>(outcome)
        assertEquals(serverRow, applied.result.data)
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun insert_connectivity_failure_returns_queued_and_enqueues() = runTest {
        // Given — the delegate INSERT fails with a transport error
        val postgrest = testPostgrest()
        val mutationDao = FakeMutationDao()
        val builder =
            buildBuilder(
                remoteTable =
                    FakeRemoteTable(
                        table = "attachments",
                        postgrest = postgrest,
                        onInsert = { throw IOException("network down") },
                    ),
                mutationDao = mutationDao,
            )

        // When
        val outcome = builder.insertJson(singleRowBody(), MutationStrategy.QueueIfOffline)

        // Then — the outcome is honest about being queued, and the mutation was enqueued
        assertEquals(MutationOutcome.Queued, outcome)
        assertEquals(1, mutationDao.inserted.size)
        assertEquals("attachments", mutationDao.inserted.single().table)
    }

    @Test
    fun insert_online_only_connectivity_failure_throws_and_does_not_enqueue() = runTest {
        // Given — OnlineOnly strategy with a transport failure
        val postgrest = testPostgrest()
        val mutationDao = FakeMutationDao()
        val builder =
            buildBuilder(
                remoteTable =
                    FakeRemoteTable(
                        table = "attachments",
                        postgrest = postgrest,
                        onInsert = { throw IOException("network down") },
                    ),
                mutationDao = mutationDao,
            )

        // When / Then — the failure surfaces, nothing is queued
        assertFailsWith<IOException> {
            builder.insertJson(singleRowBody(), MutationStrategy.OnlineOnly)
        }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun queued_write_resultOrNull_is_null_so_a_fail_loud_readback_pattern_throws() = runTest {
        // Given — a queued UPSERT (network down under QueueIfOffline)
        val postgrest = testPostgrest()
        val mutationDao = FakeMutationDao()
        val builder =
            buildBuilder(
                remoteTable =
                    FakeRemoteTable(
                        table = "beta_feedback",
                        postgrest = postgrest,
                        onUpsert = { throw IOException("network down") },
                    ),
                mutationDao = mutationDao,
            )

        // When / Then — the `resultOrNull()` bridge contract for a caller that treats "no server
        // row" as an error, e.g. a repository that reads `resultOrNull()?.decodeAs() ?:
        // error(...)`. A Queued outcome carries a null result, so the elvis `error(...)` throws.
        // (A caller that must read its row back even when queued should instead stamp a
        // client-owned id and project the row from its own payload.)
        assertFailsWith<IllegalStateException> {
            builder
                .upsertJson(singleRowBody(), MutationStrategy.QueueIfOffline)
                .resultOrNull()
                ?.data ?: error("write was queued offline; no server row to read back")
        }
        // And the write really was queued (enqueued exactly once).
        assertEquals(1, mutationDao.inserted.size)
    }

    @Test
    fun queued_write_resultOrNull_is_null_so_a_null_safe_readback_returns_null() = runTest {
        // Given — a queued UPSERT (network down under QueueIfOffline)
        val postgrest = testPostgrest()
        val mutationDao = FakeMutationDao()
        val builder =
            buildBuilder(
                remoteTable =
                    FakeRemoteTable(
                        table = "notes",
                        postgrest = postgrest,
                        onUpsert = { throw IOException("network down") },
                    ),
                mutationDao = mutationDao,
            )

        // When — a caller reads the row back with a null-safe `resultOrNull()?...` chain rather
        // than `error(...)`, so a Queued outcome falls through to null instead of throwing (the
        // caller then does an explicit id fetch, or reports that no row came back).
        val fellThrough =
            builder
                .upsertJson(singleRowBody(), MutationStrategy.QueueIfOffline)
                .resultOrNull()
                ?.data

        // Then — the chain short-circuits to null, and the write was still queued.
        assertEquals(null, fellThrough)
        assertEquals(1, mutationDao.inserted.size)
    }

    // ── UPSERT ──────────────────────────────────────────────────────────────────

    @Test
    fun upsert_online_success_returns_applied() = runTest {
        // Given
        val postgrest = testPostgrest()
        val serverRow = """[{"id":"log-1"}]"""
        val builder =
            buildBuilder(
                remoteTable =
                    FakeRemoteTable(
                        table = "notes",
                        postgrest = postgrest,
                        onUpsert = { postgrestResultOf(serverRow, postgrest) },
                    ),
                mutationDao = FakeMutationDao(),
            )

        // When
        val outcome = builder.upsertJson(singleRowBody(), MutationStrategy.QueueIfOffline)

        // Then
        val applied = assertIs<MutationOutcome.Applied>(outcome)
        assertEquals(serverRow, applied.result.data)
    }

    // ── UPDATE (strict-capture semantics preserved from the replay-safety work) ───

    @Test
    fun update_connectivity_failure_with_capturable_filter_queues() = runTest {
        // Given — a queued UPDATE whose filter is representable in the offline descriptor
        val postgrest = testPostgrest()
        val mutationDao = FakeMutationDao()
        val builder =
            buildBuilder(
                remoteTable =
                    FakeRemoteTable(
                        table = "projects",
                        postgrest = postgrest,
                        onUpdate = { throw IOException("network down") },
                    ),
                mutationDao = mutationDao,
            )

        // When — a simple equality filter (strict capture succeeds)
        val outcome =
            builder.updateJson(
                body = buildJsonObject { put("retired_at", "2026-07-12T00:00:00Z") },
                mutationStrategy = MutationStrategy.QueueIfOffline,
            ) {
                filter {
                    eq("id", "project-1")
                    eq("user_id", "user-1")
                }
            }

        // Then — queued and enqueued
        assertEquals(MutationOutcome.Queued, outcome)
        assertEquals(1, mutationDao.inserted.size)
    }

    @Test
    fun update_connectivity_failure_with_uncapturable_filter_refuses_and_rethrows() = runTest {
        // Given — a queued UPDATE whose filter is a logical OR group, which the offline descriptor
        // cannot capture faithfully. The strict-capture path must refuse to enqueue and rethrow the
        // original transport failure instead of queuing an over-broad replay.
        val postgrest = testPostgrest()
        val mutationDao = FakeMutationDao()
        val builder =
            buildBuilder(
                remoteTable =
                    FakeRemoteTable(
                        table = "projects",
                        postgrest = postgrest,
                        onUpdate = { throw IOException("network down") },
                    ),
                mutationDao = mutationDao,
            )

        // When / Then — the original failure is rethrown and nothing is queued
        assertFailsWith<IOException> {
            builder.updateJson(
                body = buildJsonObject { put("retired_at", "2026-07-12T00:00:00Z") },
                mutationStrategy = MutationStrategy.QueueIfOffline,
            ) {
                filter {
                    or {
                        eq("id", "project-1")
                        eq("user_id", "user-1")
                    }
                }
            }
        }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    // ── DELETE ────────────────────────────────────────────────────────────────────

    @Test
    fun delete_connectivity_failure_with_capturable_filter_queues() = runTest {
        // Given
        val postgrest = testPostgrest()
        val mutationDao = FakeMutationDao()
        val builder =
            buildBuilder(
                remoteTable =
                    FakeRemoteTable(
                        table = "note_projects",
                        postgrest = postgrest,
                        onDelete = { throw IOException("network down") },
                    ),
                mutationDao = mutationDao,
            )

        // When
        val outcome =
            builder.delete(MutationStrategy.QueueIfOffline) {
                filter {
                    eq("note_id", "log-1")
                    eq("user_id", "user-1")
                }
            }

        // Then
        assertEquals(MutationOutcome.Queued, outcome)
        assertEquals(1, mutationDao.inserted.size)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    private fun singleRowBody(): JsonArray = buildJsonArray {
        add(buildJsonObject { put("name", "Widget") })
    }

    private fun buildBuilder(
        remoteTable: FakeRemoteTable,
        mutationDao: MutationDao,
    ): OfflineTable =
        OfflineTable(
            delegate = remoteTable,
            store = buildStore(mutationDao),
        )

    private fun buildStore(mutationDao: MutationDao): OfflineStore =
        newTestOfflineStore(mutationDao = mutationDao)

    private fun testPostgrest(): Postgrest = newTestSupabaseClient().postgrest
}
