package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine's own state lives in reserved `object_cache` rows so that the Room schema does not
 * change. Those rows are engine state, not cache: never evicted, never counted towards the cache
 * size, never returned by a read, never touched by an overlay — and wiped with everything else when
 * local data is cleared.
 */
class ReservedRowsTest {

    private val objectDao = FakeObjectDao()
    private val mutationDao = FakeMutationDao()

    @Test
    fun the_reserved_name_cannot_be_a_postgres_table() {
        // Postgres truncates identifiers to NAMEDATALEN - 1 = 63 bytes, so no table has this name.
        assertTrue(RESERVED_TABLE_NAME.encodeToByteArray().size > 63)
    }

    @Test
    fun reserved_rows_are_never_evicted_and_never_counted() = runTest {
        // Given — a cache over its limit, holding a rejected list and the owner records as its oldest rows
        val store = newTestOfflineStore(
            objectDao = objectDao,
            settings = OfflineCacheSettings(maxDbSizeBytes = 100, evictionBatchSize = 1, minRetainedBytes = 0),
        )
        store.queueOwnedWrite("user:user-a")
        store.mutationQueueState.addRejection(rejection(id = 1))
        objectDao.seedPayload("todos", "todos", "[" + "\"x\",".repeat(60) + "\"x\"]", updatedAt = 10L)
        val reservedBytes = assertNotNull(objectDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE)).payload.length

        // When
        store.enforceDbSizeLimitIfNeeded()

        // Then — the cache row went; the reserved rows stayed and never counted towards the size
        assertNull(objectDao.getRow("todos", "todos"))
        assertNotNull(objectDao.getRow(RESERVED_TABLE_NAME, MUTATION_OWNERS_SIGNATURE))
        assertNotNull(objectDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE))
        assertTrue(reservedBytes > 0)
        assertEquals(0L, objectDao.totalPayloadBytes())
    }

    @Test
    fun reserved_rows_are_never_returned_by_a_read() = runTest {
        // Given
        val store = newTestOfflineStore(objectDao = objectDao)
        store.mutationQueueState.addRejection(rejection(id = 1))

        // When / Then — no read path serves them, even asked for them by name
        assertNull(store.getPayload(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE))
        assertNull(store.getPayloadWithTimestamp(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE))
        assertNull(store.observePayload(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE).first())
        assertNull(store.overlaidPayloadFlow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE).first())
    }

    @Test
    fun reserved_rows_are_never_touched_by_a_write_or_an_overlay() = runTest {
        // Given
        val store = newTestOfflineStore(objectDao = objectDao, mutationDao = mutationDao)
        store.mutationQueueState.addRejection(rejection(id = 1))
        val before = objectDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE)

        // When / Then — every write path refuses the reserved table
        assertFailsWith<IllegalArgumentException> {
            store.patchCachedQueries(RESERVED_TABLE_NAME, MutationOperation.DELETE, "{}", filterJson = null)
        }
        assertFailsWith<IllegalArgumentException> {
            store.enqueueInsert(RESERVED_TABLE_NAME, Json.parseToJsonElement("[{}]") as JsonArray)
        }
        assertFailsWith<IllegalArgumentException> { store.clearTable(RESERVED_TABLE_NAME) }
        assertFailsWith<IllegalArgumentException> {
            store.addPayload(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE, "[]", authoritativeEmpty = true)
        }
        assertEquals(before, objectDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE))
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun the_client_refuses_the_reserved_name_as_a_table() {
        // Given
        val client = newTestOfflineClient()

        // When / Then
        assertFailsWith<IllegalArgumentException> { client.from(RESERVED_TABLE_NAME) }
    }

    @Test
    fun clearing_local_data_removes_the_reserved_rows() = runTest {
        // Given
        val store = newTestOfflineStore(objectDao = objectDao)
        store.queueOwnedWrite("user:user-a")
        store.mutationQueueState.addRejection(rejection(id = 1))

        // When
        store.clearAll()

        // Then
        assertNull(objectDao.getRow(RESERVED_TABLE_NAME, MUTATION_OWNERS_SIGNATURE))
        assertNull(objectDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE))
        assertEquals(emptyList(), store.mutationQueueState.rejections())
        assertEquals(MutationOwners.Known(emptyMap()), store.mutationQueueState.owners())
    }

    @Test
    fun reserved_rows_record_their_format_version() = runTest {
        // Given
        val store = newTestOfflineStore(objectDao = objectDao)

        // When
        store.mutationQueueState.addRejection(rejection(id = 1))
        store.queueOwnedWrite("user:user-a")

        // Then — a later build can tell which format it is reading
        val rejected = assertNotNull(objectDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE)).payload
        val owners = assertNotNull(objectDao.getRow(RESERVED_TABLE_NAME, MUTATION_OWNERS_SIGNATURE)).payload
        assertTrue(rejected.contains("\"version\":1"), rejected)
        assertTrue(owners.contains("\"version\":1"), owners)
    }

    /** Queues a write owned by [owner], which writes the owner records. */
    private suspend fun OfflineStore.queueOwnedWrite(owner: String) {
        enqueueOwnedBy(owner) { enqueueInsert("todos", Json.parseToJsonElement("""[{"title":"x"}]""") as JsonArray) }
    }

    private fun rejection(id: Long): StoredRejection = StoredRejection(
        id = id,
        table = "todos",
        operation = MutationOperation.INSERT,
        bodyJson = """[{"title":"x"}]""",
        createdAtMs = 0L,
        rejectedAtMs = 1L,
        message = "refused",
        serverError = StoredServerError(status = 400, code = "22P02", message = "refused"),
    )
}
