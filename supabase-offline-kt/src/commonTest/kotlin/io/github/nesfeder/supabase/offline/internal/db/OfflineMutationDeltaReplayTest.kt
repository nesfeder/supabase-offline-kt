// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.NumericDeltaColumn
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakePostgrestQueryBuilderWrapper
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.postgrestResultOf
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Behavioural pins for the delta-encoded UPDATE mutation kind — the read-before-write replay that
 * makes a queued write to an *accumulating* column state the caller's change rather than a frozen
 * absolute (see [io.github.nesfeder.supabase.offline.NumericDeltaColumn]).
 *
 * The failure it exists to stop: a write queued while offline computes its value from what the
 * client could see at enqueue time. For a running total that several writers move — another device,
 * or this device's own earlier queued writes draining first — replaying that number erases
 * everything that landed in between, and no later read heals it because the number is, as far as
 * the server can tell, simply the newest write.
 *
 * Every case drives the real [MutationQueueReplayer] over a real [OfflineStore] with the in-memory
 * DAO fakes; only the remote verbs are scripted.
 */
class OfflineMutationDeltaReplayTest {

    private val table = "projects"

    @Test
    fun `a delta-declared update rebases onto the value the server holds at replay`() = runTest {
        // Given — at enqueue the client saw 8 000 and the user's edit made it 9 000 (+1 000).
        // While the write sat in the queue, a second device added 500, so the server
        // now holds 8 500.
        val store = buildStore()
        store.enqueueUpdate(
            table = table,
            body = buildJsonObject { put("total_minutes", JsonPrimitive(9000.0)) },
            filter = idFilter,
            deltaColumns =
                listOf(NumericDeltaColumn(column = "total_minutes", snapshotValue = 8000.0)),
        )
        val client = testClient()
        val wrapper =
            wrapper(
                client = client,
                selectPayload = """[{"id":"project-1","total_minutes":8500.0}]""",
            )

        // When
        val synced = syncer(store, client, wrapper).syncTable(table = table)

        // Then — the intervening 500 survives and the user's +1 000 still applies
        assertEquals(1, synced)
        assertEquals(9500.0, wrapper.updatedTotal())
    }

    @Test
    fun `a delta-declared update still applies a correction that lowers the total`() = runTest {
        // Given — the user corrected the starting total downwards (8 000 → 6 000, a -2 000
        // change) while another writer added 500 server-side
        val store = buildStore()
        store.enqueueUpdate(
            table = table,
            body = buildJsonObject { put("total_minutes", JsonPrimitive(6000.0)) },
            filter = idFilter,
            deltaColumns =
                listOf(NumericDeltaColumn(column = "total_minutes", snapshotValue = 8000.0)),
        )
        val client = testClient()
        val wrapper =
            wrapper(
                client = client,
                selectPayload = """[{"id":"project-1","total_minutes":8500.0}]""",
            )

        // When
        syncer(store, client, wrapper).syncTable(table = table)

        // Then — both moves are kept: 8 500 - 2 000
        assertEquals(6500.0, wrapper.updatedTotal())
    }

    @Test
    fun `a rebase that would go below the declared floor is clamped to it`() = runTest {
        // Given — the user zeroed the total offline (an -8 000 intent) while the server moved down
        // too: another device removed its own contributions, leaving 500. Both subtractions
        // compose, so the rebase alone would land at -7 500 — a value the column cannot hold.
        val store = buildStore()
        store.enqueueUpdate(
            table = table,
            body = buildJsonObject { put("total_minutes", JsonPrimitive(0.0)) },
            filter = idFilter,
            deltaColumns =
                listOf(
                    NumericDeltaColumn(
                        column = "total_minutes",
                        snapshotValue = 8000.0,
                        minValue = 0.0,
                    )
                ),
        )
        val client = testClient()
        val wrapper =
            wrapper(
                client = client,
                selectPayload = """[{"id":"project-1","total_minutes":500.0}]""",
            )

        // When
        syncer(store, client, wrapper).syncTable(table = table)

        // Then — the floor holds. The rebase invents a value the caller never computed, so it is
        // the one that has to stay inside the column's domain.
        assertEquals(0.0, wrapper.updatedTotal())
    }

    @Test
    fun `a declaration with no floor allows a negative rebase`() = runTest {
        // Given — the same interleaving on a column with no natural floor (a signed balance).
        // Clamping is opt-in: an undeclared floor must not silently become zero.
        val store = buildStore()
        store.enqueueUpdate(
            table = table,
            body = buildJsonObject { put("total_minutes", JsonPrimitive(0.0)) },
            filter = idFilter,
            deltaColumns =
                listOf(NumericDeltaColumn(column = "total_minutes", snapshotValue = 8000.0)),
        )
        val client = testClient()
        val wrapper =
            wrapper(
                client = client,
                selectPayload = """[{"id":"project-1","total_minutes":500.0}]""",
            )

        // When
        syncer(store, client, wrapper).syncTable(table = table)

        // Then
        assertEquals(-7500.0, wrapper.updatedTotal())
    }

    @Test
    fun `only declared columns are rebased - the rest of the body replays verbatim`() = runTest {
        // Given — an edit that renamed the row and re-seeded its running total
        val store = buildStore()
        store.enqueueUpdate(
            table = table,
            body =
                buildJsonObject {
                    put("nickname", JsonPrimitive("Sunday pair"))
                    put("starting_minutes", JsonPrimitive(2000.0))
                    put("total_minutes", JsonPrimitive(9000.0))
                },
            filter = idFilter,
            deltaColumns =
                listOf(NumericDeltaColumn(column = "total_minutes", snapshotValue = 8000.0)),
        )
        val client = testClient()
        val wrapper =
            wrapper(
                client = client,
                selectPayload =
                    """[{"id":"project-1","total_minutes":8500.0,"starting_minutes":1000.0}]""",
            )

        // When
        syncer(store, client, wrapper).syncTable(table = table)

        // Then — the declared column is rebased; the undeclared ones are the caller's absolutes,
        // because those are values the user stated outright rather than accumulating totals
        val body = wrapper.lastUpdateBody()
        assertEquals(9500.0, body["total_minutes"]?.jsonPrimitive?.double)
        assertEquals(2000.0, body["starting_minutes"]?.jsonPrimitive?.double)
        assertEquals("Sunday pair", body["nickname"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an update with no declarations replays the absolute body without re-reading`() = runTest {
        // Given — an ordinary queued UPDATE. Its wrapper's select handler throws, so a read on
        // this path would fail the test outright: the rebase must cost nothing when unused.
        val store = buildStore()
        store.enqueueUpdate(
            table = table,
            body = buildJsonObject { put("total_minutes", JsonPrimitive(9000.0)) },
            filter = idFilter,
        )
        val client = testClient()
        val wrapper =
            FakePostgrestQueryBuilderWrapper(
                table = table,
                postgrest = client.postgrest,
                onUpdate = { postgrestResultOf("[]", client.postgrest) },
            )

        // When
        val synced = syncer(store, client, wrapper).syncTable(table = table)

        // Then
        assertEquals(1, synced)
        assertEquals(9000.0, wrapper.updatedTotal())
    }

    @Test
    fun `a delta update whose row is gone replays the absolute body`() = runTest {
        // Given — the row was deleted on another device, so the re-read resolves nothing
        val store = buildStore()
        store.enqueueUpdate(
            table = table,
            body = buildJsonObject { put("total_minutes", JsonPrimitive(9000.0)) },
            filter = idFilter,
            deltaColumns =
                listOf(NumericDeltaColumn(column = "total_minutes", snapshotValue = 8000.0)),
        )
        val client = testClient()
        val wrapper = wrapper(client = client, selectPayload = "[]")

        // When
        val synced = syncer(store, client, wrapper).syncTable(table = table)

        // Then — the write still goes out (and matches nothing server-side) rather than being
        // held or dropped; there is no base to rebase against and nothing to preserve
        assertEquals(1, synced)
        assertEquals(9000.0, wrapper.updatedTotal())
    }

    @Test
    fun `a delta update whose re-read matches several rows replays the absolute body`() = runTest {
        // Given — a filter that resolves more than one row. Per-row bases would need per-row
        // UPDATEs, which is a different mutation than the one queued.
        val store = buildStore()
        store.enqueueUpdate(
            table = table,
            body = buildJsonObject { put("total_minutes", JsonPrimitive(9000.0)) },
            filter = idFilter,
            deltaColumns =
                listOf(NumericDeltaColumn(column = "total_minutes", snapshotValue = 8000.0)),
        )
        val client = testClient()
        val wrapper =
            wrapper(
                client = client,
                selectPayload =
                    """[{"id":"project-1","total_minutes":8500.0},""" +
                        """{"id":"project-2","total_minutes":100.0}]""",
            )

        // When
        syncer(store, client, wrapper).syncTable(table = table)

        // Then — degrades to the pre-delta behaviour rather than guessing
        assertEquals(9000.0, wrapper.updatedTotal())
    }

    @Test
    fun `a connectivity failure on the re-read keeps the mutation pending and writes nothing`() =
        runTest {
            // Given — the device drops off again between the drain starting and the re-read
            val store = buildStore()
            store.enqueueUpdate(
                table = table,
                body = buildJsonObject { put("total_minutes", JsonPrimitive(9000.0)) },
                filter = idFilter,
                deltaColumns =
                    listOf(NumericDeltaColumn(column = "total_minutes", snapshotValue = 8000.0)),
            )
            val client = testClient()
            val wrapper =
                FakePostgrestQueryBuilderWrapper(
                    table = table,
                    postgrest = client.postgrest,
                    onSelect = { throw ConnectTimeoutException("connect timed out", null) },
                    onUpdate = { postgrestResultOf("[]", client.postgrest) },
                )

            // When
            val synced = syncer(store, client, wrapper).syncTable(table = table)

            // Then — the read failure classifies exactly like a write failure would: the table
            // pauses with the edit intact, rather than firing a stale absolute
            assertEquals(0, synced)
            assertTrue(wrapper.recordedUpdateBodies.isEmpty(), "no PATCH may go out")
            assertEquals(1, store.pendingMutationCount(), "the queued edit survives")
        }

    @Test
    fun `a declared column absent from the server row keeps the absolute value`() = runTest {
        // Given — the row exists but has never had the column written (a fresh NULL)
        val store = buildStore()
        store.enqueueUpdate(
            table = table,
            body = buildJsonObject { put("total_minutes", JsonPrimitive(9000.0)) },
            filter = idFilter,
            deltaColumns =
                listOf(NumericDeltaColumn(column = "total_minutes", snapshotValue = 8000.0)),
        )
        val client = testClient()
        val wrapper =
            wrapper(
                client = client,
                selectPayload = """[{"id":"project-1","total_minutes":null}]""",
            )

        // When
        syncer(store, client, wrapper).syncTable(table = table)

        // Then — no base to measure against, so the caller's absolute stands
        assertEquals(9000.0, wrapper.updatedTotal())
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private val idFilter =
        FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions =
                listOf(
                    FilterDescriptor.Condition(
                        column = "id",
                        operatorId = "eq",
                        value = "project-1",
                    )
                ),
        )

    private fun FakePostgrestQueryBuilderWrapper.lastUpdateBody(): JsonObject =
        recordedUpdateBodies.last().jsonObject

    private fun FakePostgrestQueryBuilderWrapper.updatedTotal(): Double =
        lastUpdateBody().getValue("total_minutes").jsonPrimitive.double

    private fun wrapper(
        client: SupabaseClient,
        selectPayload: String,
    ): FakePostgrestQueryBuilderWrapper =
        FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onSelect = { postgrestResultOf(selectPayload, client.postgrest) },
            onUpdate = { postgrestResultOf("[]", client.postgrest) },
        )

    private fun syncer(
        store: OfflineStore,
        client: SupabaseClient,
        wrapper: FakePostgrestQueryBuilderWrapper,
    ): MutationQueueReplayer =
        MutationQueueReplayer(
            store = store,
            supabase = client,
            postgrestFactory = { _, _ -> wrapper },
        )

    private fun testClient(): SupabaseClient = newTestSupabaseClient()

    private fun buildStore(): OfflineStore = newTestOfflineStore()
}
