@file:OptIn(SupabaseExperimental::class, ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.internal.postgrest

import io.github.nesfeder.supabase.FlowReadStrategy
import io.github.nesfeder.supabase.OfflineLogLevel
import io.github.nesfeder.supabase.ReadStrategy
import io.github.nesfeder.supabase.internal.CapturingLogger
import io.github.nesfeder.supabase.internal.FakePostgrest
import io.github.nesfeder.supabase.internal.db.FakeMutationDao
import io.github.nesfeder.supabase.internal.db.FakeObjectDao
import io.github.nesfeder.supabase.internal.db.FakeStorageDao
import io.github.nesfeder.supabase.internal.db.ObjectDao
import io.github.nesfeder.supabase.internal.db.ObjectRow
import io.github.nesfeder.supabase.internal.db.OfflineStore
import io.github.nesfeder.supabase.internal.db.newTestOfflineStore
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HeadersBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

class OfflineSelectResolverTest {

    private lateinit var postgrest: FakePostgrest
    private lateinit var delegate: FakePostgrestQueryBuilderWrapper
    private lateinit var objectDao: FakeObjectDao
    private lateinit var store: OfflineStore
    private lateinit var resolver: OfflineSelectResolver
    private var currentTime: Long = 1000000L

    @BeforeTest
    fun setup() {
        postgrest = FakePostgrest()
        delegate = FakePostgrestQueryBuilderWrapper(table = "items", postgrest = postgrest)
        objectDao = FakeObjectDao()
        store = newTestOfflineStore(
            objectDao = objectDao,
            storageDao = FakeStorageDao(),
            mutationDao = FakeMutationDao(),
            nowMillis = { currentTime }
        )
        resolver = OfflineSelectResolver(
            delegate = delegate,
            store = store,
            nowMillis = { currentTime }
        )
    }

    private fun buildResult(data: String): PostgrestResult {
        return PostgrestResult(data, HeadersBuilder().build(), postgrest)
    }

    private fun getSignature(): String {
        return SignatureBuilder.build(
            "items",
            Columns.ALL,
            { SelectRequestBuilder(postgrest.config.propertyConversionMethod) },
            {}
        )
    }

    @Test
    fun networkFirst_successfulNetwork_returnsNetworkDataAndCaches() = runTest {
        // Given
        val networkData = """[{"id":1,"name":"network"}]"""
        delegate.selectResult = Result.success(buildResult(networkData))

        // When
        val result = resolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.NetworkFirst()) {}

        // Then
        assertEquals(networkData, result.data)
        assertEquals(networkData, store.getPayload("items", getSignature()))
    }

    @Test
    fun networkFirst_networkFailure_fallsBackToCache() = runTest {
        // Given
        val cachedData = """[{"id":1,"name":"cached"}]"""
        store.addPayload("items", getSignature(), cachedData)
        delegate.selectResult = Result.failure(HttpRequestTimeoutException("url", null))

        // When
        val result = resolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.NetworkFirst()) {}

        // Then
        assertEquals(cachedData, result.data)
    }

    @Test
    fun networkFirst_networkFailure_expiredCache_throws() = runTest {
        // Given
        val cachedData = """[{"id":1,"name":"cached"}]"""
        currentTime = 1000L
        store.addPayload("items", getSignature(), cachedData)
        currentTime = 1000L + 2.hours.inWholeMilliseconds
        delegate.selectResult = Result.failure(HttpRequestTimeoutException("url", null))

        // When / Then
        assertFailsWith<HttpRequestTimeoutException> {
            resolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.NetworkFirst(cacheDuration = 1.hours)) {}
        }
    }

    @Test
    fun networkOnly_successfulNetwork_returnsNetworkData() = runTest {
        // Given
        val networkData = """[{"id":1,"name":"network"}]"""
        delegate.selectResult = Result.success(buildResult(networkData))

        // When
        val result = resolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.NetworkOnly) {}

        // Then
        assertEquals(networkData, result.data)
    }

    @Test
    fun networkOnly_networkFailure_throws() = runTest {
        // Given
        delegate.selectResult = Result.failure(HttpRequestTimeoutException("url", null))

        // When / Then
        assertFailsWith<HttpRequestTimeoutException> {
            resolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.NetworkOnly) {}
        }
    }

    @Test
    fun networkOnly_networkFailure_evenWithCachedData_throws() = runTest {
        // Given
        val cachedData = """[{"id":1,"name":"cached"}]"""
        store.addPayload("items", getSignature(), cachedData)
        delegate.selectResult = Result.failure(HttpRequestTimeoutException("url", null))

        // When / Then
        assertFailsWith<HttpRequestTimeoutException> {
            resolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.NetworkOnly) {}
        }
    }

    @Test
    fun cacheFirst_foundInCache_returnsCacheImmediately() = runTest {
        // Given
        val cachedData = """[{"id":1,"name":"cached"}]"""
        store.addPayload("items", getSignature(), cachedData)

        // When
        val result = resolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.CacheFirst()) {}

        // Then
        assertEquals(cachedData, result.data)
        assertEquals(false, delegate.selectRequestInvoked)
    }

    @Test
    fun cacheFirst_expiredInCache_fetchesFromNetwork() = runTest {
        // Given
        val cachedData = """[{"id":1,"name":"cached"}]"""
        val networkData = """[{"id":1,"name":"network"}]"""
        currentTime = 1000L
        store.addPayload("items", getSignature(), cachedData)
        currentTime = 1000L + 2.hours.inWholeMilliseconds
        delegate.selectResult = Result.success(buildResult(networkData))

        // When
        val result = resolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.CacheFirst(cacheDuration = 1.hours)) {}

        // Then
        assertEquals(networkData, result.data)
        assertEquals(true, delegate.selectRequestInvoked)
    }

    @Test
    fun cacheOnly_foundInCache_returnsCache() = runTest {
        // Given
        val cachedData = """[{"id":1,"name":"cached"}]"""
        store.addPayload("items", getSignature(), cachedData)

        // When
        val result = resolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.CacheOnly()) {}

        // Then
        assertEquals(cachedData, result.data)
    }

    @Test
    fun cacheOnly_expiredInCache_throws() = runTest {
        // Given
        val cachedData = """[{"id":1,"name":"cached"}]"""
        currentTime = 1000L
        store.addPayload("items", getSignature(), cachedData)
        currentTime = 1000L + 2.hours.inWholeMilliseconds

        // When / Then
        assertFailsWith<NoSuchElementException> {
            resolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.CacheOnly(cacheDuration = 1.hours)) {}
        }
    }

    // Flow-based Tests

    @Test
    fun selectAsFlow_networkFirstThenCache_emitsNetworkData() = runTest {
        // Given
        val networkData = """[{"id":1,"name":"network"}]"""
        delegate.selectResult = Result.success(buildResult(networkData))

        // When
        val flow = resolver.selectAsFlow(
            columns = Columns.ALL,
            readStrategy = FlowReadStrategy.NetworkFirstThenCache,
            request = {}
        )
        val firstResult = flow.first()

        // Then
        assertEquals(networkData, firstResult.data)
    }

    @Test
    fun selectAsFlow_networkFirstThenCache_networkFailure_flowFails() = runTest {
        // Given
        val cachedData = """[{"id":1,"name":"cached"}]"""
        store.addPayload("items", getSignature(), cachedData)
        delegate.selectResult = Result.failure(HttpRequestTimeoutException("url", null))

        // When
        val flow = resolver.selectAsFlow(
            columns = Columns.ALL,
            readStrategy = FlowReadStrategy.NetworkFirstThenCache,
            request = {}
        )

        // Then
        assertFailsWith<HttpRequestTimeoutException> {
            flow.first()
        }
    }

    @Test
    fun selectAsFlow_cacheFirstThenNetwork_emitsCacheWhileNetworkSuspended_thenNetwork() = runTest {
        // Given — block the network select until after we observe a cache emission.
        val networkGate = CompletableDeferred<Unit>()
        val cachedData = """[{"id":1,"name":"cached"}]"""
        val networkData = """[{"id":2,"name":"network"}]"""
        store.addPayload("items", getSignature(), cachedData)
        delegate.selectResult = Result.success(buildResult(networkData))
        delegate.selectSuspensionPoint = { networkGate.await() }

        val flow = resolver.selectAsFlow(
            columns = Columns.ALL,
            readStrategy = FlowReadStrategy.CacheFirstThenNetwork,
            request = {}
        )

        val emissions = mutableListOf<String>()
        val firstEmission = CompletableDeferred<String>()
        val secondEmission = CompletableDeferred<String>()
        launch {
            flow.take(2).collect {
                emissions += it.data
                if (emissions.size == 1) firstEmission.complete(it.data)
                if (emissions.size == 2) secondEmission.complete(it.data)
            }
        }

        // When / Then — cache must arrive without waiting for the network round-trip.
        assertEquals(cachedData, firstEmission.await())
        assertEquals(listOf(cachedData), emissions)

        networkGate.complete(Unit)
        assertEquals(networkData, secondEmission.await())
        assertEquals(listOf(cachedData, networkData), emissions)
    }

    @Test
    fun selectAsFlow_cacheOnly_emitsCachedData() = runTest {
        // Given
        val cachedData = """[{"id":1,"name":"cached"}]"""
        store.addPayload("items", getSignature(), cachedData)

        // When
        val flow = resolver.selectAsFlow(
            columns = Columns.ALL,
            readStrategy = FlowReadStrategy.CacheOnly,
            request = {}
        )
        val firstResult = flow.first()

        // Then
        assertEquals(cachedData, firstResult.data)
    }

    @Test
    fun selectAsFlow_cacheOnly_neverTriggersNetwork() = runTest {
        // Given
        val cachedData = """[{"id":1,"name":"cached"}]"""
        store.addPayload("items", getSignature(), cachedData)
        // Set delegate to fail if network is called
        delegate.selectResult = Result.failure(AssertionError("Network should not be called"))

        // When
        val flow = resolver.selectAsFlow(
            columns = Columns.ALL,
            readStrategy = FlowReadStrategy.CacheOnly,
            request = {}
        )
        val firstResult = flow.first()

        // Then
        assertEquals(cachedData, firstResult.data)
    }

    @Test
    fun selectAsFlow_cacheOnly_noInitialRow_emitsWhenBroaderCacheAppears() = runTest {
        // Given — no cache row yet; broader payload will be written after subscription
        val broadPayload =
            """[{"id":"1234","account_id":"abcd123"},{"id":"9999","account_id":"abcd123"}]"""
        val deferred = async {
            resolver.selectAsFlow(
                columns = Columns.ALL,
                readStrategy = FlowReadStrategy.CacheOnly,
            ) {
                params["account_id"] = listOf("eq.abcd123")
                params["id"] = listOf("eq.1234")
            }.first()
        }

        // When — table cache updates with a broader SELECT row, then the flow resolves subset
        advanceUntilIdle()
        store.addPayload("items", broadSignature(), broadPayload)
        advanceUntilIdle()

        // Then
        assertEquals("""[{"id":"1234","account_id":"abcd123"}]""", deferred.await().data)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Logging assertion tests
    // ─────────────────────────────────────────────────────────────────────

    private fun resolverWithLogger(logger: CapturingLogger): OfflineSelectResolver =
        OfflineSelectResolver(
            delegate = delegate,
            store = store,
            sdkLogger = logger.asSdkLogger(),
            nowMillis = { currentTime }
        )

    @Test
    fun networkFirst_success_logsDebugWithStrategy() = runTest {
        // Given
        val capturing = CapturingLogger()
        val logResolver = resolverWithLogger(capturing)
        delegate.selectResult = Result.success(buildResult("""[{"id":1}]"""))

        // When
        logResolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.NetworkFirst()) {}

        // Then
        val debugEntries = capturing.entriesAt(OfflineLogLevel.DEBUG)
        assertEquals(true, debugEntries.any { it.message.contains("SELECT items via NetworkFirst") })
    }

    @Test
    fun networkFirst_success_logsDebugWithResponseJson() = runTest {
        // Given
        val capturing = CapturingLogger()
        val logResolver = resolverWithLogger(capturing)
        val networkData = """[{"id":1,"name":"test"}]"""
        delegate.selectResult = Result.success(buildResult(networkData))

        // When
        logResolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.NetworkFirst()) {}

        // Then
        val debugEntries = capturing.entriesAt(OfflineLogLevel.DEBUG)
        assertEquals(true, debugEntries.any {
            it.message.contains("SELECT items network response:") && it.message.contains(networkData)
        })
    }

    @Test
    fun networkFirst_networkFailure_cacheHit_logsWarning() = runTest {
        // Given
        val capturing = CapturingLogger()
        val logResolver = resolverWithLogger(capturing)
        store.addPayload("items", getSignature(), """[{"id":1}]""")
        delegate.selectResult = Result.failure(HttpRequestTimeoutException("url", null))

        // When
        logResolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.NetworkFirst()) {}

        // Then
        val warnings = capturing.entriesAt(OfflineLogLevel.WARNING)
        assertEquals(1, warnings.size)
        assertEquals(true, warnings[0].message.contains("SELECT items network failed, falling back to cache"))
    }

    @Test
    fun networkFirst_networkFailure_noCache_logsError() = runTest {
        // Given
        val capturing = CapturingLogger()
        val logResolver = resolverWithLogger(capturing)
        delegate.selectResult = Result.failure(HttpRequestTimeoutException("url", null))

        // When / Then
        assertFailsWith<HttpRequestTimeoutException> {
            logResolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.NetworkFirst()) {}
        }

        val errors = capturing.entriesAt(OfflineLogLevel.ERROR)
        assertEquals(1, errors.size)
        assertEquals(true, errors[0].message.contains("SELECT items network failed and no cache available"))
    }

    @Test
    fun cacheFirst_cacheHit_logsInfo() = runTest {
        // Given
        val capturing = CapturingLogger()
        val logResolver = resolverWithLogger(capturing)
        store.addPayload("items", getSignature(), """[{"id":1}]""")

        // When
        logResolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.CacheFirst()) {}

        // Then
        val infos = capturing.entriesAt(OfflineLogLevel.INFO)
        assertEquals(1, infos.size)
        assertEquals(true, infos[0].message.contains("SELECT items served from cache"))
    }

    @Test
    fun cacheOnly_cacheHit_logsInfo() = runTest {
        // Given
        val capturing = CapturingLogger()
        val logResolver = resolverWithLogger(capturing)
        store.addPayload("items", getSignature(), """[{"id":1}]""")

        // When
        logResolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.CacheOnly()) {}

        // Then
        val infos = capturing.entriesAt(OfflineLogLevel.INFO)
        assertEquals(1, infos.size)
        assertEquals(true, infos[0].message.contains("SELECT items served from cache"))
    }

    @Test
    fun cacheOnly_noCache_logsError() = runTest {
        // Given
        val capturing = CapturingLogger()
        val logResolver = resolverWithLogger(capturing)

        // When / Then
        assertFailsWith<NoSuchElementException> {
            logResolver.select(columns = Columns.ALL, readStrategy = ReadStrategy.CacheOnly()) {}
        }

        val errors = capturing.entriesAt(OfflineLogLevel.ERROR)
        assertEquals(1, errors.size)
        assertEquals(true, errors[0].message.contains("SELECT items no cached data available"))
    }

    // ─────────────────────────── Subset cache derivation ───────────────────────────

    private fun broadSignature(): String = SignatureBuilder.build(
        "items",
        Columns.ALL,
        { SelectRequestBuilder(postgrest.config.propertyConversionMethod) },
    ) { params["account_id"] = listOf("eq.abcd123") }

    private fun narrowSignature(): String = SignatureBuilder.build(
        "items",
        Columns.ALL,
        { SelectRequestBuilder(postgrest.config.propertyConversionMethod) },
    ) {
        params["account_id"] = listOf("eq.abcd123")
        params["id"] = listOf("eq.1234")
    }

    private class ExactMissTableScanObjectDao(
        private val exactMissSignature: String,
        private val tableRows: List<ObjectRow>,
    ) : ObjectDao {

        override suspend fun get(table: String, signature: String): ObjectRow? {
            if (signature == exactMissSignature) return null
            return tableRows.firstOrNull { it.table == table && it.signature == signature }
        }

        override fun observe(table: String, signature: String): Flow<ObjectRow?> =
            flowOf(tableRows.firstOrNull { it.table == table && it.signature == signature })

        override suspend fun upsert(row: ObjectRow) = Unit

        override suspend fun delete(table: String, signature: String) = Unit

        override suspend fun getAllForTable(table: String): List<ObjectRow> =
            tableRows.filter { it.table == table }

        override fun observeAllForTable(table: String): Flow<List<ObjectRow>> =
            flowOf(tableRows.filter { it.table == table })

        override suspend fun deleteTable(table: String) = Unit

        override suspend fun deleteOldestPayloadRows(limit: Int) = Unit
    }

    @Test
    fun cacheOnly_narrowQueryDerivedFromBroaderCache_returnsFilteredRows() = runTest {
        // Given – only the broader query is cached
        val broadPayload = """[{"id":"1234","account_id":"abcd123"},{"id":"9999","account_id":"abcd123"}]"""
        store.addPayload("items", broadSignature(), broadPayload)

        // When – request the narrower query
        val result = resolver.select(
            columns = Columns.ALL,
            readStrategy = ReadStrategy.CacheOnly(),
        ) {
            params["account_id"] = listOf("eq.abcd123")
            params["id"] = listOf("eq.1234")
        }

        // Then – only the matching row is returned
        assertEquals("""[{"id":"1234","account_id":"abcd123"}]""", result.data)
    }

    @Test
    fun cacheFirst_narrowQueryDerivedFromBroaderCache_returnsFilteredRowsWithoutNetwork() = runTest {
        // Given
        val broadPayload = """[{"id":"1234","account_id":"abcd123"},{"id":"9999","account_id":"abcd123"}]"""
        store.addPayload("items", broadSignature(), broadPayload)
        // Network should not be called
        delegate.selectResult = Result.failure(AssertionError("Network should not be called"))

        // When
        val result = resolver.select(
            columns = Columns.ALL,
            readStrategy = ReadStrategy.CacheFirst(),
        ) {
            params["account_id"] = listOf("eq.abcd123")
            params["id"] = listOf("eq.1234")
        }

        // Then
        assertEquals("""[{"id":"1234","account_id":"abcd123"}]""", result.data)
        assertEquals(false, delegate.selectRequestInvoked)
    }

    @Test
    fun networkFirst_networkFailure_narrowQueryFallsBackToSubsetCache() = runTest {
        // Given
        val broadPayload = """[{"id":"1234","account_id":"abcd123"},{"id":"9999","account_id":"abcd123"}]"""
        store.addPayload("items", broadSignature(), broadPayload)
        delegate.selectResult = Result.failure(HttpRequestTimeoutException("url", null))

        // When
        val result = resolver.select(
            columns = Columns.ALL,
            readStrategy = ReadStrategy.NetworkFirst(),
        ) {
            params["account_id"] = listOf("eq.abcd123")
            params["id"] = listOf("eq.1234")
        }

        // Then
        assertEquals("""[{"id":"1234","account_id":"abcd123"}]""", result.data)
    }

    @Test
    fun cacheOnly_noBroaderCacheMatch_throws() = runTest {
        // Given – only a cache entry with a DIFFERENT account_id value exists
        val differentAccountSig = SignatureBuilder.build(
            "items",
            Columns.ALL,
            { SelectRequestBuilder(postgrest.config.propertyConversionMethod) },
        ) { params["account_id"] = listOf("eq.xyz789") }
        store.addPayload("items", differentAccountSig, """[{"id":"1234","account_id":"xyz789"}]""")

        // When / Then – cached filter account_id=xyz789 is not in requested conditions
        // (account_id=abcd123 AND id=1234), so no subset derivation is possible
        assertFailsWith<NoSuchElementException> {
            resolver.select(
                columns = Columns.ALL,
                readStrategy = ReadStrategy.CacheOnly(),
            ) {
                params["account_id"] = listOf("eq.abcd123")
                params["id"] = listOf("eq.1234")
            }
        }
    }

    @Test
    fun cacheOnly_broaderCacheExpired_subsetDerivationSkipsExpired() = runTest {
        // Given – seed at t=1000, expire after 1 hour
        currentTime = 1000L
        val broadPayload = """[{"id":"1234","account_id":"abcd123"}]"""
        store.addPayload("items", broadSignature(), broadPayload)
        currentTime = 1000L + 2.hours.inWholeMilliseconds

        // When / Then
        assertFailsWith<NoSuchElementException> {
            resolver.select(
                columns = Columns.ALL,
                readStrategy = ReadStrategy.CacheOnly(cacheDuration = 1.hours),
            ) {
                params["account_id"] = listOf("eq.abcd123")
                params["id"] = listOf("eq.1234")
            }
        }
    }

    @Test
    fun cacheOnly_exactCacheWithinDuration_returnsCachedPayload() = runTest {
        // Given
        currentTime = 1000L
        val cachedPayload = """[{"id":"1234"}]"""
        store.addPayload("items", narrowSignature(), cachedPayload)
        currentTime = 1000L + 30

        // When
        val result = resolver.select(
            columns = Columns.ALL,
            readStrategy = ReadStrategy.CacheOnly(cacheDuration = 1.hours),
        ) {
            params["account_id"] = listOf("eq.abcd123")
            params["id"] = listOf("eq.1234")
        }

        // Then
        assertEquals(cachedPayload, result.data)
    }

    @Test
    fun cacheOnly_broaderCacheWithinDuration_derivesSubset() = runTest {
        // Given
        currentTime = 1000L
        val broadPayload = """[{"id":"1234","account_id":"abcd123"}]"""
        store.addPayload("items", broadSignature(), broadPayload)
        currentTime = 1000L + 30

        // When
        val result = resolver.select(
            columns = Columns.ALL,
            readStrategy = ReadStrategy.CacheOnly(cacheDuration = 1.hours),
        ) {
            params["account_id"] = listOf("eq.abcd123")
            params["id"] = listOf("eq.1234")
        }

        // Then
        assertEquals(broadPayload, result.data)
    }

    @Test
    fun cacheOnly_subsetScanSkipsSameSignatureCandidateAndUsesNextMatch() = runTest {
        // Given — this intentionally simulates an inconsistent DAO snapshot where exact lookup misses,
        // but table scan still includes the exact-signature row. The resolver should skip it and keep scanning.
        val broadPayload = """[{"id":"1234","account_id":"abcd123"}]"""
        val tableScanDao = ExactMissTableScanObjectDao(
            exactMissSignature = narrowSignature(),
            tableRows = listOf(
                ObjectRow(
                    table = "items",
                    signature = narrowSignature(),
                    payload = """[{"id":"wrong"}]""",
                    updatedAt = currentTime,
                ),
                ObjectRow(
                    table = "items",
                    signature = broadSignature(),
                    payload = broadPayload,
                    updatedAt = currentTime,
                ),
            ),
        )
        val scanStore = newTestOfflineStore(
            objectDao = tableScanDao,
            storageDao = FakeStorageDao(),
            mutationDao = FakeMutationDao(),
            nowMillis = { currentTime },
        )
        val scanResolver = OfflineSelectResolver(
            delegate = delegate,
            store = scanStore,
            nowMillis = { currentTime },
        )

        // When
        val result = scanResolver.select(
            columns = Columns.ALL,
            readStrategy = ReadStrategy.CacheOnly(),
        ) {
            params["account_id"] = listOf("eq.abcd123")
            params["id"] = listOf("eq.1234")
        }

        // Then
        assertEquals(broadPayload, result.data)
    }

    @Test
    fun cacheOnly_matchingBroaderCacheWithInvalidJson_skipsCandidateAndThrows() = runTest {
        // Given
        store.addPayload("items", broadSignature(), "not-json")

        // When / Then
        assertFailsWith<NoSuchElementException> {
            resolver.select(
                columns = Columns.ALL,
                readStrategy = ReadStrategy.CacheOnly(),
            ) {
                params["account_id"] = listOf("eq.abcd123")
                params["id"] = listOf("eq.1234")
            }
        }
    }

    @Test
    fun cacheOnly_requestedHasOrder_derivesSortedSubsetFromBroaderCache() = runTest {
        // Given – broader cache without order; requested adds ORDER BY only (exact cache miss)
        val broadPayload =
            """[{"id":"2","name":"B","account_id":"abcd123"},{"id":"1","name":"A","account_id":"abcd123"}]"""
        store.addPayload("items", broadSignature(), broadPayload)

        // When
        val result = resolver.select(
            columns = Columns.ALL,
            readStrategy = ReadStrategy.CacheOnly(),
        ) {
            params["account_id"] = listOf("eq.abcd123")
            params["order"] = listOf("name.asc")
        }

        // Then – rows sorted in memory by name.asc
        assertEquals(
            """[{"id":"1","name":"A","account_id":"abcd123"},{"id":"2","name":"B","account_id":"abcd123"}]""",
            result.data,
        )
    }

    @Test
    fun cacheOnly_requestedHasPagination_subsetDerivationSkipped_throws() = runTest {
        // Given
        val broadPayload = """[{"id":"1234","account_id":"abcd123"}]"""
        store.addPayload("items", broadSignature(), broadPayload)

        // When / Then – paginated request should not be served from subset derivation
        assertFailsWith<NoSuchElementException> {
            resolver.select(
                columns = Columns.ALL,
                readStrategy = ReadStrategy.CacheOnly(),
            ) {
                params["account_id"] = listOf("eq.abcd123")
                params["limit"] = listOf("10")
            }
        }
    }

    @Test
    fun cacheOnly_subsetDerivation_logsInfoMessage() = runTest {
        // Given
        val capturing = CapturingLogger()
        val logResolver = resolverWithLogger(capturing)
        val broadPayload = """[{"id":"1234","account_id":"abcd123"}]"""
        store.addPayload("items", broadSignature(), broadPayload)

        // When
        logResolver.select(
            columns = Columns.ALL,
            readStrategy = ReadStrategy.CacheOnly(),
        ) {
            params["account_id"] = listOf("eq.abcd123")
            params["id"] = listOf("eq.1234")
        }

        // Then
        val infos = capturing.entriesAt(OfflineLogLevel.INFO)
        assertTrue(infos.any { it.message.contains("subset derived from broader cache") })
    }
}
