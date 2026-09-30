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
 * The engine's handle on one table's PostgREST requests, which tests replace with a scripted one.
 * It is also the [DryRunProbe] for its writes and the [ReadShapeProbe] for its reads.
 */
internal interface PostgrestQueryBuilderWrapper : DryRunProbe, ReadShapeProbe {

    /** The name of the table this builder targets. */
    val table: String

    /** The database schema this builder targets. */
    val schema: String

    /** The underlying [Postgrest] client instance. */
    val postgrest: Postgrest

    /** Sends a SELECT of [columns] that [request] configures. */
    suspend fun select(
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ): PostgrestResult

    /** Sends an INSERT of the rows in [body] that [request] configures. */
    suspend fun insert(
        body: JsonArray,
        request: InsertRequestBuilder.() -> Unit,
    ): PostgrestResult

    /** Sends an UPSERT of the rows in [body] that [request] configures. */
    suspend fun upsert(
        body: JsonArray,
        request: UpsertRequestBuilder.() -> Unit,
    ): PostgrestResult

    /** Sends an UPDATE that sets [body] on the rows [request] filters. */
    suspend fun update(
        body: JsonElement,
        request: PostgrestRequestBuilder.() -> Unit,
    ): PostgrestResult

    /** Sends a DELETE of the rows [request] filters. */
    suspend fun delete(request: PostgrestRequestBuilder.() -> Unit): PostgrestResult
}

/**
 * The [PostgrestQueryBuilderWrapper] over a real [PostgrestQueryBuilder].
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
        onBuilt: (SelectRequestBuilder) -> Unit,
    ): ReadShape = readShapeProbe.shapeOf(columns, request, onBuilt)
}
