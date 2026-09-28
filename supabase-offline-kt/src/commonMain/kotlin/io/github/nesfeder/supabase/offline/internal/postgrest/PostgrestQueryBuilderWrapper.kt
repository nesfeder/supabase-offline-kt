package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * A wrapper around [PostgrestQueryBuilder] that provides a consistent interface for
 * PostgREST operations, facilitating testing and decoupling.
 */
internal interface PostgrestQueryBuilderWrapper {

    /**
     * The name of the table this builder targets.
     */
    val table: String

    /**
     * The database schema this builder targets.
     */
    val schema: String

    /**
     * The underlying [Postgrest] client instance.
     */
    val postgrest: Postgrest

    /**
     * Performs a SELECT query.
     *
     * @param columns The columns to retrieve.
     * @param request Configuration block for the [SelectRequestBuilder].
     * @return The result of the query.
     */
    suspend fun select(
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit
    ): PostgrestResult

    /**
     * Performs an INSERT query.
     *
     * @param body The JSON array of items to insert.
     * @param request Configuration block for the [InsertRequestBuilder].
     * @return The result of the query.
     */
    suspend fun insert(
        body: JsonArray,
        request: InsertRequestBuilder.() -> Unit
    ): PostgrestResult

    /**
     * Performs an UPSERT query.
     *
     * @param body The JSON array of items to upsert.
     * @param request Configuration block for the [UpsertRequestBuilder].
     * @return The result of the query.
     */
    suspend fun upsert(
        body: JsonArray,
        request: UpsertRequestBuilder.() -> Unit
    ): PostgrestResult

    /**
     * Performs an UPDATE query.
     *
     * @param body The JSON element representing the updates.
     * @param request Configuration block for the [PostgrestRequestBuilder].
     * @return The result of the query.
     */
    suspend fun update(
        body: JsonElement,
        request: PostgrestRequestBuilder.() -> Unit
    ): PostgrestResult

    /**
     * Performs a DELETE query.
     *
     * @param request Configuration block for the [PostgrestRequestBuilder].
     * @return The result of the query.
     */
    suspend fun delete(
        request: PostgrestRequestBuilder.() -> Unit
    ): PostgrestResult

    /**
     * Builds the request [write] would send, **without sending it**, and reports whether it asks
     * PostgREST to roll the transaction back — a write built with `dryRun()`.
     *
     * @param write Issues one write through this handle.
     * @return Whether it is a dry run, or `null` when this handle cannot tell.
     */
    suspend fun requestsRollback(write: suspend () -> PostgrestResult): Boolean?
}

/**
 * Default implementation of [PostgrestQueryBuilderWrapper] that delegates to a
 * real [PostgrestQueryBuilder].
 *
 * @property builder The underlying [PostgrestQueryBuilder] instance.
 */
internal class PostgrestQueryBuilderWrapperImpl(
    private val builder: PostgrestQueryBuilder
) : PostgrestQueryBuilderWrapper {

    init {
        builder.postgrest.supabaseClient.installPreferHeaderProbe()
    }

    /**
     * @see PostgrestQueryBuilderWrapper.table
     */
    override val table: String
        get() = builder.table

    /**
     * @see PostgrestQueryBuilderWrapper.schema
     */
    override val schema: String
        get() = builder.schema

    /**
     * @see PostgrestQueryBuilderWrapper.postgrest
     */
    override val postgrest: Postgrest
        get() = builder.postgrest

    /**
     * @see PostgrestQueryBuilderWrapper.select
     */
    override suspend fun select(
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit
    ): PostgrestResult {
        return builder.select(columns, request)
    }

    /**
     * @see PostgrestQueryBuilderWrapper.insert
     */
    override suspend fun insert(
        body: JsonArray,
        request: InsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        return builder.insert(body, request)
    }

    /**
     * @see PostgrestQueryBuilderWrapper.upsert
     */
    override suspend fun upsert(
        body: JsonArray,
        request: UpsertRequestBuilder.() -> Unit
    ): PostgrestResult {
        return builder.upsert(body, request)
    }

    /**
     * @see PostgrestQueryBuilderWrapper.update
     */
    override suspend fun update(
        body: JsonElement,
        request: PostgrestRequestBuilder.() -> Unit
    ): PostgrestResult {
        return builder.update(body, request)
    }

    /**
     * @see PostgrestQueryBuilderWrapper.delete
     */
    override suspend fun delete(
        request: PostgrestRequestBuilder.() -> Unit
    ): PostgrestResult {
        return builder.delete(request)
    }

    /**
     * Runs [write] under a short-circuiting [PreferHeaderProbe]: the request is built and stopped at
     * the client's HTTP pipeline, before it reaches the network.
     *
     * @see PostgrestQueryBuilderWrapper.requestsRollback
     */
    override suspend fun requestsRollback(write: suspend () -> PostgrestResult): Boolean? {
        val probe = PreferHeaderProbe(shortCircuit = true)
        try {
            withContext(probe) { write() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The short circuit surfaces as a failed request; any other failure means the request
            // was never built far enough to be read, and was not sent either.
        }
        return if (probe.observed) probe.requestedRollback else null
    }
}
