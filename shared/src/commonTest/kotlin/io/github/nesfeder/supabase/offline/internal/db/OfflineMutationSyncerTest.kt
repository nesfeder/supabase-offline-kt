@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.util.awaitReplaySafeSession
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.FakePostgrestQueryBuilderWrapper
import io.github.nesfeder.supabase.offline.testdoubles.FakeStorageDao
import io.github.nesfeder.supabase.offline.testdoubles.postgrestResultOf
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Behavioural pins for [OfflineMutationSyncer] — the replay path that pushes queued offline
 * mutations to Supabase. The syncer previously had no test coverage; these cases lock in FIFO
 * replay, per-failure-class handling (network pause vs. permanent drop vs. transient 401 vs.
 * bounded unknown-error dead-letter), the single-sweep-per-table `distinct()` fix, the concurrency
 * mutex, and the refuse-to-replay-unfiltered guard for legacy null-descriptor UPDATE/DELETE rows.
 *
 * Each case drives a real [OfflineStore] over the shared in-memory DAO fakes and a scripted
 * [FakePostgrestQueryBuilderWrapper]; only the remote verbs are faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineMutationSyncerTest {

    private val table = "shoes"
    private var createdAtCounter = 0L

    @Test
    fun replays_pending_mutations_in_fifo_order_and_marks_them_synced() = runTest {
        // Given three queued INSERTs and a builder that accepts every write
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        dao.insert(insertRow("""[{"n":"b"}]"""))
        dao.insert(insertRow("""[{"n":"c"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onInsert = { postgrestResultOf("[]", client.postgrest) },
        )
        val syncer = syncer(store, client, wrapper)

        // When
        val synced = syncer.syncTable(table = table)

        // Then: every mutation replayed once, in enqueue order, and none stays pending
        assertEquals(3, synced)
        assertEquals(
            listOf("""[{"n":"a"}]""", """[{"n":"b"}]""", """[{"n":"c"}]"""),
            wrapper.recordedInsertBodies.map { it.toString() },
        )
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun network_failure_pauses_the_table_and_preserves_order() = runTest {
        // Given two queued INSERTs and a builder whose first write hits a connectivity failure
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        dao.insert(insertRow("""[{"n":"b"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onInsert = { throw ConnectTimeoutException("connect timed out", null) },
        )
        val syncer = syncer(store, client, wrapper)

        // When
        val synced = syncer.syncTable(table = table)

        // Then: the whole table pauses on the first mutation; nothing synced or dropped, order kept
        assertEquals(0, synced)
        assertEquals(1, wrapper.recordedInsertBodies.size, "only the first mutation was attempted")
        val pending = store.pendingMutationsForTable(table)
        assertEquals(
            listOf("""[{"n":"a"}]""", """[{"n":"b"}]"""),
            pending.map { it.bodyJson },
        )
    }

    @Test
    fun permanent_failure_drops_the_mutation_and_continues() = runTest {
        // Given two queued INSERTs; the first fails with a permanent 403, the second would succeed
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        dao.insert(insertRow("""[{"n":"b"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val forbidden = responseExceptionWithStatus(HttpStatusCode.Forbidden)
        var call = 0
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onInsert = {
                call++
                if (call == 1) throw forbidden else postgrestResultOf("[]", client.postgrest)
            },
        )
        val syncer = syncer(store, client, wrapper)

        // When
        val synced = syncer.syncTable(table = table)

        // Then: the 403 mutation is dropped, the syncer continues, and the second mutation syncs
        assertEquals(1, synced)
        assertEquals(2, wrapper.recordedInsertBodies.size, "both mutations were attempted")
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun unauthorized_keeps_the_mutation_pending() = runTest {
        // Given a queued INSERT that fails with 401 — routine token-refresh race after reconnect
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val unauthorized = responseExceptionWithStatus(HttpStatusCode.Unauthorized)
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onInsert = { throw unauthorized },
        )
        val syncer = syncer(store, client, wrapper)

        // When: 401 is transient, so it is rethrown (not permanent) — and NOT dropped
        assertFailsWith<ResponseException> { syncer.syncTable(table = table) }

        // Then: the queued write survives for a later sync (once the token is refreshed)
        assertEquals(1, store.pendingMutationCount())
    }

    @Test
    fun repeated_unauthorized_never_dead_letters_the_mutation() = runTest {
        // Given a queued INSERT that keeps failing with 401 across many sync triggers — a bad-token
        // window right after reconnect (NetworkRegained fires on every Online transition, so a
        // flapping reconnect can drive several rapid replays before supabase-kt's refresh lands)
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val unauthorized = responseExceptionWithStatus(HttpStatusCode.Unauthorized)
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onInsert = { throw unauthorized },
        )
        val syncer = syncer(store, client, wrapper)

        // When: far more than MAX_REPLAY_FAILURES (5) consecutive 401 replays occur, each rethrows
        repeat(8) {
            assertFailsWith<ResponseException> { syncer.syncTable(table = table) }
            assertEquals(
                1,
                store.pendingMutationCount(),
                "401 is excluded from the dead-letter budget and must never drop the write",
            )
        }
    }

    @Test
    fun unknown_error_rethrows_four_times_then_dead_letters_on_the_fifth() = runTest {
        // Given a queued INSERT that always fails with a 500 (poison mutation)
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val serverError = responseExceptionWithStatus(HttpStatusCode.InternalServerError)
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onInsert = { throw serverError },
        )
        val syncer = syncer(store, client, wrapper)

        // When: the first four replays rethrow and keep the mutation pending
        repeat(4) {
            assertFailsWith<ResponseException> { syncer.syncTable(table = table) }
            assertEquals(1, store.pendingMutationCount(), "mutation stays pending across retries")
        }

        // Then: the fifth replay dead-letters (drops) the mutation instead of throwing forever
        val synced = syncer.syncTable(table = table)
        assertEquals(0, synced)
        assertEquals(0, store.pendingMutationCount(), "poison mutation is dead-lettered")
        assertEquals(5, wrapper.recordedInsertBodies.size, "insert attempted on every replay")
    }

    @Test
    fun concurrent_syncAllTables_replays_each_mutation_exactly_once() =
        runTest(UnconfinedTestDispatcher()) {
            // Given one queued INSERT; the first sync to hold the lock parks inside insert() until
            // released, giving the second sync a chance to double-replay if the mutex were absent
            val dao = FakeMutationDao()
            dao.insert(insertRow("""[{"n":"a"}]"""))
            val store = buildStore(dao)
            val client = testClient()
            val reachedInsert = CompletableDeferred<Unit>()
            val releaseInsert = CompletableDeferred<Unit>()
            var insertCalls = 0
            val wrapper = FakePostgrestQueryBuilderWrapper(
                table = table,
                postgrest = client.postgrest,
                onInsert = {
                    insertCalls++
                    if (!reachedInsert.isCompleted) reachedInsert.complete(Unit)
                    releaseInsert.await()
                    postgrestResultOf("[]", client.postgrest)
                },
            )
            val syncer = syncer(store, client, wrapper)

            // When: two syncAllTables run concurrently
            val first = launch { syncer.syncAllTables() }
            val second = launch { syncer.syncAllTables() }
            reachedInsert.await()          // first holds the lock, parked in insert()
            releaseInsert.complete(Unit)   // let it finish
            advanceUntilIdle()
            first.join()
            second.join()

            // Then: the mutex serialized both; the second saw the mutation already synced
            assertEquals(1, insertCalls, "mutation must replay exactly once")
            assertEquals(0, store.pendingMutationCount())
        }

    @Test
    fun syncAllTables_visits_each_table_once_even_with_many_pending() = runTest {
        // Given three queued INSERTs on the same table
        val dao = FakeMutationDao()
        repeat(3) { index -> dao.insert(insertRow("""[{"n":"$index"}]""")) }
        val store = buildStore(dao)
        val client = testClient()
        var factoryCalls = 0
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onInsert = { postgrestResultOf("[]", client.postgrest) },
        )
        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = client,
            postgrestFactory = { _, _ ->
                factoryCalls++
                wrapper
            },
        )

        // When
        val synced = syncer.syncAllTables()

        // Then: the table is swept once (not once per pending mutation), all three synced
        assertEquals(3, synced)
        assertEquals(1, factoryCalls, "a table with N pending mutations is visited once")
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun null_descriptor_delete_is_refused_and_never_executed() = runTest {
        // Given a legacy DELETE row with no captured filter descriptor
        val dao = FakeMutationDao()
        dao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.DELETE,
                bodyJson = "{}",
                filterJson = null,
                createdAtMs = createdAtCounter++,
                syncedAtMs = null,
            ),
        )
        val store = buildStore(dao)
        val client = testClient()
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onDelete = { postgrestResultOf("[]", client.postgrest) }, // would succeed if ever called
        )
        val syncer = syncer(store, client, wrapper)

        // When
        val synced = syncer.syncTable(table = table)

        // Then: the unfiltered DELETE never fires and the poison row is dropped
        assertEquals(0, synced)
        assertEquals(0, wrapper.recordedDeleteCount, "unfiltered DELETE must never execute")
        assertEquals(0, store.pendingMutationCount(), "the legacy null-filter row is dropped")
    }

    @Test
    fun null_descriptor_update_is_refused_and_never_executed() = runTest {
        // Given a legacy UPDATE row with no captured filter descriptor
        val dao = FakeMutationDao()
        dao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.UPDATE,
                bodyJson = """{"n":"x"}""",
                filterJson = null,
                createdAtMs = createdAtCounter++,
                syncedAtMs = null,
            ),
        )
        val store = buildStore(dao)
        val client = testClient()
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onUpdate = { postgrestResultOf("[]", client.postgrest) },
        )
        val syncer = syncer(store, client, wrapper)

        // When
        val synced = syncer.syncTable(table = table)

        // Then: the unfiltered UPDATE never fires and the poison row is dropped
        assertEquals(0, synced)
        assertEquals(0, wrapper.recordedUpdateBodies.size, "unfiltered UPDATE must never execute")
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun partially_replayable_descriptor_update_is_refused_and_never_executed() = runTest {
        // Given an UPDATE whose stored filter decodes but loses its second condition on replay —
        // the request would carry eq(user_id) alone and rewrite every row of that user
        val dao = FakeMutationDao()
        dao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.UPDATE,
                bodyJson = """{"n":"x"}""",
                filterJson = LOSSY_FILTER_JSON,
                createdAtMs = createdAtCounter++,
                syncedAtMs = null,
            ),
        )
        val store = buildStore(dao)
        val client = testClient()
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onUpdate = { postgrestResultOf("[]", client.postgrest) },
        )
        val syncer = syncer(store, client, wrapper)

        // When
        val synced = syncer.syncTable(table = table)

        // Then: the broadened UPDATE never fires and the row is dropped rather than retried
        assertEquals(0, synced)
        assertEquals(
            0,
            wrapper.recordedUpdateBodies.size,
            "an UPDATE missing a captured condition must never execute",
        )
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun partially_replayable_descriptor_delete_is_refused_and_never_executed() = runTest {
        // Given the same lossy filter on a DELETE, where the broadened form removes rows
        val dao = FakeMutationDao()
        dao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.DELETE,
                bodyJson = "{}",
                filterJson = LOSSY_FILTER_JSON,
                createdAtMs = createdAtCounter++,
                syncedAtMs = null,
            ),
        )
        val store = buildStore(dao)
        val client = testClient()
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onDelete = { postgrestResultOf("[]", client.postgrest) },
        )
        val syncer = syncer(store, client, wrapper)

        // When
        val synced = syncer.syncTable(table = table)

        // Then
        assertEquals(0, synced)
        assertEquals(
            0,
            wrapper.recordedDeleteCount,
            "a DELETE missing a captured condition must never execute",
        )
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun empty_condition_descriptor_delete_is_refused_and_never_executed() = runTest {
        // Given a corrupted row whose descriptor decodes but carries no conditions at all —
        // it is non-null, so the legacy null guard lets it through
        val dao = FakeMutationDao()
        dao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.DELETE,
                bodyJson = "{}",
                filterJson = EMPTY_CONDITIONS_FILTER_JSON,
                createdAtMs = createdAtCounter++,
                syncedAtMs = null,
            ),
        )
        val store = buildStore(dao)
        val client = testClient()
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onDelete = { postgrestResultOf("[]", client.postgrest) },
        )
        val syncer = syncer(store, client, wrapper)

        // When
        val synced = syncer.syncTable(table = table)

        // Then: it would have replayed as a fully unfiltered DELETE
        assertEquals(0, synced)
        assertEquals(
            0,
            wrapper.recordedDeleteCount,
            "a descriptor with no conditions must never execute",
        )
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun fully_replayable_descriptor_update_still_executes() = runTest {
        // Given the same two-condition filter with a value the replay path can parse back. This
        // is the counterweight to the two refusals above: the gate is fail-closed, so it must be
        // shown not to reject a healthy queued write.
        val dao = FakeMutationDao()
        dao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.UPDATE,
                bodyJson = """{"n":"x"}""",
                filterJson = REPLAYABLE_FILTER_JSON,
                createdAtMs = createdAtCounter++,
                syncedAtMs = null,
            ),
        )
        val store = buildStore(dao)
        val client = testClient()
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onUpdate = { postgrestResultOf("[]", client.postgrest) },
        )
        val syncer = syncer(store, client, wrapper)

        // When
        val synced = syncer.syncTable(table = table)

        // Then
        assertEquals(1, synced)
        assertEquals(1, wrapper.recordedUpdateBodies.size, "a fully captured filter still replays")
    }

    // ── Test helpers ──────────────────────────────────────────────────────────────

    private fun insertRow(body: String): MutationRow = MutationRow(
        table = table,
        operation = MutationOperation.INSERT,
        bodyJson = body,
        filterJson = null,
        createdAtMs = createdAtCounter++,
        syncedAtMs = null,
    )

    @Test
    fun replay_is_deferred_while_the_auth_session_is_still_restoring() = runTest {
        // Given a queued INSERT and a session that never leaves the cold-start restore window
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onInsert = { postgrestResultOf("[]", client.postgrest) },
        )
        val syncer = syncer(store, client, wrapper, awaitSessionSettled = { false })

        // When both public entry points fire
        val syncedForTable = syncer.syncTable(table = table)
        val syncedForAll = syncer.syncAllTables()

        // Then nothing reaches the wire — the write is neither replayed on the anon key (where
        // RLS would 403 it into the permanent-failure drop) nor removed from the queue
        assertEquals(0, syncedForTable)
        assertEquals(0, syncedForAll)
        assertEquals(0, wrapper.recordedInsertBodies.size)
        assertEquals(1, store.pendingMutationCount())
    }

    @Test
    fun replay_proceeds_once_the_auth_session_settles() = runTest {
        // Given a queued INSERT and a session that settles between two triggers
        val dao = FakeMutationDao()
        dao.insert(insertRow("""[{"n":"a"}]"""))
        val store = buildStore(dao)
        val client = testClient()
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = table,
            postgrest = client.postgrest,
            onInsert = { postgrestResultOf("[]", client.postgrest) },
        )
        var settled = false
        val syncer = syncer(store, client, wrapper, awaitSessionSettled = { settled })

        // When the first trigger races restore and the second lands after it settles — the
        // second going through syncAllTables so the post-deferral proceed path is pinned on
        // both public entry points
        val deferredCount = syncer.syncTable(table = table)
        settled = true
        val replayedCount = syncer.syncAllTables()

        // Then the deferred write replays intact on the second trigger
        assertEquals(0, deferredCount)
        assertEquals(1, replayedCount)
        assertEquals(listOf("""[{"n":"a"}]"""), wrapper.recordedInsertBodies.map { it.toString() })
        assertEquals(0, store.pendingMutationCount())
    }

    /**
     * Builds the syncer under test. When [awaitSessionSettled] is omitted the gate is the
     * production default, [awaitReplaySafeSession] — and because [testClient] installs Postgrest
     * but not Auth, every existing case also pins the fail-open path (no Auth plugin ⇒ replay
     * proceeds, no wait).
     */
    private fun syncer(
        store: OfflineStore,
        client: SupabaseClient,
        wrapper: FakePostgrestQueryBuilderWrapper,
        awaitSessionSettled: (suspend () -> Boolean)? = null,
    ): OfflineMutationSyncer = OfflineMutationSyncer(
        store = store,
        supabase = client,
        postgrestFactory = { _, _ -> wrapper },
        awaitSessionSettled = awaitSessionSettled ?: { client.awaitReplaySafeSession() },
    )

    private fun testClient(): SupabaseClient = createSupabaseClient(
        supabaseUrl = "https://unit-test.supabase.co",
        supabaseKey = "unit-test-anon-key",
    ) {
        httpEngine = MockEngine { respondOk("[]") }
        install(Postgrest)
    }

    private fun buildStore(mutationDao: FakeMutationDao): OfflineStore = OfflineStore(
        objectDao = FakeObjectDao(),
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
    )

    /**
     * Builds a real [ResponseException] carrying [status] by letting a `MockEngine`-backed client
     * fail an `expectSuccess` request — the only reliable way to produce the HTTP-status-bearing
     * exception type that [io.github.nesfeder.supabase.offline.internal.util.httpStatusOrNull] inspects.
     */
    private suspend fun responseExceptionWithStatus(status: HttpStatusCode): ResponseException {
        val client = HttpClient(MockEngine) {
            engine { addHandler { respondError(status) } }
            expectSuccess = true
        }
        val thrown = assertFailsWith<ResponseException> {
            client.get("https://example.com/rest/v1/$table")
        }
        val response: HttpResponse = thrown.response
        check(response.status == status)
        return thrown
    }

    private companion object {
        /**
         * A stored descriptor whose `in` condition carries the bracket shape. It is not decodable
         * JSON, so replay drops it and the request would go out scoped by `eq(user_id)` alone —
         * the broadening this guard exists to refuse.
         */
        const val LOSSY_FILTER_JSON =
            """{"mode":"AND","conditions":[""" +
                """{"column":"user_id","operatorId":"eq","value":"u1"},""" +
                """{"column":"status","operatorId":"in","value":"[a,b]"}]}"""

        /**
         * A descriptor that decodes to a non-null value carrying nothing — non-null, so the
         * legacy null-filter guard does not catch it, yet it replays with no filter at all.
         */
        const val EMPTY_CONDITIONS_FILTER_JSON = """{"mode":"AND","conditions":[]}"""

        /** The same two columns with an `in` value replay can parse back in full. */
        const val REPLAYABLE_FILTER_JSON =
            """{"mode":"AND","conditions":[""" +
                """{"column":"user_id","operatorId":"eq","value":"u1"},""" +
                """{"column":"status","operatorId":"in","value":"(a,b)"}]}"""
    }
}
