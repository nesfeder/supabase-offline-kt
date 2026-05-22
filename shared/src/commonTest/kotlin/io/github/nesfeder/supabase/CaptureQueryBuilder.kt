package io.github.nesfeder.supabase

import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

class TestException(message: String = "Intentional test exception") : RuntimeException(message)

class CaptureQueryBuilder : OfflinePostgrestQueryBuilder {
    var insertWithListCalled = false
    var insertWithValueCalled = false
    var upsertWithListCalled = false
    var upsertWithValueCalled = false
    var updateWithValueCalled = false

    var lastValues: List<Any?>? = null
    var lastValue: Any? = null
    var lastSerializer: KSerializer<*>? = null

    override suspend fun select(
        columns: Columns,
        readStrategy: ReadStrategy,
        request: SelectRequestBuilder.() -> Unit
    ): PostgrestResult = throw NotImplementedError()

    override fun selectAsFlow(
        columns: Columns,
        readStrategy: FlowReadStrategy,
        request: SelectRequestBuilder.() -> Unit
    ): Flow<PostgrestResult> = throw NotImplementedError()

    override suspend fun insertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit
    ): PostgrestResult = throw NotImplementedError()

    override suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        insertWithListCalled = true
        lastValues = values
        lastSerializer = serializer
        throw TestException()
    }

    override suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        insertWithValueCalled = true
        lastValue = value
        lastSerializer = serializer
        throw TestException()
    }

    override suspend fun upsertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit
    ): PostgrestResult = throw NotImplementedError()

    override suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        upsertWithListCalled = true
        lastValues = values
        lastSerializer = serializer
        throw TestException()
    }

    override suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        upsertWithValueCalled = true
        lastValue = value
        lastSerializer = serializer
        throw TestException()
    }

    override suspend fun updateJson(
        body: JsonElement,
        mutationStrategy: MutationStrategy,
        request: PostgrestRequestBuilder.() -> Unit
    ): PostgrestResult = throw NotImplementedError()

    override suspend fun <T : Any> updateWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: PostgrestRequestBuilder.() -> Unit
    ): PostgrestResult {
        updateWithValueCalled = true
        lastValue = value
        lastSerializer = serializer
        throw TestException()
    }

    override suspend fun delete(
        mutationStrategy: MutationStrategy,
        request: PostgrestRequestBuilder.() -> Unit
    ): PostgrestResult = throw NotImplementedError()
}
