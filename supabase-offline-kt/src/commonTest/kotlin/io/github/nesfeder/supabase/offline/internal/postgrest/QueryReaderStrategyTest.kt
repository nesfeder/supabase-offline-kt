// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRemoteTable
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.getPayload
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.postgrestResultOf
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Read-strategy semantics of [QueryReader], one strategy and one failure class at a time: which
 * strategies fall back to cache on a connectivity failure, which honour a cache duration, which
 * never touch the network, and what each one logs.
 *
 * Only the network SELECT is scripted (a [FakeRemoteTable]); the cache is a real `TestStore` over
 * the in-memory DAO fakes.
 */
class QueryReaderStrategyTest {

    private val postgrest: Postgrest = newTestSupabaseClient().postgrest

    private var currentTime: Long = 1_000_000L
    private val mutationDao = FakeMutationDao()
    private val store =
        newTestStore(
            queryCacheDao = FakeQueryCacheDao(),
            mutationDao = mutationDao,
            nowMillis = { currentTime },
        )

    /** What the scripted network SELECT answers; defaults to a failure so a test opts in. */
    private var networkAnswer: suspend () -> PostgrestResult = {
        error("network SELECT not scripted")
    }

    /** Completed by a test to release a network SELECT it wants held in flight. */
    private var networkGate: CompletableDeferred<Unit>? = null

    /** Number of network SELECTs issued. */
    private var networkCalls = 0

    private val remoteTable =
        FakeRemoteTable(
            table = TABLE,
            postgrest = postgrest,
            onSelect = {
                networkCalls++
                networkGate?.await()
                networkAnswer()
            },
        )

    private fun resolver(
        scope: CoroutineScope,
        logger: RecordingLogSink? = null,
    ): QueryReader =
        QueryReader(
            delegate = remoteTable,
            queryCache = store.queryCache,
            queryOverlay = store.queryOverlay,
            mutationQueue = store.mutationQueue,
            freshness = store.freshness,
            engineLogger = logger?.asEngineLogger() ?: EngineLogger(null, EngineLogLevel.INFO),
            nowMillis = { currentTime },
            liveQueries = LiveQueries(scope),
        )

    /** The cache key the resolver uses for an unfiltered `SELECT *` on [TABLE]. */
    private fun cacheKey(): String =
        cacheKeyOf(
            table = TABLE,
            columns = Columns.ALL,
            builderFactory = {
                SelectRequestBuilder(remoteTable.schema, postgrest.config.propertyConversionMethod)
            },
            request = {},
        )

    private fun answerWith(payload: String) {
        networkAnswer = { postgrestResultOf(payload, postgrest) }
    }

    private fun failNetwork() {
        networkAnswer = { throw HttpRequestTimeoutException("url", null) }
    }

    // ─────────────────────────── One-shot reads ───────────────────────────

    @Test
    fun networkFirst_successfulNetwork_returnsNetworkDataAndCaches() = runTest {
        // Given
        answerWith(NETWORK_DATA)

        // When
        val result = resolver(backgroundScope).select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

        // Then
        assertEquals(NETWORK_DATA, result.data)
        assertEquals(NETWORK_DATA, store.queryCache.getPayload(TABLE, cacheKey()))
    }

    @Test
    fun networkFirst_networkFailure_fallsBackToCache() = runTest {
        // Given
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)
        failNetwork()

        // When
        val result = resolver(backgroundScope).select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

        // Then
        assertEquals(CACHED_DATA, result.data)
    }

    @Test
    fun networkFirst_success_overlaysPendingWrites_andRetiresSyncedOnes() = runTest {
        // Given — one pending INSERT and one already synced
        answerWith(NETWORK_DATA)
        store.mutationQueue.enqueueInsert(
            table = TABLE,
            body = rows("""[{"id":2,"name":"pending"}]"""),
        )
        store.mutationQueue.enqueueInsert(
            table = TABLE,
            body = rows("""[{"id":3,"name":"synced"}]"""),
        )
        store.mutationQueue.recordReplayed(id = mutationDao.inserted.last().id, table = TABLE)

        // When
        val result = resolver(backgroundScope).select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

        // Then — the pending row is overlaid, the synced one is left to the server, and retired
        assertEquals("""[{"id":1,"name":"network"},{"id":2,"name":"pending"}]""", result.data)
        assertEquals(
            listOf("""[{"id":2,"name":"pending"}]"""),
            mutationDao.inserted.map { it.bodyJson },
        )
    }

    @Test
    fun networkFirst_networkFailure_cacheFallback_overlaysSyncedWritesToo() = runTest {
        // Given — a cache older than a write that has since synced
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)
        store.mutationQueue.enqueueInsert(
            table = TABLE,
            body = rows("""[{"id":3,"name":"synced"}]"""),
        )
        store.mutationQueue.recordReplayed(id = mutationDao.inserted.single().id, table = TABLE)
        failNetwork()

        // When
        val result = resolver(backgroundScope).select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

        // Then — the cached snapshot predates the sync, so the synced write is re-applied
        assertEquals("""[{"id":1,"name":"cached"},{"id":3,"name":"synced"}]""", result.data)
    }

    @Test
    fun networkFirst_networkFailure_expiredCache_throws() = runTest {
        // Given — a cached row two hours old, read with a one-hour cache duration
        currentTime = 1_000L
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)
        currentTime = 1_000L + 2.hours.inWholeMilliseconds
        failNetwork()
        val resolver = resolver(backgroundScope)

        // When / Then
        assertFailsWith<HttpRequestTimeoutException> {
            resolver.select(Columns.ALL, ReadStrategy.NetworkFirst(maxAge = 1.hours)) {}
        }
    }

    @Test
    fun networkFirst_nonNetworkFailure_rethrowsWithoutUsingCache() = runTest {
        // Given — cached data, and a failure that is not a connectivity failure
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)
        networkAnswer = { throw IllegalStateException("server rejected the request") }
        val resolver = resolver(backgroundScope)

        // When / Then — only a connectivity failure may fall back to the cache
        assertFailsWith<IllegalStateException> {
            resolver.select(Columns.ALL, ReadStrategy.NetworkFirst()) {}
        }
    }

    @Test
    fun networkOnly_successfulNetwork_returnsNetworkData() = runTest {
        // Given
        answerWith(NETWORK_DATA)

        // When
        val result = resolver(backgroundScope).select(Columns.ALL, ReadStrategy.NetworkOnly) {}

        // Then
        assertEquals(NETWORK_DATA, result.data)
    }

    @Test
    fun networkOnly_networkFailure_throws() = runTest {
        // Given
        failNetwork()
        val resolver = resolver(backgroundScope)

        // When / Then
        assertFailsWith<HttpRequestTimeoutException> {
            resolver.select(Columns.ALL, ReadStrategy.NetworkOnly) {}
        }
    }

    @Test
    fun networkOnly_networkFailure_evenWithCachedData_throws() = runTest {
        // Given
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)
        failNetwork()
        val resolver = resolver(backgroundScope)

        // When / Then
        assertFailsWith<HttpRequestTimeoutException> {
            resolver.select(Columns.ALL, ReadStrategy.NetworkOnly) {}
        }
    }

    @Test
    fun cacheFirst_foundInCache_returnsCacheImmediately() = runTest {
        // Given
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)

        // When
        val result = resolver(backgroundScope).select(Columns.ALL, ReadStrategy.CacheFirst()) {}

        // Then
        assertEquals(CACHED_DATA, result.data)
        assertEquals(0, networkCalls)
    }

    @Test
    fun cacheFirst_expiredInCache_fetchesFromNetwork() = runTest {
        // Given
        currentTime = 1_000L
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)
        currentTime = 1_000L + 2.hours.inWholeMilliseconds
        answerWith(NETWORK_DATA)

        // When
        val result =
            resolver(backgroundScope).select(
                Columns.ALL,
                ReadStrategy.CacheFirst(maxAge = 1.hours),
            ) {}

        // Then
        assertEquals(NETWORK_DATA, result.data)
        assertEquals(1, networkCalls)
    }

    @Test
    fun cacheFirst_expiredInCache_networkFailure_throws() = runTest {
        // Given — an expired cache and no network
        currentTime = 1_000L
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)
        currentTime = 1_000L + 2.hours.inWholeMilliseconds
        failNetwork()
        val resolver = resolver(backgroundScope)

        // When / Then — no stale fallback
        assertFailsWith<HttpRequestTimeoutException> {
            resolver.select(Columns.ALL, ReadStrategy.CacheFirst(maxAge = 1.hours)) {}
        }
    }

    @Test
    fun cacheFirst_noCache_networkFailure_throws() = runTest {
        // Given — nothing cached and no network
        failNetwork()
        val resolver = resolver(backgroundScope)

        // When / Then
        assertFailsWith<HttpRequestTimeoutException> {
            resolver.select(Columns.ALL, ReadStrategy.CacheFirst()) {}
        }
        assertEquals(1, networkCalls)
    }

    @Test
    fun cacheOnly_foundInCache_returnsCache() = runTest {
        // Given
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)

        // When
        val result = resolver(backgroundScope).select(Columns.ALL, ReadStrategy.CacheOnly()) {}

        // Then
        assertEquals(CACHED_DATA, result.data)
    }

    @Test
    fun cacheOnly_expiredInCache_throws() = runTest {
        // Given
        currentTime = 1_000L
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)
        currentTime = 1_000L + 2.hours.inWholeMilliseconds
        val resolver = resolver(backgroundScope)

        // When / Then
        assertFailsWith<CacheMissException> {
            resolver.select(Columns.ALL, ReadStrategy.CacheOnly(maxAge = 1.hours)) {}
        }
    }

    // ─────────────────────────── Reactive reads ───────────────────────────

    @Test
    fun selectAsFlow_networkFirstThenCache_emitsNetworkData() = runTest {
        // Given
        answerWith(NETWORK_DATA)

        // When
        val first =
            resolver(backgroundScope)
                .selectAsFlow(Columns.ALL, FlowReadStrategy.NetworkFirstThenCache) {}
                .first()

        // Then
        assertEquals(NETWORK_DATA, first.result?.data)
    }

    @Test
    fun selectAsFlow_networkFirstThenCache_networkFailure_fallsBackToCache() = runTest {
        // Given — cached data exists, and the network bootstrap fails
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)
        failNetwork()
        val flow =
            resolver(backgroundScope).selectAsFlow(
                Columns.ALL,
                FlowReadStrategy.NetworkFirstThenCache,
            ) {}

        // When
        val first = flow.first()

        // Then
        assertEquals(CACHED_DATA, first.result?.data)
    }

    @Test
    fun selectAsFlow_cacheFirstThenNetwork_emitsCacheWhileNetworkSuspended_thenNetwork() = runTest {
        // Given — the network SELECT is held until after the cache has been observed
        val gate = CompletableDeferred<Unit>()
        networkGate = gate
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)
        answerWith(NETWORK_DATA)
        val emissions = mutableListOf<String>()
        val firstEmission = CompletableDeferred<String>()
        val collector = async {
            resolver(backgroundScope)
                .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                .mapNotNull { read -> read.result?.data }
                .distinctUntilChanged()
                .take(2)
                .collect { payload ->
                    emissions += payload
                    if (emissions.size == 1) firstEmission.complete(payload)
                }
        }

        // When — the cache must arrive without waiting for the network round trip
        val firstPayload = firstEmission.await()
        val emittedBeforeNetwork = emissions.toList()
        gate.complete(Unit)
        collector.await()

        // Then
        assertEquals(CACHED_DATA, firstPayload)
        assertEquals(listOf(CACHED_DATA), emittedBeforeNetwork)
        assertEquals(listOf(CACHED_DATA, NETWORK_DATA), emissions)
    }

    @Test
    fun selectAsFlow_cacheOnly_emitsCachedData() = runTest {
        // Given
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)

        // When
        val first =
            resolver(backgroundScope)
                .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheOnly) {}
                .first()

        // Then
        assertEquals(CACHED_DATA, first.result?.data)
    }

    @Test
    fun selectAsFlow_cacheOnly_neverTriggersNetwork() = runTest {
        // Given — a network SELECT that would fail the test if issued
        store.queryCache.cacheResponse(TABLE, cacheKey(), CACHED_DATA)
        networkAnswer = { throw AssertionError("Network should not be called") }

        // When
        val first =
            resolver(backgroundScope)
                .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheOnly) {}
                .first()

        // Then
        assertEquals(CACHED_DATA, first.result?.data)
        assertEquals(0, networkCalls)
    }

    // ─────────────────────────── Logging ───────────────────────────

    @Test
    fun networkFirst_success_logsDebugWithStrategy() = runTest {
        // Given
        val recorder = RecordingLogSink()
        answerWith("""[{"id":1}]""")

        // When
        resolver(backgroundScope, recorder).select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

        // Then
        val debugEntries = recorder.entriesAt(EngineLogLevel.DEBUG)
        assertTrue(debugEntries.any { it.message.contains("SELECT items via NetworkFirst") })
    }

    @Test
    fun networkFirst_success_logsDebugWithResponseJson() = runTest {
        // Given
        val recorder = RecordingLogSink()
        val networkData = """[{"id":1,"name":"test"}]"""
        answerWith(networkData)

        // When
        resolver(backgroundScope, recorder).select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

        // Then — response bodies are logged at DEBUG only
        val debugEntries = recorder.entriesAt(EngineLogLevel.DEBUG)
        assertTrue(
            debugEntries.any {
                it.message.contains("SELECT items network response:") &&
                    it.message.contains(networkData)
            }
        )
        assertTrue(
            recorder.entries
                .filter { it.level != EngineLogLevel.DEBUG }
                .none { it.message.contains(networkData) },
            "a response body must never be logged above DEBUG",
        )
    }

    @Test
    fun networkFirst_networkFailure_cacheHit_logsInfo() = runTest {
        // Given
        val recorder = RecordingLogSink()
        store.queryCache.cacheResponse(TABLE, cacheKey(), """[{"id":1}]""")
        failNetwork()

        // When
        resolver(backgroundScope, recorder).select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

        // Then — an expected offline condition, so nothing above INFO
        assertEquals(1, recorder.entriesAt(EngineLogLevel.INFO).count { entry ->
            entry.message.contains("SELECT items network failed, falling back to cache")
        })
        assertEquals(emptyList(), recorder.entries.filter { it.level > EngineLogLevel.INFO })
    }

    @Test
    fun networkFirst_networkFailure_noCache_logsInfo() = runTest {
        // Given
        val recorder = RecordingLogSink()
        failNetwork()
        val resolver = resolver(backgroundScope, recorder)

        // When
        assertFailsWith<HttpRequestTimeoutException> {
            resolver.select(Columns.ALL, ReadStrategy.NetworkFirst()) {}
        }

        // Then — an expected offline condition, so nothing above INFO
        assertEquals(1, recorder.entriesAt(EngineLogLevel.INFO).count { entry ->
            entry.message.contains("SELECT items network failed and no cache available")
        })
        assertEquals(emptyList(), recorder.entries.filter { it.level > EngineLogLevel.INFO })
    }

    @Test
    fun cacheFirst_cacheHit_logsInfo() = runTest {
        // Given
        val recorder = RecordingLogSink()
        store.queryCache.cacheResponse(TABLE, cacheKey(), """[{"id":1}]""")

        // When
        resolver(backgroundScope, recorder).select(Columns.ALL, ReadStrategy.CacheFirst()) {}

        // Then
        val infos = recorder.entriesAt(EngineLogLevel.INFO)
        assertEquals(1, infos.size)
        assertTrue(infos[0].message.contains("SELECT items served from cache"))
    }

    @Test
    fun cacheOnly_cacheHit_logsInfo() = runTest {
        // Given
        val recorder = RecordingLogSink()
        store.queryCache.cacheResponse(TABLE, cacheKey(), """[{"id":1}]""")

        // When
        resolver(backgroundScope, recorder).select(Columns.ALL, ReadStrategy.CacheOnly()) {}

        // Then
        val infos = recorder.entriesAt(EngineLogLevel.INFO)
        assertEquals(1, infos.size)
        assertTrue(infos[0].message.contains("SELECT items served from cache"))
    }

    @Test
    fun cacheOnly_noCache_logsInfo() = runTest {
        // Given
        val recorder = RecordingLogSink()
        val resolver = resolver(backgroundScope, recorder)

        // When
        assertFailsWith<CacheMissException> {
            resolver.select(Columns.ALL, ReadStrategy.CacheOnly()) {}
        }

        // Then — the outcome the read asked for, so nothing above INFO
        assertEquals(1, recorder.entriesAt(EngineLogLevel.INFO).count { entry ->
            entry.message.contains("SELECT items no cached data available")
        })
        assertEquals(emptyList(), recorder.entries.filter { it.level > EngineLogLevel.INFO })
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "items"
        const val CACHED_DATA = """[{"id":1,"name":"cached"}]"""
        const val NETWORK_DATA = """[{"id":1,"name":"network"}]"""
    }
}
