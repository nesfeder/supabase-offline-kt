@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.NumericDeltaColumn
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.FakePostgrestQueryBuilderWrapper
import io.github.nesfeder.supabase.offline.testdoubles.FakeStorageDao
import io.github.nesfeder.supabase.offline.testdoubles.postgrestResultOf
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for the stale-refresh watermark in [OfflineStore.addPayload] — the guard that refuses a
 * network SELECT response whose request went out *before* the table's most recent local write.
 *
 * The hazard is ordering, not emptiness. A refresh starts at t0 against server state S0; at t1 a
 * local mutation succeeds online and [OfflineStore.applyLocalMutationToCache] patches every cached
 * payload to S0+M; at t2 the refresh's S0 response arrives and overwrites that patch. Nothing
 * replays M — the online-success path uses an ephemeral overlay carrier and never enqueues — so
 * the row stays gone until some later SELECT happens to run. The user sees "I saved it and it
 * vanished".
 *
 * The guard keys purely on **when the request started** relative to the write, which is what makes
 * it compose with the authoritative-empty accept rather than fight it: an in-flight refresh's
 * pre-insert `[]` is refused because it is stale, while an empty from a request that started after
 * the write still replaces cached rows and lets a remote delete converge.
 *
 * Every test drives the store's tick source by hand so the interleavings are exact rather than
 * timing-dependent.
 */
class OfflineStoreStaleRefreshWatermarkTest {

    private val oneShoe = """[{"id":"shoe-1"}]"""
    private val bothShoes = """[{"id":"shoe-1"},{"id":"shoe-2"}]"""

    @Test
    fun stale_non_empty_response_is_dropped_and_the_local_write_survives() = runTest {
        // Given — a cached payload, and a refresh whose request went out at tick 10
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("shoes", "sig-A", oneShoe)
        var tick = 10L
        val store = buildStore(objectDao, tick = { tick })
        val requestStartedAtTick = store.monotonicTickNow()

        // When — a local insert succeeds online while that request is still in flight
        tick = 20L
        store.insertShoeTwo()

        // And — the in-flight refresh's pre-insert response finally lands
        tick = 30L
        val outcome = store.addPayload(
            table = "shoes",
            signature = "sig-A",
            payload = oneShoe,
            requestStartedAtTick = requestStartedAtTick,
        )

        // Then — the response is refused and the just-saved row is still in the cache
        assertEquals(AddPayloadOutcome.DROPPED_STALE, outcome)
        val row = objectDao.getRow("shoes", "sig-A")
        assertNotNull(row)
        assertEquals(bothShoes, row.payload)
    }

    @Test
    fun stale_authoritative_empty_response_is_dropped() = runTest {
        // Given — nothing cached yet, and a refresh that went out at tick 10 (its response will
        // be the server's pre-insert `[]`, authoritative because the session was authenticated)
        val objectDao = FakeObjectDao()
        var tick = 10L
        val store = buildStore(objectDao, tick = { tick })
        val requestStartedAtTick = store.monotonicTickNow()

        // When — a local insert materializes into the observed signature mid-flight
        tick = 20L
        store.insertShoeTwo(liveSignatures = setOf("sig-A"))

        // And — the pre-insert `[]` lands afterwards
        tick = 30L
        val outcome = store.addPayload(
            table = "shoes",
            signature = "sig-A",
            payload = "[]",
            authoritativeEmpty = true,
            requestStartedAtTick = requestStartedAtTick,
        )

        // Then — staleness is decided before authority: the empty is refused rather than erasing
        // the row the user just saved
        assertEquals(AddPayloadOutcome.DROPPED_STALE, outcome)
        val row = objectDao.getRow("shoes", "sig-A")
        assertNotNull(row)
        assertEquals("""[{"id":"shoe-2"}]""", row.payload)
    }

    @Test
    fun response_started_after_the_local_write_is_applied() = runTest {
        // Given — a local insert landed at tick 10
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("shoes", "sig-A", oneShoe)
        var tick = 10L
        val store = buildStore(objectDao, tick = { tick })
        store.insertShoeTwo()

        // When — a refresh issued *after* that write returns the server's current rows
        tick = 20L
        val requestStartedAtTick = store.monotonicTickNow()
        tick = 30L
        val outcome = store.addPayload(
            table = "shoes",
            signature = "sig-A",
            payload = bothShoes,
            requestStartedAtTick = requestStartedAtTick,
        )

        // Then — the watermark does not stand in the way of genuinely fresh server state
        assertEquals(AddPayloadOutcome.WRITTEN, outcome)
        val row = objectDao.getRow("shoes", "sig-A")
        assertNotNull(row)
        assertEquals(bothShoes, row.payload)
    }

    @Test
    fun authoritative_empty_started_after_the_local_write_still_replaces_cached_rows() = runTest {
        // Given — a local write at tick 10, after which every matching row is deleted remotely
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("shoes", "sig-A", oneShoe)
        var tick = 10L
        val store = buildStore(objectDao, tick = { tick })
        store.insertShoeTwo()

        // When — a refresh issued after the write carries an authenticated session and returns []
        tick = 20L
        val requestStartedAtTick = store.monotonicTickNow()
        tick = 30L
        val outcome = store.addPayload(
            table = "shoes",
            signature = "sig-A",
            payload = "[]",
            authoritativeEmpty = true,
            requestStartedAtTick = requestStartedAtTick,
        )

        // Then — the authoritative-empty accept is untouched: the empty replaces the cached rows
        // so a delete performed on another device still converges here
        assertEquals(AddPayloadOutcome.WRITTEN, outcome)
        val row = objectDao.getRow("shoes", "sig-A")
        assertNotNull(row)
        assertEquals("[]", row.payload)
    }

    @Test
    fun response_is_applied_when_no_local_write_was_recorded() = runTest {
        // Given — a store that has seen no local mutation at all (the overwhelmingly common case)
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("shoes", "sig-A", oneShoe)
        var tick = 10L
        val store = buildStore(objectDao, tick = { tick })
        val requestStartedAtTick = store.monotonicTickNow()

        // When — a refresh lands
        tick = 20L
        val outcome = store.addPayload(
            table = "shoes",
            signature = "sig-A",
            payload = bothShoes,
            requestStartedAtTick = requestStartedAtTick,
        )

        // Then — no watermark, no drop
        assertEquals(AddPayloadOutcome.WRITTEN, outcome)
        assertEquals(bothShoes, objectDao.getRow("shoes", "sig-A")?.payload)
    }

    @Test
    fun authoritative_empty_is_applied_when_no_local_write_was_recorded() = runTest {
        // Given — cached rows and no local mutation on record
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("shoes", "sig-A", oneShoe)
        var tick = 10L
        val store = buildStore(objectDao, tick = { tick })
        val requestStartedAtTick = store.monotonicTickNow()

        // When — an auth-ready refresh reports the rows are gone
        tick = 20L
        val outcome = store.addPayload(
            table = "shoes",
            signature = "sig-A",
            payload = "[]",
            authoritativeEmpty = true,
            requestStartedAtTick = requestStartedAtTick,
        )

        // Then — the empty lands, exactly as it did before the watermark existed
        assertEquals(AddPayloadOutcome.WRITTEN, outcome)
        assertEquals("[]", objectDao.getRow("shoes", "sig-A")?.payload)
    }

    @Test
    fun writers_that_carry_no_request_tick_are_never_dropped() = runTest {
        // Given — a local write at tick 20 that would disqualify anything older
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("shoes", "sig-A", oneShoe)
        var tick = 20L
        val store = buildStore(objectDao, tick = { tick })
        store.insertShoeTwo()

        // When — a caller with no request behind it writes a payload (no tick to compare)
        tick = 30L
        val outcome = store.addPayload(
            table = "shoes",
            signature = "sig-A",
            payload = oneShoe,
        )

        // Then — the write goes through; the guard only judges responses it can date
        assertEquals(AddPayloadOutcome.WRITTEN, outcome)
        assertEquals(oneShoe, objectDao.getRow("shoes", "sig-A")?.payload)
    }

    @Test
    fun the_watermark_is_scoped_per_table() = runTest {
        // Given — an in-flight refresh for `plans`, and a local write on `shoes`
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("shoes", "sig-A", oneShoe)
        objectDao.seedPayload("plans", "sig-P", """[{"id":"plan-1"}]""")
        var tick = 10L
        val store = buildStore(objectDao, tick = { tick })
        val requestStartedAtTick = store.monotonicTickNow()
        tick = 20L
        store.insertShoeTwo()

        // When — the `plans` refresh lands after the unrelated `shoes` write
        tick = 30L
        val outcome = store.addPayload(
            table = "plans",
            signature = "sig-P",
            payload = """[{"id":"plan-2"}]""",
            requestStartedAtTick = requestStartedAtTick,
        )

        // Then — a write to one table never disqualifies another table's refreshes
        assertEquals(AddPayloadOutcome.WRITTEN, outcome)
        assertEquals("""[{"id":"plan-2"}]""", objectDao.getRow("plans", "sig-P")?.payload)
    }

    @Test
    fun a_queue_replay_marked_synced_advances_the_watermark() = runTest {
        // Given — a mutation queued while offline, and a refresh already in flight
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("shoes", "sig-A", oneShoe)
        val mutationDao = FakeMutationDao()
        var tick = 10L
        val store = buildStore(objectDao, mutationDao = mutationDao, tick = { tick })
        store.enqueueInsert(table = "shoes", body = shoeTwoBody())
        val queued = mutationDao.inserted.single()
        val requestStartedAtTick = store.monotonicTickNow()

        // When — reconnect drains the queue and the replay succeeds
        tick = 20L
        store.markMutationSynced(id = queued.id, table = "shoes")

        // And — the refresh that was already in flight returns the pre-replay server state
        tick = 30L
        val outcome = store.addPayload(
            table = "shoes",
            signature = "sig-A",
            payload = oneShoe,
            requestStartedAtTick = requestStartedAtTick,
        )

        // Then — the replay counts as local truth too. Accepting here would rewrite the
        // pre-replay payload *and* retire the overlay in the same pass, losing the write twice
        // over; the queue-replay path writes no cache row of its own, so this is its only guard.
        assertEquals(AddPayloadOutcome.DROPPED_STALE, outcome)
    }

    @Test
    fun a_response_that_left_before_sign_out_never_repopulates_the_wiped_cache() = runTest {
        // Given — the signed-in user's rows are cached and a refresh went out at tick 10
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("shoes", "sig-A", oneShoe)
        var tick = 10L
        val store = buildStore(objectDao, tick = { tick })
        val requestStartedAtTick = store.monotonicTickNow()

        // When — the user signs out (or switches accounts) and the cache is torn down
        tick = 20L
        store.clearAll()

        // And — the refresh that was still in flight delivers the outgoing user's rows
        tick = 30L
        val outcome = store.addPayload(
            table = "shoes",
            signature = "sig-A",
            payload = bothShoes,
            authoritativeEmpty = true,
            requestStartedAtTick = requestStartedAtTick,
        )

        // Then — the response is refused and the wipe holds. Writing it would undo the teardown
        // and put one account's rows in front of the next user.
        assertEquals(AddPayloadOutcome.DROPPED_CACHE_CLEARED, outcome)
        assertNull(objectDao.getRow("shoes", "sig-A"))
    }

    @Test
    fun a_response_issued_after_teardown_is_written_normally() = runTest {
        // Given — a cache torn down at tick 10 (the next user has since signed in)
        val objectDao = FakeObjectDao()
        var tick = 10L
        val store = buildStore(objectDao, tick = { tick })
        store.clearAll()

        // When — a refresh issued after the teardown returns rows
        tick = 20L
        val requestStartedAtTick = store.monotonicTickNow()
        tick = 30L
        val outcome = store.addPayload(
            table = "shoes",
            signature = "sig-A",
            payload = oneShoe,
            requestStartedAtTick = requestStartedAtTick,
        )

        // Then — teardown disqualifies only what was already in flight; the cache refills
        assertEquals(AddPayloadOutcome.WRITTEN, outcome)
        assertEquals(oneShoe, objectDao.getRow("shoes", "sig-A")?.payload)
    }

    @Test
    fun a_replay_that_lands_between_the_response_and_the_retirement_keeps_its_overlay() = runTest {
        // Given — a mutation queued while offline and a refresh that went out at tick 10
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("shoes", "sig-A", oneShoe)
        val mutationDao = FakeMutationDao()
        var tick = 10L
        val store = buildStore(objectDao, mutationDao = mutationDao, tick = { tick })
        store.enqueueInsert(table = "shoes", body = shoeTwoBody())
        val queued = mutationDao.inserted.single()
        val requestStartedAtTick = store.monotonicTickNow()

        // When — that response is accepted (no watermark yet, so nothing disqualifies it)
        tick = 15L
        val outcome = store.addPayload(
            table = "shoes",
            signature = "sig-A",
            payload = oneShoe,
            requestStartedAtTick = requestStartedAtTick,
        )
        assertEquals(AddPayloadOutcome.WRITTEN, outcome)

        // And — the queue drains in the gap before the resolver retires synced mutations
        tick = 20L
        store.markMutationSynced(id = queued.id, table = "shoes")
        tick = 25L
        store.clearSyncedMutationsForTable("shoes", requestStartedAtTick)

        // Then — retirement is refused. The cached payload predates the replay, so dropping the
        // queue row here would erase the write from the cache and the overlay at once.
        assertEquals(1, mutationDao.inserted.size)
        assertNotNull(mutationDao.inserted.single().syncedAtMs)
    }

    @Test
    fun synced_mutations_are_retired_when_no_replay_raced_the_response() = runTest {
        // Given — a mutation that replayed at tick 10, before the refresh even went out
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("shoes", "sig-A", oneShoe)
        val mutationDao = FakeMutationDao()
        var tick = 10L
        val store = buildStore(objectDao, mutationDao = mutationDao, tick = { tick })
        store.enqueueInsert(table = "shoes", body = shoeTwoBody())
        val queued = mutationDao.inserted.single()
        store.markMutationSynced(id = queued.id, table = "shoes")

        // When — a refresh issued afterwards brings back server state that already includes it
        tick = 20L
        val requestStartedAtTick = store.monotonicTickNow()
        tick = 30L
        store.addPayload(
            table = "shoes",
            signature = "sig-A",
            payload = bothShoes,
            requestStartedAtTick = requestStartedAtTick,
        )
        store.clearSyncedMutationsForTable("shoes", requestStartedAtTick)

        // Then — the ordinary retirement still happens; the queue does not grow without bound
        assertEquals(emptyList(), mutationDao.inserted)
    }

    @Test
    fun a_delta_rebased_replay_stamps_the_watermark_exactly_like_an_absolute_one() = runTest {
        // Given — a delta-declared UPDATE queued offline (an accumulating-total edit: the
        // caller's intent is "+1 000", not the frozen absolute it computed at enqueue), and a
        // refresh for the same table already in flight at tick 10
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("shoes", "sig-A", oneShoe)
        val mutationDao = FakeMutationDao()
        var tick = 10L
        val store = buildStore(objectDao, mutationDao = mutationDao, tick = { tick })
        store.enqueueUpdate(
            table = "shoes",
            body = buildJsonObject { put("total_meters_used", JsonPrimitive(9000.0)) },
            filter = shoeOneFilter,
            deltaColumns = listOf(
                NumericDeltaColumn(column = "total_meters_used", snapshotValue = 8000.0),
            ),
        )
        val requestStartedAtTick = store.monotonicTickNow()

        // When — reconnect drains the queue through the real syncer. The replay re-reads the row
        // (the server moved to 8 500 m meanwhile) and rebases the delta onto it.
        tick = 20L
        val client = deltaTestClient()
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = "shoes",
            postgrest = client.postgrest,
            onSelect = {
                postgrestResultOf("""[{"id":"shoe-1","total_meters_used":8500.0}]""", client.postgrest)
            },
            onUpdate = { postgrestResultOf("[]", client.postgrest) },
        )
        val synced = OfflineMutationSyncer(
            store = store,
            supabase = client,
            postgrestFactory = { _, _ -> wrapper },
        ).syncTable(table = "shoes")

        // Then — the rebase itself is untouched by the watermark. The read-before-write goes out
        // on the raw PostgREST wrapper, never through addPayload, so nothing can refuse it.
        assertEquals(1, synced)
        assertEquals(
            9500.0,
            wrapper.recordedUpdateBodies.last().jsonObject
                .getValue("total_meters_used").jsonPrimitive.double,
        )

        // And — the refresh that was already in flight returns the pre-replay server state
        tick = 30L
        val outcome = store.addPayload(
            table = "shoes",
            signature = "sig-A",
            payload = oneShoe,
            requestStartedAtTick = requestStartedAtTick,
        )
        store.clearSyncedMutationsForTable("shoes", requestStartedAtTick)

        // Then — a delta-rebased replay establishes new server truth exactly like an absolute one:
        // it stamps the watermark, so the stale response is refused and its retirement suppressed.
        // The queue row survives, keeping the write visible through the overlay until a SELECT
        // issued after the replay brings back the rebased value.
        assertEquals(AddPayloadOutcome.DROPPED_STALE, outcome)
        assertEquals(oneShoe, objectDao.getRow("shoes", "sig-A")?.payload)
        assertEquals(1, mutationDao.inserted.size)
        assertNotNull(mutationDao.inserted.single().syncedAtMs)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private val shoeOneFilter = FilterDescriptor(
        mode = FilterDescriptor.Mode.AND,
        conditions = listOf(
            FilterDescriptor.Condition(column = "id", operatorId = "eq", value = "shoe-1"),
        ),
    )

    private fun deltaTestClient(): SupabaseClient = createSupabaseClient(
        supabaseUrl = "https://unit-test.supabase.co",
        supabaseKey = "unit-test-anon-key",
    ) {
        httpEngine = MockEngine { respondOk("[]") }
        install(Postgrest)
    }

    /** Applies the online-success insert of `shoe-2` — the write the stale response would erase. */
    private suspend fun OfflineStore.insertShoeTwo(liveSignatures: Set<String>? = null) {
        applyLocalMutationToCache(
            table = "shoes",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":"shoe-2"}]""",
            filterJson = null,
            defaultToNull = true,
            liveSignatures = liveSignatures,
        )
    }

    private fun shoeTwoBody(): JsonArray = JsonArray(
        listOf(JsonObject(mapOf("id" to JsonPrimitive("shoe-2")))),
    )

    private fun buildStore(
        objectDao: ObjectDao,
        mutationDao: MutationDao = FakeMutationDao(),
        tick: () -> Long,
    ): OfflineStore = OfflineStore(
        objectDao = objectDao,
        storageDao = FakeStorageDao(),
        mutationDao = mutationDao,
        dbPathProvider = object : DbPathProvider {
            override fun dbPath(): Path = "/tmp/test.db".toPath()
        },
        settings = OfflineCacheSettings(
            maxDbSizeBytes = null,
            evictionBatchSize = 0,
            minRetainedBytes = 0,
        ),
        nowMillis = { 0L },
        dbSizeProvider = { null },
        monotonicTick = tick,
    )
}
