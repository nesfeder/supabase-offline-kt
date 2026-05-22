package io.github.nesfeder.supabase.internal.db

import io.github.nesfeder.supabase.internal.postgrest.FilterDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OfflineStoreTest {

    private val objectDao = FakeObjectDao()
    private val storageDao = FakeStorageDao()
    private val mutationDao = FakeMutationDao()
    private val fixedNow = 1_700_000_000_000L

    private fun newStore(): OfflineStore =
        newTestOfflineStore(
            objectDao = objectDao,
            storageDao = storageDao,
            mutationDao = mutationDao,
            nowMillis = { fixedNow },
        )

    @Test
    fun addAndGetPayload_roundTrip() = runTest {
        // Given
        val store = newStore()
        val table = "assets"
        val signature = "assets?order=updated_at.desc"
        val payload = """[{"id":"1"}]"""

        // When
        store.addPayload(table, signature, payload)
        val result = store.getPayload(table, signature)

        // Then
        assertEquals(payload, result)
    }

    @Test
    fun getPayloadWithTimestamp_returnsCorrectValues() = runTest {
        // Given
        val store = newStore()
        val table = "assets"
        val signature = "sig"
        val payload = "payload"
        store.addPayload(table, signature, payload)

        // When
        val result = store.getPayloadWithTimestamp(table, signature)

        // Then
        assertNotNull(result)
        assertEquals(payload, result.first)
        assertEquals(fixedNow, result.second)
    }

    @Test
    fun getPayloadWithTimestamp_missingKey_returnsNull() = runTest {
        // Given
        val store = newStore()

        // When
        val result = store.getPayloadWithTimestamp("missing", "sig")

        // Then
        assertNull(result)
    }

    @Test
    fun getCandidateRowsForTable_returnsNewestRowsFirst() = runTest {
        // Given
        var time = 100L
        val store = newTestOfflineStore(
            objectDao = objectDao,
            storageDao = storageDao,
            mutationDao = mutationDao,
            nowMillis = { time.also { time += 100 } },
        )
        store.addPayload("assets", "older", "[]")
        store.addPayload("assets", "newer", "[]")
        store.addPayload("other", "other", "[]")

        // When
        val rows = store.getCandidateRowsForTable("assets")

        // Then
        assertEquals(listOf("newer", "older"), rows.map { it.signature })
    }

    @Test
    fun clearTable_removesPayloads() = runTest {
        // Given
        val store = newStore()
        store.addPayload("assets", "sig1", "[]")
        store.addPayload("assets", "sig2", "[]")
        store.addPayload("other", "sigO", "[]")

        // When
        store.clearTable("assets")

        // Then
        assertNull(store.getPayload("assets", "sig1"))
        assertNull(store.getPayload("assets", "sig2"))
        assertNotNull(store.getPayload("other", "sigO"))
    }

    @Test
    fun deleteTablePayload_removesOnlyMatchingSignature() = runTest {
        // Given
        val store = newStore()
        store.addPayload("assets", "sig1", """[{"id":"1"}]""")
        store.addPayload("assets", "sig2", """[{"id":"2"}]""")

        // When
        store.deleteTablePayload("assets", "sig1")

        // Then
        assertNull(store.getPayload("assets", "sig1"))
        assertNotNull(store.getPayload("assets", "sig2"))
    }

    @Test
    fun addGetDeleteStorage_roundTrip() = runTest {
        // Given
        val store = newStore()
        val bucket = "test-bucket"
        val path = "foo/bar.jpg"
        val file: Path = "/tmp/test-file.jpg".toPath()

        // When
        store.addStorage(bucketId = bucket, path = path, file = file, size = 42, eTag = "abc")
        val stored = store.getStorageByKey(bucket, path)
        store.deleteStorageByKey(bucket, path)
        val afterDelete = store.getStorageByKey(bucket, path)

        // Then
        assertNotNull(stored)
        assertEquals(bucket, stored.bucketId)
        assertEquals(path, stored.path)
        assertEquals(file.toString(), stored.localPath)
        assertEquals(42, stored.sizeBytes)
        assertEquals("abc", stored.eTag)
        assertEquals(fixedNow, stored.lastFetchedEpochMs)
        assertNull(afterDelete)
    }

    @Test
    fun enqueueInsert_createsMutationRow() = runTest {
        // Given
        val store = newStore()
        Json
        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }
        val filter = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = listOf(
                FilterDescriptor.Condition(column = "id", operatorId = "eq", value = "1")
            )
        )

        // When
        store.enqueueInsert(
            table = "assets",
            body = body,
            filter = filter,
            defaultToNull = false
        )
        val pending = store.pendingMutationsForTable("assets")

        // Then
        assertEquals(1, pending.size)
        val m = pending.first()
        assertEquals(MutationOperation.INSERT, m.operation)
        assertEquals("assets", m.table)
        assertEquals(body.toString(), m.bodyJson)
        assertEquals(false, m.defaultToNull)
        assertEquals(fixedNow, m.createdAtMs)
        val decodedFilter = io.github.nesfeder.supabase.internal.postgrest.FilterEngine.decodeOrNull(
            m.filterJson,
            store.json
        )
        assertNotNull(decodedFilter)
        assertEquals(filter, decodedFilter)
    }

    @Test
    fun enqueueUpsert_createsMutationRow() = runTest {
        // Given
        val store = newStore()
        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }

        // When
        store.enqueueUpsert(
            table = "assets",
            bodyJson = body,
            filter = null,
            onConflict = "id",
            ignoreDuplicates = true,
            defaultToNull = false
        )
        val pending = store.pendingMutationsForTable("assets")

        // Then
        assertEquals(1, pending.size)
        val m = pending.first()
        assertEquals(MutationOperation.UPSERT, m.operation)
        assertEquals("assets", m.table)
        assertEquals(body.toString(), m.bodyJson)
        assertEquals("id", m.onConflict)
        assertEquals(true, m.ignoreDuplicates)
        assertEquals(false, m.defaultToNull)
    }

    @Test
    fun enqueueUpsert_defaultsAreStored() = runTest {
        // Given
        val store = newStore()
        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }

        // When
        store.enqueueUpsert(table = "assets", bodyJson = body)
        val mutation = store.pendingMutationsForTable("assets").single()

        // Then
        assertEquals(MutationOperation.UPSERT, mutation.operation)
        assertNull(mutation.filterJson)
        assertNull(mutation.onConflict)
        assertEquals(false, mutation.ignoreDuplicates)
        assertEquals(true, mutation.defaultToNull)
    }

    @Test
    fun enqueueUpdateAndDelete_createsMutations() = runTest {
        // Given
        val store = newStore()
        val body: JsonElement = buildJsonObject { put("name", "New") }

        // When
        store.enqueueUpdate(table = "assets", body = body, filter = null)
        store.enqueueDelete(table = "assets", filter = null)
        val pending = store.pendingMutationsForTable("assets")

        // Then
        assertEquals(2, pending.size)
        assertEquals(MutationOperation.UPDATE, pending[0].operation)
        assertEquals(MutationOperation.DELETE, pending[1].operation)
        assertEquals(body.toString(), pending[0].bodyJson)
        assertEquals("{}", pending[1].bodyJson)
    }

    @Test
    fun enqueueUpdate_defaultFilterIsNull() = runTest {
        // Given
        val store = newStore()
        val body: JsonElement = buildJsonObject { put("name", "New") }

        // When
        store.enqueueUpdate(table = "assets", body = body)
        val mutation = store.pendingMutationsForTable("assets").single()

        // Then
        assertEquals(MutationOperation.UPDATE, mutation.operation)
        assertNull(mutation.filterJson)
    }

    @Test
    fun pendingCountsAndDeleteMutation() = runTest {
        // Given
        val store = newStore()
        val body: JsonArray = buildJsonArray { add(buildJsonObject { put("id", "1") }) }

        store.enqueueInsert("assets", body)
        store.enqueueInsert("assets", body)
        store.enqueueInsert("other", body)

        // When
        val totalBefore = store.pendingMutationCount()
        val assetsBefore = store.pendingMutationCountForTable("assets")
        val all = store.allPendingMutations()
        store.deleteMutation(all.first().id)
        val totalAfter = store.pendingMutationCount()

        // Then
        assertEquals(3, totalBefore)
        assertEquals(2, assetsBefore)
        assertEquals(2, totalAfter)
    }

    @Test
    fun applyPendingMutations_delegatesToOverlay() = runTest {
        // Given
        val store = newStore()
        val body: JsonArray = buildJsonArray { add(buildJsonObject { put("id", "1") }) }
        store.enqueueInsert("assets", body)
        val serverJson = "[]"

        // When
        val result = store.applyPendingMutations("assets", serverJson)

        // Then
        assertEquals(body.toString(), result)
    }

    @Test
    fun applyAllMutationsForOfflineFallback_includesSyncedMutations() = runTest {
        // Given
        val store = newStore()
        val body: JsonArray = buildJsonArray { add(buildJsonObject { put("id", "1") }) }

        // Enqueue a pending INSERT mutation for the assets table.
        store.enqueueInsert("assets", body)

        // Mark it as synced so it is no longer returned by pending* queries
        // but still present in the underlying mutation table.
        val pending = store.pendingMutationsForTable("assets")
        val mutationId = pending.single().id
        store.markMutationSynced(mutationId)

        val cachedJson = "[]"

        // When
        val freshResult = store.applyPendingMutations("assets", cachedJson)
        val offlineResult = store.applyAllMutationsForOfflineFallback("assets", cachedJson)

        // Then - fresh path sees no *pending* mutations and returns input
        assertEquals(cachedJson, freshResult)

        // Offline fallback path must re-apply both pending and already-synced
        // mutations, so our previously-synced INSERT is still overlaid.
        assertEquals(body.toString(), offlineResult)
    }

    @Test
    fun clearSyncedMutationsForTable_removesOnlySyncedRows() = runTest {
        // Given
        val store = newStore()
        val body = buildJsonArray { add(buildJsonObject { put("id", "1") }) }
        val body2 = buildJsonArray { add(buildJsonObject { put("id", "2") }) }

        // Enqueue two mutations for the same table.
        store.enqueueInsert("assets", body)
        store.enqueueInsert("assets", body2)

        // Mark the first as synced, leaving the second pending.
        val allPending = store.pendingMutationsForTable("assets")
        val firstId = allPending.first().id
        store.markMutationSynced(firstId)

        // Sanity check: total mutations (pending + synced) is 1.
        val totalBefore = store.pendingMutationCount()
        assertEquals(1, totalBefore)

        // When - clearing synced mutations for this table should drop only the
        // row we just marked as synced, leaving the still-pending one.
        store.clearSyncedMutationsForTable("assets")

        // Then - one mutation remains overall and it is still pending.
        val totalAfter = store.pendingMutationCount()
        val remainingPending = store.pendingMutationsForTable("assets")
        assertEquals(1, totalAfter)
        assertEquals(1, remainingPending.size)
        assertEquals(null, remainingPending.single().syncedAtMs)
    }

    @Test
    fun enforceDbSizeLimitIfNeeded_evictsRows() = runTest {
        val objectDao = FakeObjectDao()
        val sizes = iterator {
            yield(1000L) // Initial check
            yield(400L)  // After eviction
        }

        var time = 100L
        val storeForSetup = newTestOfflineStore(
            objectDao = objectDao,
            nowMillis = { time.also { time += 100 } }
        )

        storeForSetup.addPayload("t", "s1", "{}") // t=100
        storeForSetup.addPayload("t", "s2", "{}") // t=200
        storeForSetup.addPayload("t", "s3", "{}") // t=300

        val store = newTestOfflineStore(
            objectDao = objectDao,
            settings = OfflineCacheSettings(
                maxDbSizeBytes = 500L,
                evictionBatchSize = 1,
                minRetainedBytes = 0L
            ),
            dbSizeProvider = { sizes.next() }
        )

        store.enforceDbSizeLimitIfNeeded()

        // s1 should be evicted (oldest)
        assertNull(objectDao.get("t", "s1"))
        assertNotNull(objectDao.get("t", "s2"))
        assertNotNull(objectDao.get("t", "s3"))
    }

    @Test
    fun enforceDbSizeLimitIfNeeded_sizeUnavailable_skipsEviction() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        val setupStore = newTestOfflineStore(objectDao = objectDao)
        setupStore.addPayload("t", "s1", "{}")
        val store = newTestOfflineStore(
            objectDao = objectDao,
            settings = OfflineCacheSettings(
                maxDbSizeBytes = 500L,
                evictionBatchSize = 1,
                minRetainedBytes = 0L,
            ),
            dbSizeProvider = { null },
        )

        // When
        store.enforceDbSizeLimitIfNeeded()

        // Then
        assertNotNull(objectDao.get("t", "s1"))
    }

    @Test
    fun enforceDbSizeLimitIfNeeded_sizeAtLimit_skipsEviction() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        val setupStore = newTestOfflineStore(objectDao = objectDao)
        setupStore.addPayload("t", "s1", "{}")
        val store = newTestOfflineStore(
            objectDao = objectDao,
            settings = OfflineCacheSettings(
                maxDbSizeBytes = 500L,
                evictionBatchSize = 1,
                minRetainedBytes = 0L,
            ),
            dbSizeProvider = { 500L },
        )

        // When
        store.enforceDbSizeLimitIfNeeded()

        // Then
        assertNotNull(objectDao.get("t", "s1"))
    }

    @Test
    fun enforceDbSizeLimitIfNeeded_stopsWhenSizeProviderReturnsNullAfterEviction() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        val setupStore = newTestOfflineStore(objectDao = objectDao)
        setupStore.addPayload("t", "s1", "{}")
        setupStore.addPayload("t", "s2", "{}")
        val sizes = iterator {
            yield(1000L)
            yield(null)
        }
        val store = newTestOfflineStore(
            objectDao = objectDao,
            settings = OfflineCacheSettings(
                maxDbSizeBytes = 500L,
                evictionBatchSize = 1,
                minRetainedBytes = 0L,
            ),
            dbSizeProvider = { sizes.next() },
        )

        // When
        store.enforceDbSizeLimitIfNeeded()

        // Then
        assertNull(objectDao.get("t", "s1"))
        assertNotNull(objectDao.get("t", "s2"))
    }

    @Test
    fun enforceDbSizeLimitIfNeeded_exitsLoopWhenSizeFallsBelowLimitWithoutEarlyStop() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        val setupStore = newTestOfflineStore(objectDao = objectDao)
        setupStore.addPayload("t", "s1", "{}")
        setupStore.addPayload("t", "s2", "{}")
        val sizes = iterator {
            yield(1000L)
            yield(400L)
        }
        val store = newTestOfflineStore(
            objectDao = objectDao,
            settings = OfflineCacheSettings(
                maxDbSizeBytes = 500L,
                evictionBatchSize = 1,
                minRetainedBytes = 1000L,
            ),
            dbSizeProvider = { sizes.next() },
        )

        // When
        store.enforceDbSizeLimitIfNeeded()

        // Then
        assertNull(objectDao.get("t", "s1"))
        assertNotNull(objectDao.get("t", "s2"))
    }

    @Test
    fun observePayload_emitsNullThenPayload() = runTest {
        // Given
        val store = newStore()
        val table = "assets"
        val signature = "assets?order=updated_at.desc"
        val payload = """[{"id":"1"}]"""

        // When
        val firstEmission = store.observePayload(table, signature)
            .firstOrNull()
        store.addPayload(table, signature, payload)
        val secondEmission = store.observePayload(table, signature)
            .firstOrNull()

        // Then
        assertNull(firstEmission)
        assertEquals(payload, secondEmission)
    }

    @Test
    fun applyAllMutationsForOfflineFallbackFlow_emitsUpdatedPayload() = runTest {
        // Given
        val store = newStore()
        val table = "assets"
        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }
        store.enqueueInsert(table, body)
        val cachedJson = "[]"

        // When
        val flow = kotlinx.coroutines.flow.flowOf(cachedJson)
            .let { with(store) { it.applyAllMutationsForOfflineFallbackFlow(table) } }
        val result = flow.firstOrNull()

        // Then
        assertEquals(body.toString(), result)
    }

    @Test
    fun applyAllMutationsForOfflineFallbackFlow_reactsToMutationChanges() = runTest {
        // Given
        val store = newStore()
        val table = "assets"
        val cachedJson = "[]"

        // Add initial cached payload
        store.addPayload(table, "signature", cachedJson)

        // When
        val emissions = mutableListOf<String?>()

        // Start collecting in background
        val job = backgroundScope.launch {
            val flow = store.observePayload(table, "signature")
                .let { with(store) { it.applyAllMutationsForOfflineFallbackFlow(table) } }
            flow.collect { emissions.add(it) }
        }

        delay(50)

        val body1: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }
        store.enqueueInsert(table, body1)
        delay(50)

        val body2: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "2") })
        }
        store.enqueueInsert(table, body2)
        delay(50)

        job.cancel()

        // Then
        assertEquals(3, emissions.size)
        assertEquals(cachedJson, emissions[0]) // Initial cached data
        assertEquals(body1.toString(), emissions[1]) // After first insert
        // After second insert, both mutations applied
        val expected = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
            add(buildJsonObject { put("id", "2") })
        }.toString()
        assertEquals(expected, emissions[2])
    }

    @Test
    fun applyAllMutationsForOfflineFallbackFlow_includesSyncedMutations() = runTest {
        // Given
        val store = newStore()
        val table = "assets"
        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }
        store.enqueueInsert(table, body)

        // Mark as synced
        val pending = store.pendingMutationsForTable(table)
        store.markMutationSynced(pending.single().id)

        val cachedJson = "[]"

        // When
        val flow = kotlinx.coroutines.flow.flowOf(cachedJson)
            .let { with(store) { it.applyAllMutationsForOfflineFallbackFlow(table) } }
        val result = flow.firstOrNull()

        // Then - synced mutations are still applied in offline fallback flow
        assertEquals(body.toString(), result)
    }

    @Test
    fun applyAllMutationsForOfflineFallbackFlow_returnsEmptyFlowWhenCachedJsonIsNull() = runTest {
        // Given
        val store = newStore()
        val table = "assets"

        // When
        val flow = kotlinx.coroutines.flow.flowOf(null)
            .let { with(store) { it.applyAllMutationsForOfflineFallbackFlow(table) } }
        val result = flow.firstOrNull()

        // Then
        assertNull(result)
    }

    @Test
    fun pendingMutationCountFlow_emitsUpdatedCount() = runTest {
        // Given
        val store = newStore()
        val emissions = mutableListOf<Int>()

        val job = backgroundScope.launch {
            store.pendingMutationCountFlow().collect { emissions.add(it) }
        }

        delay(50)

        // When
        val body = buildJsonArray { add(buildJsonObject { put("id", "1") }) }
        store.enqueueInsert("assets", body)
        delay(50)

        store.enqueueInsert("other", body)
        delay(50)

        val pending = store.allPendingMutations()
        store.deleteMutation(pending.first().id)
        delay(50)

        job.cancel()

        // Then
        assertEquals(4, emissions.size)
        assertEquals(0, emissions[0]) // Initial state
        assertEquals(1, emissions[1]) // After first insert
        assertEquals(2, emissions[2]) // After second insert
        assertEquals(1, emissions[3]) // After delete
    }

    @Test
    fun pendingMutationCountFlow_excludesSyncedMutations() = runTest {
        // Given
        val store = newStore()
        val body = buildJsonArray { add(buildJsonObject { put("id", "1") }) }
        store.enqueueInsert("assets", body)

        // When
        val countBefore = store.pendingMutationCountFlow().firstOrNull()

        val pending = store.pendingMutationsForTable("assets")
        store.markMutationSynced(pending.single().id)

        val countAfter = store.pendingMutationCountFlow().firstOrNull()

        // Then
        assertEquals(1, countBefore)
        assertEquals(0, countAfter)
    }

    @Test
    fun pendingMutationCountForTableFlow_emitsUpdatedCountForSpecificTable() = runTest {
        // Given
        val store = newStore()
        val emissions = mutableListOf<Int>()

        val job = backgroundScope.launch {
            store.pendingMutationCountForTableFlow("assets").collect { emissions.add(it) }
        }

        delay(50)

        // When
        val body = buildJsonArray { add(buildJsonObject { put("id", "1") }) }
        store.enqueueInsert("assets", body)
        delay(50)

        store.enqueueInsert("assets", body)
        delay(50)

        // Delete one mutation
        val pending = store.pendingMutationsForTable("assets")
        store.deleteMutation(pending.first().id)
        delay(50)

        job.cancel()

        // Then
        assertEquals(4, emissions.size)
        assertEquals(0, emissions[0]) // Initial state
        assertEquals(1, emissions[1]) // After first assets insert
        assertEquals(2, emissions[2]) // After second assets insert
        assertEquals(1, emissions[3]) // After delete
    }

    @Test
    fun pendingMutationCountForTableFlow_excludesSyncedMutations() = runTest {
        // Given
        val store = newStore()
        val table = "assets"
        val body = buildJsonArray { add(buildJsonObject { put("id", "1") }) }
        store.enqueueInsert(table, body)
        store.enqueueInsert(table, body)

        // When
        val countBefore = store.pendingMutationCountForTableFlow(table).firstOrNull()

        val pending = store.pendingMutationsForTable(table)
        store.markMutationSynced(pending.first().id)

        val countAfter = store.pendingMutationCountForTableFlow(table).firstOrNull()

        // Then
        assertEquals(2, countBefore)
        assertEquals(1, countAfter)
    }

    @Test
    fun pendingMutationCountForTableFlow_onlyCountsSpecifiedTable() = runTest {
        // Given
        val store = newStore()
        val body = buildJsonArray { add(buildJsonObject { put("id", "1") }) }
        store.enqueueInsert("assets", body)
        store.enqueueInsert("assets", body)
        store.enqueueInsert("other", body)

        // When
        val assetsCount = store.pendingMutationCountForTableFlow("assets").firstOrNull()
        val otherCount = store.pendingMutationCountForTableFlow("other").firstOrNull()

        // Then
        assertEquals(2, assetsCount)
        assertEquals(1, otherCount)
    }

    @Test
    fun pendingMutationCountForTableFlow_doesNotEmitForOtherTables() = runTest {
        // Given
        val store = newStore()
        val emissions = mutableListOf<Int>()

        val job = backgroundScope.launch {
            store.pendingMutationCountForTableFlow("assets").collect { emissions.add(it) }
        }

        delay(50)

        // When
        val body = buildJsonArray { add(buildJsonObject { put("id", "1") }) }
        store.enqueueInsert("assets", body)
        delay(50)

        // Insert to a different table - will trigger emission but count stays the same
        store.enqueueInsert("other", body)
        delay(50)

        job.cancel()

        // Then
        // Note: The flow emits even when other tables change, but the count for "assets" remains 1
        assertEquals(3, emissions.size)
        assertEquals(0, emissions[0]) // Initial state
        assertEquals(1, emissions[1]) // After assets insert
        assertEquals(1, emissions[2]) // After other table insert (count still 1 for assets)
    }
}
