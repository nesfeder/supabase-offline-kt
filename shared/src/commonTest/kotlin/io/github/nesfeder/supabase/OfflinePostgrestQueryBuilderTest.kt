@file:OptIn(io.github.jan.supabase.annotations.SupabaseExperimental::class)

package io.github.nesfeder.supabase

import io.github.nesfeder.supabase.internal.CapturingLogger
import io.github.nesfeder.supabase.internal.FakePostgrest
import io.github.nesfeder.supabase.internal.db.FakeMutationDao
import io.github.nesfeder.supabase.internal.db.FakeObjectDao
import io.github.nesfeder.supabase.internal.db.FakeStorageDao
import io.github.nesfeder.supabase.internal.db.MutationOperation
import io.github.nesfeder.supabase.internal.db.OfflineStore
import io.github.nesfeder.supabase.internal.db.newTestOfflineStore
import io.github.nesfeder.supabase.internal.makeFakeHttpResponse
import io.github.nesfeder.supabase.internal.postgrest.FakePostgrestQueryBuilderWrapper
import io.github.nesfeder.supabase.internal.postgrest.SignatureBuilder
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class OfflinePostgrestQueryBuilderTest {

    // ---------------------------------------------------------------------
    // SELECT: success, overlay, cache fallback, error paths
    // ---------------------------------------------------------------------

    @Test
    fun select_success_noOverlay_cachesAndReturnsNetworkResult() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val networkPayload = """[{"id":1}]"""
        val networkResult = fakeResult(postgrest, networkPayload)

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            selectResult = Result.success(networkResult)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When
        val result = offline.select { }

        // Then - we get the same PostgrestResult instance (no overlay)...
        assertSame(networkResult, result)

        // ...and it was cached under the expected signature ("assets").
        val cached = store.getPayload("assets", "assets")
        assertEquals(networkPayload, cached)
    }

    @Test
    fun select_success_withPendingAndSyncedMutations_appliesOverlayAndClearsSynced() = runTest {
        // Given
        val mutationDao = FakeMutationDao()
        val store = newTestOfflineStore(
            objectDao = FakeObjectDao(),
            storageDao = FakeStorageDao(),
            mutationDao = mutationDao,
        )

        val networkPayload = """[{"id":1}]"""

        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            selectResult = Result.success(fakeResult(postgrest, networkPayload))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // Pending INSERT (id = 2) that overlay should apply.
        val pendingBody = buildJsonArray {
            add(buildJsonObject { put("id", JsonPrimitive(2)) })
        }
        store.enqueueInsert("assets", pendingBody)

        // A second mutation for the same table that is already marked synced.
        val syncedBody = buildJsonArray {
            add(buildJsonObject { put("id", JsonPrimitive(3)) })
        }
        store.enqueueInsert("assets", syncedBody)
        val all = mutationDao.allForTable("assets")
        val syncedMutation = all.last()
        mutationDao.markSynced(syncedMutation.id, syncedAtMs = 123L)

        // Sanity: 2 mutations exist, 1 pending
        assertEquals(1, mutationDao.countPendingForTable("assets"))
        assertEquals(1, store.pendingMutationCountForTable("assets"))

        // When - SELECT succeeds, overlay applies pending mutations and
        // clearSyncedMutationsForTable removes the already-synced ones.
        val result = offline.select { }

        // Then - overlay should have introduced "2" somewhere in the payload.
        assertNotEquals(networkPayload, result.data)
        require(result.data.contains("1")) { "Expected result to contain id=1" }
        require(result.data.contains("2")) { "Expected result to contain id=2 from pending insert" }

        // Only the still-pending mutation remains in the DAO; synced one was cleared.
        assertEquals(1, mutationDao.countPendingForTable("assets"))
    }

    @Test
    fun select_networkFailure_withCachedPayload_returnsCachedData() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            selectResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // Seed cache with the exact signature used by select { }.
        val signature = SignatureBuilder.build(
            table = "assets",
            builderFactory = { SelectRequestBuilder(postgrest.config.propertyConversionMethod) },
        ) { }
        val cachedPayload = """[{"id":1}]"""
        store.addPayload("assets", signature, cachedPayload)

        // When
        val result = offline.select { }

        // Then
        assertEquals(cachedPayload, result.data)
    }

    @Test
    fun select_withColumns_networkFailure_returnsCached() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            selectResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // Signature for select(id,name)
        val columns = Columns.list("id", "name")
        val signature = SignatureBuilder.build(
            table = "assets",
            columns = columns,
            builderFactory = { SelectRequestBuilder(postgrest.config.propertyConversionMethod) },
        ) {
            // In real usage, supabase-kt will fill params based on Columns.list(...)
            // but SignatureBuilder handles columns explicitly, so request is empty.
        }
        val cachedPayload = """[{"id":1,"name":"foo"}]"""
        store.addPayload("assets", signature, cachedPayload)

        // When
        val result = offline.select(columns = columns) { }

        // Then
        assertEquals(cachedPayload, result.data)
    }

    @Test
    fun select_networkFailure_withoutCachedPayload_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            selectResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When / Then - no cached payload means we rethrow.
        assertFailsWith<IOException> {
            offline.select { }
        }
    }

    @Test
    fun select_nonNetworkFailure_rethrowsWithoutUsingCache() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            selectResult = Result.failure(IllegalStateException("boom"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // Seed cache that should NOT be used because this is not a network error.
        store.addPayload("assets", "assets", """["cached"]""")

        // When / Then
        assertFailsWith<IllegalStateException> {
            offline.select { }
        }
    }

    // ---------------------------------------------------------------------
    // SELECT AS FLOW: CacheFirstThenNetwork, NetworkFirstThenCache, CacheOnly
    // ---------------------------------------------------------------------

    @Test
    fun selectAsFlow_cacheFirstThenNetwork_success_emitsNetworkData() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val networkPayload = """[{"id":1,"name":"test"}]"""
        val networkResult = fakeResult(postgrest, networkPayload)

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            selectResult = Result.success(networkResult)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When
        val flow = offline.selectAsFlow(readStrategy = FlowReadStrategy.CacheFirstThenNetwork) { }
        val firstResult = flow.first()

        // Then
        assertEquals(networkPayload, firstResult.data)
    }

    @Test
    fun selectAsFlow_cacheFirstThenNetwork_networkFailure_continuesEmittingCachedData() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val cachedPayload = """[{"id":1,"name":"cached"}]"""

        // Seed cache
        val signature = SignatureBuilder.build(
            table = "assets",
            builderFactory = { SelectRequestBuilder(postgrest.config.propertyConversionMethod) },
        ) { }
        store.addPayload("assets", signature, cachedPayload)

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            selectResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When
        val flow = offline.selectAsFlow(readStrategy = FlowReadStrategy.CacheFirstThenNetwork) { }
        val firstResult = flow.first()

        // Then - should still emit cached data despite network failure
        assertEquals(cachedPayload, firstResult.data)
    }

    @Test
    fun selectAsFlow_networkFirstThenCache_success_emitsNetworkData() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val networkPayload = """[{"id":1,"name":"network"}]"""
        val networkResult = fakeResult(postgrest, networkPayload)

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            selectResult = Result.success(networkResult)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When
        val flow = offline.selectAsFlow(readStrategy = FlowReadStrategy.NetworkFirstThenCache) { }
        val firstResult = flow.first()

        // Then
        assertEquals(networkPayload, firstResult.data)
    }

    @Test
    fun selectAsFlow_networkFirstThenCache_networkFailure_flowFails() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val cachedPayload = """[{"id":1,"name":"cached"}]"""

        // Seed cache (but NetworkFirstThenCache should fail on network error regardless)
        val signature = SignatureBuilder.build(
            table = "assets",
            builderFactory = { SelectRequestBuilder(postgrest.config.propertyConversionMethod) },
        ) { }
        store.addPayload("assets", signature, cachedPayload)

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            selectResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When
        val flow = offline.selectAsFlow(readStrategy = FlowReadStrategy.NetworkFirstThenCache) { }

        // Then - flow should fail with network error
        assertFailsWith<IOException> {
            flow.first()
        }
    }

    @Test
    fun selectAsFlow_cacheOnly_emitsCachedData() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val cachedPayload = """[{"id":1,"name":"cached"}]"""

        // Seed cache
        val signature = SignatureBuilder.build(
            table = "assets",
            builderFactory = { SelectRequestBuilder(postgrest.config.propertyConversionMethod) },
        ) { }
        store.addPayload("assets", signature, cachedPayload)

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            // Set failure to ensure network is never called
            selectResult = Result.failure(AssertionError("Network should not be called"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When
        val flow = offline.selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly) { }
        val firstResult = flow.first()

        // Then
        assertEquals(cachedPayload, firstResult.data)
    }

    @Test
    fun selectAsFlow_cacheOnly_neverTriggersNetwork() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val cachedPayload = """[{"id":1,"name":"cached"}]"""

        val signature = SignatureBuilder.build(
            table = "assets",
            builderFactory = { SelectRequestBuilder(postgrest.config.propertyConversionMethod) },
        ) { }
        store.addPayload("assets", signature, cachedPayload)

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            // Set failure to ensure network throws if called
            selectResult = Result.failure(AssertionError("Network should not be called"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When
        val flow = offline.selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly) { }
        val firstResult = flow.first()

        // Then - should emit cached data without triggering network
        assertEquals(cachedPayload, firstResult.data)
    }

    @Test
    fun selectAsFlow_withColumns_cacheOnly_emitsCachedData() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val cachedPayload = """[{"id":1,"name":"test"}]"""
        val columns = Columns.list("id", "name")

        val signature = SignatureBuilder.build(
            table = "assets",
            columns = columns,
            builderFactory = { SelectRequestBuilder(postgrest.config.propertyConversionMethod) },
        ) { }
        store.addPayload("assets", signature, cachedPayload)

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest)
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When
        val flow = offline.selectAsFlow(columns = columns, readStrategy = FlowReadStrategy.CacheOnly) { }
        val firstResult = flow.first()

        // Then
        assertEquals(cachedPayload, firstResult.data)
    }

    // ---------------------------------------------------------------------
    // INSERT: success, network failure, non-network failure, strategy paths
    // ---------------------------------------------------------------------

    @Test
    fun insertJson_success_doesNotEnqueueMutations() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val networkResult = fakeResult(postgrest, """[{"id":1}]""")
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            insertResult = Result.success(networkResult)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonArray {
            add(buildJsonObject { put("id", JsonPrimitive("1")) })
        }

        // When
        val result = offline.insertJson(body) { }

        // Then
        assertEquals(networkResult.data, result.data)
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun insertJson_networkFailure_enqueueIfOffline_enqueuesMutation() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            insertResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive("1"))
            })
        }

        // When - the delegate insert throws, so we fall back to offline queueing.
        val result = offline.insertJson(body, mutationStrategy = MutationStrategy.QueueIfOffline) { }

        // Then - result is an empty array placeholder and a pending INSERT
        assertEquals("[]", result.data)

        val pending = store.pendingMutationsForTable("assets")
        assertEquals(1, pending.size)

        val mutation = pending.first()
        assertEquals(MutationOperation.INSERT, mutation.operation)
        assertEquals("assets", mutation.table)
        assertEquals(body.toString(), mutation.bodyJson)
        assertEquals(true, mutation.defaultToNull)
        assertNull(mutation.filterJson)
    }

    @Test
    fun insertJson_networkFailure_onlineOnly_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            insertResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive("1"))
            })
        }

        // When / Then
        assertFailsWith<IOException> {
            offline.insertJson(body, mutationStrategy = MutationStrategy.OnlineOnly) { }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun insertJson_nonNetworkFailure_enqueueIfOffline_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val response = makeFakeHttpResponse(
            status = HttpStatusCode.BadRequest,
            body = """{"message":"bad"}"""
        )
        val exception = PostgrestRestException(
            message = "bad",
            hint = null,
            details = null,
            code = "400",
            response = response
        )

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            insertResult = Result.failure(exception)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonArray {
            add(buildJsonObject { put("id", JsonPrimitive("1")) })
        }

        // When / Then
        assertFailsWith<PostgrestRestException> {
            offline.insertJson(body, mutationStrategy = MutationStrategy.QueueIfOffline) { }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun insertJson_nonNetworkFailure_onlineOnly_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val response = makeFakeHttpResponse(
            status = HttpStatusCode.BadRequest,
            body = """{"message":"bad"}"""
        )
        val exception = PostgrestRestException(
            message = "bad",
            hint = null,
            details = null,
            code = "400",
            response = response
        )

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            insertResult = Result.failure(exception)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonArray {
            add(buildJsonObject { put("id", JsonPrimitive("1")) })
        }

        // When / Then
        assertFailsWith<PostgrestRestException> {
            offline.insertJson(body, mutationStrategy = MutationStrategy.OnlineOnly) { }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    // ---------------------------------------------------------------------
    // UPSERT: success, network failure, non-network failure, strategy paths
    // ---------------------------------------------------------------------

    @Test
    fun upsertJson_success_doesNotEnqueueMutations() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val networkResult = fakeResult(postgrest, """[{"id":1}]""")
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            upsertResult = Result.success(networkResult)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonArray {
            add(buildJsonObject { put("id", JsonPrimitive("1")) })
        }

        // When
        val result = offline.upsertJson(body) { onConflict = "id" }

        // Then
        assertEquals(networkResult.data, result.data)
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun upsertJson_networkFailure_enqueueIfOffline_enqueuesMutation() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            upsertResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive("1"))
                put("name", JsonPrimitive("foo"))
            })
        }

        // When
        val result = offline.upsertJson(body, mutationStrategy = MutationStrategy.QueueIfOffline) { onConflict = "id" }

        // Then
        assertEquals("[]", result.data)
        assertEquals(1, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun upsertJson_networkFailure_onlineOnly_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            upsertResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive("1"))
                put("name", JsonPrimitive("foo"))
            })
        }

        // When / Then
        assertFailsWith<IOException> {
            offline.upsertJson(body, mutationStrategy = MutationStrategy.OnlineOnly) { onConflict = "id" }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun upsertJson_nonNetworkFailure_enqueueIfOffline_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val response = makeFakeHttpResponse(
            status = HttpStatusCode.BadRequest,
            body = """{"message":"bad"}"""
        )
        val exception = PostgrestRestException(
            message = "bad",
            hint = null,
            details = null,
            code = "400",
            response = response
        )

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            upsertResult = Result.failure(exception)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive("1"))
            })
        }

        // When / Then
        assertFailsWith<PostgrestRestException> {
            offline.upsertJson(body, mutationStrategy = MutationStrategy.QueueIfOffline) { onConflict = "id" }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun upsertJson_nonNetworkFailure_onlineOnly_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val response = makeFakeHttpResponse(
            status = HttpStatusCode.BadRequest,
            body = """{"message":"bad"}"""
        )
        val exception = PostgrestRestException(
            message = "bad",
            hint = null,
            details = null,
            code = "400",
            response = response
        )

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            upsertResult = Result.failure(exception)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive("1"))
            })
        }

        // When / Then
        assertFailsWith<PostgrestRestException> {
            offline.upsertJson(body, mutationStrategy = MutationStrategy.OnlineOnly) { onConflict = "id" }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    // ---------------------------------------------------------------------
    // UPDATE: success, network failure, non-network failure, strategy paths
    // ---------------------------------------------------------------------

    @Test
    fun updateJson_success_doesNotEnqueueMutations() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val networkResult = fakeResult(postgrest, """[{"status":"ok"}]""")
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            updateResult = Result.success(networkResult)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonObject { put("status", JsonPrimitive("active")) }

        // When
        val result = offline.updateJson(body) { }

        // Then
        assertEquals(networkResult.data, result.data)
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun updateJson_networkFailure_enqueueIfOffline_enqueuesMutation() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            updateResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonObject {
            put("status", JsonPrimitive("active"))
        }

        // When - delegate.update throws and we enqueue an UPDATE mutation.
        val result = offline.updateJson(body, mutationStrategy = MutationStrategy.QueueIfOffline) {
            params["id"] = listOf("eq.1")
        }

        // Then
        assertEquals("[]", result.data)

        val pending = store.pendingMutationsForTable("assets")
        assertEquals(1, pending.size)

        val mutation = pending.first()
        assertEquals(MutationOperation.UPDATE, mutation.operation)
        assertEquals("assets", mutation.table)
        assertEquals(body.toString(), mutation.bodyJson)
        assertNotNull(mutation.filterJson)
    }

    @Test
    fun updateJson_networkFailure_onlineOnly_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            updateResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonObject {
            put("status", JsonPrimitive("active"))
        }

        // When / Then
        assertFailsWith<IOException> {
            offline.updateJson(body, mutationStrategy = MutationStrategy.OnlineOnly) { }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun updateJson_nonNetworkFailure_enqueueIfOffline_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val response = makeFakeHttpResponse(
            status = HttpStatusCode.BadRequest,
            body = """{"message":"bad"}"""
        )
        val exception = PostgrestRestException(
            message = "bad",
            hint = null,
            details = null,
            code = "400",
            response = response
        )

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            updateResult = Result.failure(exception)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonObject {
            put("status", JsonPrimitive("active"))
        }

        // When / Then
        assertFailsWith<PostgrestRestException> {
            offline.updateJson(body, mutationStrategy = MutationStrategy.QueueIfOffline) { }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun updateJson_nonNetworkFailure_onlineOnly_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val response = makeFakeHttpResponse(
            status = HttpStatusCode.BadRequest,
            body = """{"message":"bad"}"""
        )
        val exception = PostgrestRestException(
            message = "bad",
            hint = null,
            details = null,
            code = "400",
            response = response
        )

        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            updateResult = Result.failure(exception)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        val body = buildJsonObject {
            put("status", JsonPrimitive("active"))
        }

        // When / Then
        assertFailsWith<PostgrestRestException> {
            offline.updateJson(body, mutationStrategy = MutationStrategy.OnlineOnly) { }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    // ---------------------------------------------------------------------
    // DELETE: success, network failure, non-network failure, strategy paths
    // ---------------------------------------------------------------------

    @Test
    fun delete_success_doesNotEnqueueMutations() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val networkResult = fakeResult(postgrest, """[]""")
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            deleteResult = Result.success(networkResult)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When
        val result = offline.delete { }

        // Then
        assertEquals(networkResult.data, result.data)
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun delete_networkFailure_enqueueIfOffline_enqueuesMutation() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            deleteResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When - delegate.delete throws and we enqueue a DELETE mutation.
        val result = offline.delete(mutationStrategy = MutationStrategy.QueueIfOffline) {
            params["id"] = listOf("eq.1")
        }

        // Then
        assertEquals("[]", result.data)

        val pending = store.pendingMutationsForTable("assets")
        assertEquals(1, pending.size)

        val mutation = pending.first()
        assertEquals(MutationOperation.DELETE, mutation.operation)
        assertEquals("assets", mutation.table)
        assertNotNull(mutation.filterJson)
    }

    @Test
    fun delete_networkFailure_onlineOnly_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            deleteResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When / Then
        assertFailsWith<IOException> {
            offline.delete(mutationStrategy = MutationStrategy.OnlineOnly) { }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun delete_nonNetworkFailure_enqueueIfOffline_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val response = makeFakeHttpResponse(
            status = HttpStatusCode.BadRequest,
            body = """{"message":"bad"}"""
        )
        val exception = PostgrestRestException(
            message = "bad",
            hint = null,
            details = null,
            code = "400",
            response = response
        )
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            deleteResult = Result.failure(exception)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When / Then
        assertFailsWith<PostgrestRestException> {
            offline.delete(mutationStrategy = MutationStrategy.QueueIfOffline) { }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun delete_nonNetworkFailure_onlineOnly_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val response = makeFakeHttpResponse(
            status = HttpStatusCode.BadRequest,
            body = """{"message":"bad"}"""
        )
        val exception = PostgrestRestException(
            message = "bad",
            hint = null,
            details = null,
            code = "400",
            response = response
        )
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            deleteResult = Result.failure(exception)
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)

        // When / Then
        assertFailsWith<PostgrestRestException> {
            offline.delete(mutationStrategy = MutationStrategy.OnlineOnly) { }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    // ---------------------------------------------------------------------
    // Reified helpers: insertWith / upsertWith / updateWith
    // ---------------------------------------------------------------------

    @Serializable
    private data class TestData(val id: Int, val name: String)

    @Test
    fun insertWith_list_networkFailure_queueIfOffline_enqueuesPendingMutation() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            insertResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)
        val data = listOf(TestData(1, "A"), TestData(2, "B"))

        // When
        offline.insertWith(TestData.serializer(), data, MutationStrategy.QueueIfOffline) { }

        // Then
        val pending = store.pendingMutationsForTable("assets")
        assertEquals(1, pending.size)
        val mutation = pending.first()
        assertEquals(MutationOperation.INSERT, mutation.operation)
        assertEquals("""[{"id":1,"name":"A"},{"id":2,"name":"B"}]""", mutation.bodyJson)
    }

    @Test
    fun insertWith_list_networkFailure_onlineOnly_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            insertResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)
        val data = listOf(TestData(1, "A"), TestData(2, "B"))

        // When / Then
        assertFailsWith<IOException> {
            offline.insertWith(TestData.serializer(), data, MutationStrategy.OnlineOnly) { }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun insertWith_list_success_passesEncodedBodyToDelegate() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            insertResult = Result.success(fakeResult(postgrest, """[{"ok":true}]"""))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)
        val data = listOf(TestData(1, "A"), TestData(2, "B"))

        // When
        val result = offline.insertWith(TestData.serializer(), data) { }

        // Then
        assertEquals(
            """[{"id":1,"name":"A"},{"id":2,"name":"B"}]""",
            delegate.lastInsertBody.toString()
        )
        assertEquals("""[{"ok":true}]""", result.data)
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun insertWith_single_networkFailure_queueIfOffline_enqueuesPendingMutation() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            insertResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)
        val data = TestData(1, "A")

        // When
        offline.insertWith(TestData.serializer(), data, MutationStrategy.QueueIfOffline) { }

        // Then
        val pending = store.pendingMutationsForTable("assets")
        assertEquals(1, pending.size)
        val mutation = pending.first()
        assertEquals(MutationOperation.INSERT, mutation.operation)
        assertEquals("""[{"id":1,"name":"A"}]""", mutation.bodyJson)
    }

    @Test
    fun upsertWith_list_networkFailure_queueIfOffline_enqueuesPendingMutation() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            upsertResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)
        val data = listOf(TestData(1, "A"), TestData(2, "B"))

        // When
        offline.upsertWith(TestData.serializer(), data, MutationStrategy.QueueIfOffline) {
            onConflict = "id"
        }

        // Then
        val pending = store.pendingMutationsForTable("assets")
        assertEquals(1, pending.size)
        val mutation = pending.first()
        assertEquals(MutationOperation.UPSERT, mutation.operation)
        assertEquals("""[{"id":1,"name":"A"},{"id":2,"name":"B"}]""", mutation.bodyJson)
        assertEquals("id", mutation.onConflict)
    }

    @Test
    fun upsertWith_single_networkFailure_queueIfOffline_enqueuesPendingMutation() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            upsertResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)
        val data = TestData(1, "A")

        // When
        offline.upsertWith(TestData.serializer(), data, MutationStrategy.QueueIfOffline) {
            onConflict = "id"
        }

        // Then
        val pending = store.pendingMutationsForTable("assets")
        assertEquals(1, pending.size)
        val mutation = pending.first()
        assertEquals(MutationOperation.UPSERT, mutation.operation)
        assertEquals("""[{"id":1,"name":"A"}]""", mutation.bodyJson)
        assertEquals("id", mutation.onConflict)
    }

    @Test
    fun upsertWith_networkFailure_onlineOnly_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            upsertResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)
        val data = TestData(1, "A")

        // When / Then
        assertFailsWith<IOException> {
            offline.upsertWith(TestData.serializer(), data, MutationStrategy.OnlineOnly) { onConflict = "id" }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    @Test
    fun updateWith_networkFailure_queueIfOffline_enqueuesPendingMutation() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            updateResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)
        val data = TestData(1, "A")

        // When
        offline.updateWith(TestData.serializer(), data, MutationStrategy.QueueIfOffline) {
            params["id"] = listOf("eq.1")
        }

        // Then
        val pending = store.pendingMutationsForTable("assets")
        assertEquals(1, pending.size)
        val mutation = pending.first()
        assertEquals(MutationOperation.UPDATE, mutation.operation)
        assertEquals("""{"id":1,"name":"A"}""", mutation.bodyJson)
        assertNotNull(mutation.filterJson)
    }

    @Test
    fun updateWith_networkFailure_onlineOnly_rethrows() = runTest {
        // Given
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            updateResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store)
        val data = TestData(1, "A")

        // When / Then
        assertFailsWith<IOException> {
            offline.updateWith(TestData.serializer(), data, MutationStrategy.OnlineOnly) { params["id"] = listOf("eq.1") }
        }
        assertEquals(0, store.pendingMutationCountForTable("assets"))
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun newStore(): OfflineStore {
        val objectDao = FakeObjectDao()
        val storageDao = FakeStorageDao()
        val mutationDao = FakeMutationDao()
        return newTestOfflineStore(
            objectDao = objectDao,
            storageDao = storageDao,
            mutationDao = mutationDao,
        )
    }

    private fun fakeResult(postgrest: FakePostgrest, data: String): PostgrestResult {
        val headers = HeadersBuilder().build()
        return PostgrestResult(data, headers, postgrest)
    }

    // ---------------------------------------------------------------------
    // Logging assertion tests
    // ---------------------------------------------------------------------

    @Test
    fun insertJson_success_logsDebugWithRequestAndResponseJson() = runTest {
        // Given
        val capturing = CapturingLogger()
        val store = newStore()
        val postgrest = FakePostgrest()
        val responseData = """[{"id":1,"created":true}]"""
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            insertResult = Result.success(fakeResult(postgrest, responseData))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store, sdkLogger = capturing.asSdkLogger())

        val body = buildJsonArray { add(buildJsonObject { put("id", JsonPrimitive(1)) }) }

        // When
        offline.insertJson(body) {}

        // Then
        val debugEntries = capturing.entriesAt(OfflineLogLevel.DEBUG)
        assertEquals(2, debugEntries.size)
        assertEquals(true, debugEntries[0].message.contains("INSERT assets request body:"))
        assertEquals(true, debugEntries[0].message.contains(body.toString()))
        assertEquals(true, debugEntries[1].message.contains("INSERT assets response:"))
        assertEquals(true, debugEntries[1].message.contains(responseData))
    }

    @Test
    fun insertJson_queuedOffline_logsInfoQueued() = runTest {
        // Given
        val capturing = CapturingLogger()
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            insertResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store, sdkLogger = capturing.asSdkLogger())

        val body = buildJsonArray { add(buildJsonObject { put("id", JsonPrimitive(1)) }) }

        // When
        offline.insertJson(body, MutationStrategy.QueueIfOffline) {}

        // Then
        val infos = capturing.entriesAt(OfflineLogLevel.INFO)
        assertEquals(1, infos.size)
        assertEquals(true, infos[0].message.contains("INSERT assets queued offline"))
    }

    @Test
    fun upsertJson_success_logsDebugWithJson() = runTest {
        // Given
        val capturing = CapturingLogger()
        val store = newStore()
        val postgrest = FakePostgrest()
        val responseData = """[{"id":1}]"""
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            upsertResult = Result.success(fakeResult(postgrest, responseData))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store, sdkLogger = capturing.asSdkLogger())

        val body = buildJsonArray { add(buildJsonObject { put("id", JsonPrimitive(1)) }) }

        // When
        offline.upsertJson(body) {}

        // Then
        val debugEntries = capturing.entriesAt(OfflineLogLevel.DEBUG)
        assertEquals(2, debugEntries.size)
        assertEquals(true, debugEntries[0].message.contains("UPSERT assets request body:"))
        assertEquals(true, debugEntries[1].message.contains("UPSERT assets response:"))
        assertEquals(true, debugEntries[1].message.contains(responseData))
    }

    @Test
    fun upsertJson_queuedOffline_logsInfoQueued() = runTest {
        // Given
        val capturing = CapturingLogger()
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            upsertResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store, sdkLogger = capturing.asSdkLogger())

        val body = buildJsonArray { add(buildJsonObject { put("id", JsonPrimitive(1)) }) }

        // When
        offline.upsertJson(body, MutationStrategy.QueueIfOffline) {}

        // Then
        val infos = capturing.entriesAt(OfflineLogLevel.INFO)
        assertEquals(1, infos.size)
        assertEquals(true, infos[0].message.contains("UPSERT assets queued offline"))
    }

    @Test
    fun updateJson_success_logsDebugWithJson() = runTest {
        // Given
        val capturing = CapturingLogger()
        val store = newStore()
        val postgrest = FakePostgrest()
        val responseData = """[{"id":1,"updated":true}]"""
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            updateResult = Result.success(fakeResult(postgrest, responseData))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store, sdkLogger = capturing.asSdkLogger())

        val body = buildJsonObject { put("name", JsonPrimitive("updated")) }

        // When
        offline.updateJson(body) {}

        // Then
        val debugEntries = capturing.entriesAt(OfflineLogLevel.DEBUG)
        assertEquals(2, debugEntries.size)
        assertEquals(true, debugEntries[0].message.contains("UPDATE assets request body:"))
        assertEquals(true, debugEntries[0].message.contains(body.toString()))
        assertEquals(true, debugEntries[1].message.contains("UPDATE assets response:"))
        assertEquals(true, debugEntries[1].message.contains(responseData))
    }

    @Test
    fun updateJson_queuedOffline_logsInfoQueued() = runTest {
        // Given
        val capturing = CapturingLogger()
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            updateResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store, sdkLogger = capturing.asSdkLogger())

        val body = buildJsonObject { put("name", JsonPrimitive("updated")) }

        // When
        offline.updateJson(body, MutationStrategy.QueueIfOffline) {}

        // Then
        val infos = capturing.entriesAt(OfflineLogLevel.INFO)
        assertEquals(1, infos.size)
        assertEquals(true, infos[0].message.contains("UPDATE assets queued offline"))
    }

    @Test
    fun delete_success_logsDebugWithResponseJson() = runTest {
        // Given
        val capturing = CapturingLogger()
        val store = newStore()
        val postgrest = FakePostgrest()
        val responseData = """[]"""
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            deleteResult = Result.success(fakeResult(postgrest, responseData))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store, sdkLogger = capturing.asSdkLogger())

        // When
        offline.delete {}

        // Then
        val debugEntries = capturing.entriesAt(OfflineLogLevel.DEBUG)
        assertEquals(2, debugEntries.size)
        assertEquals(true, debugEntries[0].message.contains("DELETE assets attempting network"))
        assertEquals(true, debugEntries[1].message.contains("DELETE assets response:"))
    }

    @Test
    fun delete_queuedOffline_logsInfoQueued() = runTest {
        // Given
        val capturing = CapturingLogger()
        val store = newStore()
        val postgrest = FakePostgrest()
        val delegate = FakePostgrestQueryBuilderWrapper("assets", postgrest).apply {
            deleteResult = Result.failure(IOException("network"))
        }
        val offline = OfflinePostgrestQueryBuilderImpl(delegate, store, sdkLogger = capturing.asSdkLogger())

        // When
        offline.delete(MutationStrategy.QueueIfOffline) {}

        // Then
        val infos = capturing.entriesAt(OfflineLogLevel.INFO)
        assertEquals(1, infos.size)
        assertEquals(true, infos[0].message.contains("DELETE assets queued offline"))
    }
}
