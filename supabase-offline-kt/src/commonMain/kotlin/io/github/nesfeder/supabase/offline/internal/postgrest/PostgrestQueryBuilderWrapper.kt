// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * A wrapper around [PostgrestQueryBuilder] that provides a consistent interface for PostgREST
 * operations, facilitating testing and decoupling. It is also the [DryRunProbe] for the writes it
 * issues and the [ReadShapeProbe] for its reads.
 */
internal interface PostgrestQueryBuilderWrapper : DryRunProbe, ReadShapeProbe {

    /** The name of the table this builder targets. */
    val table: String

    /** The database schema this builder targets. */
    val schema: String

    /** The underlying [Postgrest] client instance. */
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
        request: SelectRequestBuilder.() -> Unit,
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
        request: InsertRequestBuilder.() -> Unit,
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
        request: UpsertRequestBuilder.() -> Unit,
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
        request: PostgrestRequestBuilder.() -> Unit,
    ): PostgrestResult

    /**
     * Performs a DELETE query.
     *
     * @param request Configuration block for the [PostgrestRequestBuilder].
     * @return The result of the query.
     */
    suspend fun delete(request: PostgrestRequestBuilder.() -> Unit): PostgrestResult
}

/**
 * Default implementation of [PostgrestQueryBuilderWrapper] that delegates to a real
 * [PostgrestQueryBuilder].
 *
 * @property builder The underlying [PostgrestQueryBuilder] instance.
 * @param readShapePostgrest the instance the read-shape check builds reads through; see
 *   [readShapePostgrestOf]. The client shares one across its handles.
 */
internal class PostgrestQueryBuilderWrapperImpl(
    private val builder: PostgrestQueryBuilder,
    readShapePostgrest: Postgrest = readShapePostgrestOf(builder.postgrest),
) : PostgrestQueryBuilderWrapper {

    init {
        // The offline client installs these when it is built; a handle made directly installs them
        // here. Either way each is installed once per client.
        builder.postgrest.supabaseClient.installDryRunCheck()
        builder.postgrest.supabaseClient.installReadShapeCheck()
        builder.postgrest.supabaseClient.installTransportFailureCapture()
    }

    private val dryRunProbe = PreferHeaderDryRunProbe(builder.postgrest.supabaseClient)

    private val readShapeProbe =
        PipelineReadShapeProbe(readShapePostgrest, builder.table, builder.schema)

    /** @see PostgrestQueryBuilderWrapper.table */
    override val table: String
        get() = builder.table

    /** @see PostgrestQueryBuilderWrapper.schema */
    override val schema: String
        get() = builder.schema

    /** @see PostgrestQueryBuilderWrapper.postgrest */
    override val postgrest: Postgrest
        get() = builder.postgrest

    /** @see PostgrestQueryBuilderWrapper.select */
    override suspend fun select(
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ): PostgrestResult {
        return builder.select(columns, request)
    }

    /** @see PostgrestQueryBuilderWrapper.insert */
    override suspend fun insert(
        body: JsonArray,
        request: InsertRequestBuilder.() -> Unit,
    ): PostgrestResult {
        return builder.insert(body, request)
    }

    /** @see PostgrestQueryBuilderWrapper.upsert */
    override suspend fun upsert(
        body: JsonArray,
        request: UpsertRequestBuilder.() -> Unit,
    ): PostgrestResult {
        return builder.upsert(body, request)
    }

    /** @see PostgrestQueryBuilderWrapper.update */
    override suspend fun update(
        body: JsonElement,
        request: PostgrestRequestBuilder.() -> Unit,
    ): PostgrestResult {
        return builder.update(body, request)
    }

    /** @see PostgrestQueryBuilderWrapper.delete */
    override suspend fun delete(request: PostgrestRequestBuilder.() -> Unit): PostgrestResult {
        return builder.delete(request)
    }

    /** @see PreferHeaderDryRunProbe */
    override suspend fun classify(write: suspend () -> PostgrestResult): DryRunVerdict =
        dryRunProbe.classify(write)

    /** @see PipelineReadShapeProbe */
    override suspend fun shapeOf(
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ): ReadShape = readShapeProbe.shapeOf(columns, request)
}
