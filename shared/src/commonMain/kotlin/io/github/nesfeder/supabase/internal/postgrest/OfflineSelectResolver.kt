@file:OptIn(SupabaseExperimental::class, ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.internal.postgrest

import io.github.nesfeder.supabase.FlowReadStrategy
import io.github.nesfeder.supabase.OfflineLogLevel
import io.github.nesfeder.supabase.ReadStrategy
import io.github.nesfeder.supabase.SdkLogger
import io.github.nesfeder.supabase.internal.db.ObjectRow
import io.github.nesfeder.supabase.internal.db.OfflineStore
import io.github.nesfeder.supabase.internal.postgrest.parser.SelectQueryMetadataParser
import io.github.nesfeder.supabase.internal.util.isNetworkOrConnectivityFailure
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * Resolves [ReadStrategy] during select operations by deciding whether to
 * fetch from network, cache, or both, while respecting [ReadStrategy] options.
 *
 * Supports both suspend (one-shot) and Flow (reactive) APIs.
 */
internal class OfflineSelectResolver(
    private val delegate: PostgrestQueryBuilderWrapper,
    private val store: OfflineStore,
    private val sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun select(
        columns: Columns,
        readStrategy: ReadStrategy,
        request: SelectRequestBuilder.() -> Unit
    ): PostgrestResult {
        val table = delegate.table
        sdkLogger.debug { "SELECT $table via ${readStrategy::class.simpleName}" }
        return when (readStrategy) {
            is ReadStrategy.NetworkFirst -> {
                try {
                    performNetworkSelect(table, columns, request)
                } catch (exception: Exception) {
                    if (!exception.isNetworkOrConnectivityFailure()) throw exception
                    val cached = performCacheSelect(table, columns, request, readStrategy.cacheDuration)
                    if (cached != null) {
                        sdkLogger.warning { "SELECT $table network failed, falling back to cache" }
                        cached
                    } else {
                        sdkLogger.error { "SELECT $table network failed and no cache available" }
                        throw exception
                    }
                }
            }
            ReadStrategy.NetworkOnly -> {
                performNetworkSelect(table, columns, request)
            }
            is ReadStrategy.CacheFirst -> {
                val cached = performCacheSelect(table, columns, request, readStrategy.cacheDuration)
                if (cached != null) {
                    sdkLogger.info { "SELECT $table served from cache" }
                    cached
                } else {
                    performNetworkSelect(table, columns, request)
                }
            }
            is ReadStrategy.CacheOnly -> {
                val cached = performCacheSelect(table, columns, request, readStrategy.cacheDuration)
                if (cached != null) {
                    sdkLogger.info { "SELECT $table served from cache" }
                    cached
                } else {
                    sdkLogger.error { "SELECT $table no cached data available" }
                    throw NoSuchElementException("No valid cached data found for this query")
                }
            }
        }
    }

    fun selectAsFlow(
        columns: Columns,
        readStrategy: FlowReadStrategy,
        request: SelectRequestBuilder.() -> Unit
    ): Flow<PostgrestResult> {
        val table = delegate.table
        return when (readStrategy) {
            FlowReadStrategy.CacheFirstThenNetwork -> cacheFirstThenNetworkFlow(table, columns, request)
            FlowReadStrategy.CacheOnly -> cacheOnlyFlow(table, columns, request)
            FlowReadStrategy.NetworkFirstThenCache -> networkFirstThenCacheFlow(table, columns, request)
        }.distinctUntilChanged()
    }

    /**
     * Emit only cached values and never hit the network.
     * If no cached value exists, the flow stays alive for future cache updates.
     *
     * Uses the same resolution rules as one-shot cache reads: exact signature match first, then
     * in-memory subset derivation from a broader cached query when applicable.
     *
     * Applies overlay of pending mutations on cached data.
     */
    private fun cacheOnlyFlow(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit
    ): Flow<PostgrestResult> {
        return store.observeObjectRowsForTable(table)
            .mapLatest { rows ->
                resolveCachedPayloadStringForFlow(
                    table = table,
                    columns = columns,
                    request = request,
                    cacheDuration = null,
                    cachedRows = rows,
                )
            }
            .let { flow ->
                with(store) {
                    flow.applyAllMutationsForOfflineFallbackFlow(table)
                }
            }
            .filterNotNull()
            .map { payload ->
                buildResult(payload, delegate.postgrest)
            }
    }

    /**
     * Emit cached values immediately and in parallel trigger a network request.
     * When the network response arrives, the cache is updated and the flow continues
     * emitting future cache changes.
     */
    private fun cacheFirstThenNetworkFlow(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit
    ): Flow<PostgrestResult> = channelFlow {
        launch {
            cacheOnlyFlow(table, columns, request)
                .collect { send(it) }
        }

        // Refresh the cache in parallel. `cacheOnlyFlow` remains responsible
        // for emitting cache values, including updates when the network response arrives.
        launch {
            try {
                performNetworkSelect(
                    table = table,
                    columns = columns,
                    request = request,
                    applyOverlay = false
                )
            } catch (_: Exception) {
                // Ignore network errors; we continue emitting cached data.
            }
        }
    }

    /**
     * Perform a network request first and wait for it to complete.
     * Once the response is stored in the cache, start observing the cache for future changes.
     * If the network request fails, the flow fails.
     */
    private fun networkFirstThenCacheFlow(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit
    ): Flow<PostgrestResult> {
        return cacheOnlyFlow(table, columns, request)
            .onStart {
                // Perform network request first to bootstrap the cache.
                // Any network errors will be thrown and terminate the flow.
                performNetworkSelect(
                    table = table,
                    columns = columns,
                    request = request,
                    applyOverlay = false
                )
            }
    }

    private suspend fun performNetworkSelect(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        applyOverlay: Boolean = true
    ): PostgrestResult {
        val signature = getSignature(table, columns, request)
        val networkResult = delegate.select(columns, request)
        sdkLogger.debug { "SELECT $table network response: ${networkResult.data}" }

        store.addPayload(
            table = table,
            signature = signature,
            payload = networkResult.data
        )

        val finalJson = if (applyOverlay) {
            // Overlay *pending* mutations on fresh network result.
            store.applyPendingMutations(
                table = table,
                serverJson = networkResult.data
            )
        } else {
            networkResult.data
        }

        // Clear already-synced mutations for this table.
        store.clearSyncedMutationsForTable(table)

        return if (finalJson == networkResult.data) {
            networkResult
        } else {
            buildResult(finalJson, delegate.postgrest)
        }
    }

    private suspend fun performCacheSelect(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        cacheDuration: Duration?,
        applyOverlay: Boolean = true
    ): PostgrestResult? {
        val signature = getSignature(table, columns, request)
        val cached = store.getPayloadWithTimestamp(table, signature)
            ?: return performSubsetCacheSelect(table, columns, request, cacheDuration, applyOverlay)

        val (payload, updatedAt) = cached

        if (cacheDuration != null) {
            val now = nowMillis()
            val age = now - updatedAt
            if (age > cacheDuration.inWholeMilliseconds) {
                return null // Cache expired
            }
        }

        val finalJson = if (applyOverlay) {
            store.applyAllMutationsForOfflineFallback(
                table = table,
                cachedJson = payload
            )
        } else {
            payload
        }
        return buildResult(finalJson, delegate.postgrest)
    }

    /**
     * Fallback when the exact-signature cache lookup misses.
     *
     * Scans all cached rows for [table] and attempts to serve the requested query
     * by in-memory filtering of a broader cached result. Only attempted when the
     * requested query avoids pagination/range and uninterpretable params; ORDER BY
     * is applied in memory after filtering when present.
     */
    private suspend fun performSubsetCacheSelect(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        cacheDuration: Duration?,
        applyOverlay: Boolean
    ): PostgrestResult? = withContext(dispatcher) {
        val candidateRows = store.getCandidateRowsForTable(table)
        val derivedPayload = tryDeriveSubsetPayloadString(
            table = table,
            columns = columns,
            request = request,
            cacheDuration = cacheDuration,
            candidateRows = candidateRows,
        ) ?: return@withContext null

        val finalJson = if (applyOverlay) {
            store.applyAllMutationsForOfflineFallback(table = table, cachedJson = derivedPayload)
        } else {
            derivedPayload
        }
        buildResult(finalJson, delegate.postgrest)
    }

    /**
     * When the exact-signature row is absent, attempts in-memory subset derivation from [candidateRows].
     */
    private fun tryDeriveSubsetPayloadString(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        cacheDuration: Duration?,
        candidateRows: List<ObjectRow>,
    ): String? {
        val requestedMeta = SelectQueryMetadataParser.fromRequestBuilder(
            table = table,
            columns = columns,
            propertyConversionMethod = delegate.postgrest.config.propertyConversionMethod,
            request = request,
        )

        if (!requestedMeta.supportsSubsetDerivation) {
            return null
        }

        for (row in candidateRows) {
            if (row.signature == requestedMeta.signature) continue

            if (cacheDuration != null) {
                val age = nowMillis() - row.updatedAt
                if (age > cacheDuration.inWholeMilliseconds) continue
            }

            val candidateMeta = SelectQueryMetadataParser.fromSignature(table, row.signature)
            if (!candidateMeta.canProvideSubsetFor(requestedMeta)) continue

            val derivedPayload = deriveSubsetPayload(
                candidatePayload = row.payload,
                candidateMetadata = candidateMeta,
                requestedMetadata = requestedMeta,
                json = store.json,
            ) ?: continue

            sdkLogger.info { "SELECT $table subset derived from broader cache '${row.signature}'" }
            return derivedPayload
        }

        return null
    }

    /**
     * Resolves raw cached JSON for [request] from an in-memory snapshot of cache rows (flow path).
     */
    private suspend fun resolveCachedPayloadStringForFlow(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        cacheDuration: Duration?,
        cachedRows: List<ObjectRow>,
    ): String? = withContext(dispatcher) {
        val signature = getSignature(table, columns, request)
        val exactRow = cachedRows.firstOrNull { it.signature == signature }
        if (exactRow != null) {
            if (cacheDuration != null) {
                val age = nowMillis() - exactRow.updatedAt
                if (age > cacheDuration.inWholeMilliseconds) return@withContext null
            }
            return@withContext exactRow.payload
        }
        tryDeriveSubsetPayloadString(
            table = table,
            columns = columns,
            request = request,
            cacheDuration = cacheDuration,
            candidateRows = cachedRows,
        )
    }

    private fun getSignature(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit
    ): String {
        return SignatureBuilder.build(
            table = table,
            columns = columns,
            builderFactory = {
                SelectRequestBuilder(delegate.postgrest.config.propertyConversionMethod)
            },
            request = request
        )
    }

    private fun buildResult(data: String, postgrest: Postgrest): PostgrestResult {
        val headers: Headers = HeadersBuilder().build()
        return PostgrestResult(data, headers, postgrest)
    }
}
