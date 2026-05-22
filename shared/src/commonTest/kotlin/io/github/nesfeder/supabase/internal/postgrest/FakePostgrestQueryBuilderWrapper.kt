package io.github.nesfeder.supabase.internal.postgrest

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Test fake for [PostgrestQueryBuilderWrapper].
 *
 * You can:
 *  - preconfigure each operation with [Result.success] or [Result.failure]
 *  - inspect the last body / columns passed into each method
 *
 * By default, all operations return a successful empty `[]` result.
 */
internal class FakePostgrestQueryBuilderWrapper(
    override val table: String = "test_table",
    override val postgrest: Postgrest,
) : PostgrestQueryBuilderWrapper {

    private val headers: Headers = HeadersBuilder().build()
    private val success = Result.success(PostgrestResult("[]", headers, postgrest))
    var selectResult: Result<PostgrestResult>? = success
    var insertResult: Result<PostgrestResult>? = success
    var upsertResult: Result<PostgrestResult>? = success
    var updateResult: Result<PostgrestResult>? = success
    var deleteResult: Result<PostgrestResult>? = success

    // Capture arguments for verification
    var lastSelectColumns: Columns? = null
    var lastInsertBody: JsonArray? = null
    var lastUpsertBody: JsonArray? = null
    var lastUpdateBody: JsonElement? = null

    // Capture request builder states and invocation flags
    var lastSelectBuilder: SelectRequestBuilder? = null
    var lastInsertBuilder: InsertRequestBuilder? = null
    var lastUpsertBuilder: UpsertRequestBuilder? = null
    var lastUpdateBuilder: PostgrestRequestBuilder? = null
    var lastDeleteBuilder: PostgrestRequestBuilder? = null

    var selectRequestInvoked: Boolean = false

    /**
     * Optional suspend hook invoked after bookkeeping and before returning the configured
     * [selectResult]. Use in flow tests to keep an in-flight network select while cache
     * emissions are collected.
     */
    var selectSuspensionPoint: (suspend () -> Unit)? = null
    var insertRequestInvoked: Boolean = false
    var upsertRequestInvoked: Boolean = false
    var updateRequestInvoked: Boolean = false
    var deleteRequestInvoked: Boolean = false

    // Convenience snapshots for commonly asserted options
    var lastInsertDefaultToNull: Boolean? = null
    var lastUpsertOnConflict: String? = null
    var lastUpsertIgnoreDuplicates: Boolean? = null
    var lastUpsertDefaultToNull: Boolean? = null

    override suspend fun select(
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit
    ): PostgrestResult {
        lastSelectColumns = columns
        val builder = SelectRequestBuilder(postgrest.config.propertyConversionMethod).apply(request)
        lastSelectBuilder = builder
        selectRequestInvoked = true
        selectSuspensionPoint?.invoke()
        return selectResult?.getOrThrow() ?: error("Select result not configured")
    }

    override suspend fun insert(
        body: JsonArray,
        request: InsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        lastInsertBody = body
        val builder = InsertRequestBuilder(postgrest.config.propertyConversionMethod).apply(request)
        lastInsertBuilder = builder
        insertRequestInvoked = true
        lastInsertDefaultToNull = builder.defaultToNull
        return insertResult?.getOrThrow() ?: error("Insert result not configured")
    }

    override suspend fun upsert(
        body: JsonArray,
        request: UpsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        lastUpsertBody = body
        val builder = UpsertRequestBuilder(postgrest.config.propertyConversionMethod).apply(request)
        lastUpsertBuilder = builder
        upsertRequestInvoked = true
        lastUpsertOnConflict = builder.onConflict
        lastUpsertIgnoreDuplicates = builder.ignoreDuplicates
        lastUpsertDefaultToNull = builder.defaultToNull
        return upsertResult?.getOrThrow() ?: error("Upsert result not configured")
    }

    override suspend fun update(
        body: JsonElement,
        request: PostgrestRequestBuilder.() -> Unit
    ): PostgrestResult {
        lastUpdateBody = body
        val builder =
            PostgrestRequestBuilder(postgrest.config.propertyConversionMethod).apply(request)
        lastUpdateBuilder = builder
        updateRequestInvoked = true
        return updateResult?.getOrThrow() ?: error("Update result not configured")
    }

    override suspend fun delete(
        request: PostgrestRequestBuilder.() -> Unit
    ): PostgrestResult {
        val builder =
            PostgrestRequestBuilder(postgrest.config.propertyConversionMethod).apply(request)
        lastDeleteBuilder = builder
        deleteRequestInvoked = true
        return deleteResult?.getOrThrow() ?: error("Delete result not configured")
    }
}