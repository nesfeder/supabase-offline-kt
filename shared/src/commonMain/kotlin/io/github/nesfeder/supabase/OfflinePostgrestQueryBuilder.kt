@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase

import io.github.nesfeder.supabase.internal.db.OfflineStore
import io.github.nesfeder.supabase.internal.postgrest.OfflineSelectResolver
import io.github.nesfeder.supabase.internal.postgrest.PostgrestQueryBuilderWrapper
import io.github.nesfeder.supabase.internal.postgrest.PostgrestQueryBuilderWrapperImpl
import io.github.nesfeder.supabase.internal.postgrest.buildDescriptorFromBuilder
import io.github.nesfeder.supabase.internal.postgrest.buildDescriptorFromRequest
import io.github.nesfeder.supabase.internal.util.isNetworkOrConnectivityFailure
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray

/**
 * Offline-aware PostgREST query builder.
 */
interface OfflinePostgrestQueryBuilder {

    suspend fun select(
        columns: Columns = Columns.ALL,
        readStrategy: ReadStrategy = ReadStrategy.NetworkFirst(),
        request: SelectRequestBuilder.() -> Unit = {}
    ): PostgrestResult

    fun selectAsFlow(
        columns: Columns = Columns.ALL,
        readStrategy: FlowReadStrategy = FlowReadStrategy.CacheFirstThenNetwork,
        request: SelectRequestBuilder.() -> Unit = {}
    ): Flow<PostgrestResult>

    suspend fun insertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: InsertRequestBuilder.() -> Unit = {}
    ): PostgrestResult

    suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: InsertRequestBuilder.() -> Unit = {}
    ): PostgrestResult

    suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: InsertRequestBuilder.() -> Unit = {}
    ): PostgrestResult

    suspend fun upsertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: UpsertRequestBuilder.() -> Unit = {}
    ): PostgrestResult

    suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: UpsertRequestBuilder.() -> Unit = {}
    ): PostgrestResult

    suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: UpsertRequestBuilder.() -> Unit = {}
    ): PostgrestResult

    suspend fun updateJson(
        body: JsonElement,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: PostgrestRequestBuilder.() -> Unit = {}
    ): PostgrestResult

    suspend fun <T : Any> updateWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: PostgrestRequestBuilder.() -> Unit = {}
    ): PostgrestResult

    suspend fun delete(
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: PostgrestRequestBuilder.() -> Unit = {}
    ): PostgrestResult
}

/**
 * Internal implementation that delegates to the online [PostgrestQueryBuilder] and augments it
 * with offline caching, mutation queuing, and overlay via [OfflineStore].
 */
internal class OfflinePostgrestQueryBuilderImpl(
    private val delegate: PostgrestQueryBuilderWrapper,
    private val store: OfflineStore,
    private val sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO)
) : OfflinePostgrestQueryBuilder {

    constructor(
        delegate: PostgrestQueryBuilder,
        store: OfflineStore,
        sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO)
    ) : this(PostgrestQueryBuilderWrapperImpl(delegate), store, sdkLogger)

    private val json = Json { ignoreUnknownKeys = true }
    private val selectResolver = OfflineSelectResolver(delegate, store, sdkLogger)

    override suspend fun select(
        columns: Columns,
        readStrategy: ReadStrategy,
        request: SelectRequestBuilder.() -> Unit
    ): PostgrestResult {
        return selectResolver.select(columns, readStrategy, request)
    }

    override fun selectAsFlow(
        columns: Columns,
        readStrategy: FlowReadStrategy,
        request: SelectRequestBuilder.() -> Unit
    ): Flow<PostgrestResult> {
        return selectResolver.selectAsFlow(columns, readStrategy, request)
    }

    // INSERT -------------------------------------------------------------------

    override suspend fun insertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        val table = delegate.table
        sdkLogger.debug { "INSERT $table request body: $body" }
        return try {
            val result = delegate.insert(body, request)
            sdkLogger.debug { "INSERT $table response: ${result.data}" }
            result
        } catch (exception: Exception) {
            when {
                mutationStrategy == MutationStrategy.QueueIfOffline && exception.isNetworkOrConnectivityFailure() -> {
                    val conversionMethod = delegate.postgrest.config.propertyConversionMethod

                    val insertBuilder = InsertRequestBuilder(conversionMethod).apply(request)
                    val descriptor = buildDescriptorFromBuilder(insertBuilder)

                    store.enqueueInsert(
                        table = table,
                        body = body,
                        defaultToNull = insertBuilder.defaultToNull,
                        filter = descriptor
                    )
                    sdkLogger.info { "INSERT $table queued offline (network unavailable)" }

                    buildResult("[]", delegate.postgrest)
                }

                else -> throw exception
            }
        }
    }

    override suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        val listSer = ListSerializer(serializer)
        val body = json.encodeToJsonElement(listSer, values).jsonArray
        return insertJson(body, mutationStrategy, request)
    }

    override suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        val elem = json.encodeToJsonElement(serializer, value)
        val body = buildJsonArray { add(elem) }
        return insertJson(body, mutationStrategy, request)
    }

    // UPSERT -------------------------------------------------------------------

    override suspend fun upsertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        val table = delegate.table
        sdkLogger.debug { "UPSERT $table request body: $body" }
        return try {
            val result = delegate.upsert(body, request)
            sdkLogger.debug { "UPSERT $table response: ${result.data}" }
            result
        } catch (exception: Exception) {
            when {
                mutationStrategy == MutationStrategy.QueueIfOffline && exception.isNetworkOrConnectivityFailure() -> {
                    val conversionMethod = delegate.postgrest.config.propertyConversionMethod

                    val upsertBuilder = UpsertRequestBuilder(conversionMethod).apply(request)
                    val descriptor = buildDescriptorFromBuilder(upsertBuilder)

                    store.enqueueUpsert(
                        table = table,
                        bodyJson = body,
                        defaultToNull = upsertBuilder.defaultToNull,
                        onConflict = upsertBuilder.onConflict,
                        ignoreDuplicates = upsertBuilder.ignoreDuplicates,
                        filter = descriptor
                    )
                    sdkLogger.info { "UPSERT $table queued offline (network unavailable)" }

                    buildResult("[]", delegate.postgrest)
                }

                else -> throw exception
            }
        }
    }

    override suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        val listSer = ListSerializer(serializer)
        val body = json.encodeToJsonElement(listSer, values).jsonArray
        return upsertJson(body, mutationStrategy, request)
    }

    override suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        val elem = json.encodeToJsonElement(serializer, value)
        val body = buildJsonArray { add(elem) }
        return upsertJson(body, mutationStrategy, request)
    }

    // UPDATE -------------------------------------------------------------------

    override suspend fun updateJson(
        body: JsonElement,
        mutationStrategy: MutationStrategy,
        request: PostgrestRequestBuilder.() -> Unit
    ): PostgrestResult {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        sdkLogger.debug { "UPDATE $table request body: $body" }

        return try {
            val result = delegate.update(body, request)
            sdkLogger.debug { "UPDATE $table response: ${result.data}" }
            result
        } catch (exception: Exception) {
            when {
                mutationStrategy == MutationStrategy.QueueIfOffline && exception.isNetworkOrConnectivityFailure() -> {
                    val descriptor = buildDescriptorFromRequest(
                        conversionMethod = conversionMethod,
                        request = request
                    )
                    store.enqueueUpdate(
                        table = table,
                        body = body,
                        filter = descriptor
                    )
                    sdkLogger.info { "UPDATE $table queued offline (network unavailable)" }
                    buildResult("[]", delegate.postgrest)
                }

                else -> throw exception
            }
        }
    }

    override suspend fun <T : Any> updateWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: PostgrestRequestBuilder.() -> Unit
    ): PostgrestResult {
        val elem = json.encodeToJsonElement(serializer, value)
        return updateJson(elem, mutationStrategy, request)
    }

    // DELETE -------------------------------------------------------------------

    override suspend fun delete(
        mutationStrategy: MutationStrategy,
        request: PostgrestRequestBuilder.() -> Unit
    ): PostgrestResult {
        val table = delegate.table
        val conversionMethod = delegate.postgrest.config.propertyConversionMethod
        sdkLogger.debug { "DELETE $table attempting network" }

        return try {
            val result = delegate.delete(request)
            sdkLogger.debug { "DELETE $table response: ${result.data}" }
            result
        } catch (e: Exception) {
            when {
                mutationStrategy == MutationStrategy.QueueIfOffline && e.isNetworkOrConnectivityFailure() -> {
                    val descriptor = buildDescriptorFromRequest(
                        conversionMethod = conversionMethod,
                        request = request
                    )
                    store.enqueueDelete(
                        table = table,
                        filter = descriptor
                    )
                    sdkLogger.info { "DELETE $table queued offline (network unavailable)" }
                    buildResult("[]", delegate.postgrest)
                }
                else -> throw e
            }
        }
    }

    // --------------------------------------------------------------------------

    private fun buildResult(data: String, postgrest: Postgrest): PostgrestResult {
        val headers: Headers = HeadersBuilder().build()
        return PostgrestResult(data, headers, postgrest)
    }
}
