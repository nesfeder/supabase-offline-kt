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
internal interface RemoteTable : DryRunProbe, ReadShapeProbe {

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

    /**
     * [classify] for the write [write] makes on the handle it is given. The real handle gives it
     * one of this table whose requests carry a placeholder token and are all stopped: a dry run is
     * told from its request's shape alone, so even when the session's token cannot be had.
     */
    suspend fun classifyTokenFree(write: suspend (RemoteTable) -> PostgrestResult): DryRunVerdict =
        classify {
            write(this)
        }
}

/**
 * The [RemoteTable] over a real [PostgrestQueryBuilder].
 *
 * @property builder The underlying [PostgrestQueryBuilder] instance.
 * @param readShapePostgrest the instance the read-shape check builds reads through; see
 *   [readShapePostgrestOf]. The client shares one across its handles.
 */
internal class SupabaseRemoteTable(
    private val builder: PostgrestQueryBuilder,
    private val readShapePostgrest: Postgrest = readShapePostgrestOf(builder.postgrest),
) : RemoteTable {

    init {
        // The offline client installs these when it is built; a handle made directly installs them
        // here. Either way each is installed once per client.
        builder.postgrest.supabaseClient.installDryRunCheck()
        builder.postgrest.supabaseClient.installReadShapeCheck()
        builder.postgrest.supabaseClient.installTransportFailureCapture()
    }

    private val dryRunProbe = PreferHeaderDryRunProbe(builder.postgrest.supabaseClient)

    /** Reads writes built on [tokenFreeHandle], stopping every request they make. */
    private val tokenFreeDryRunProbe =
        PreferHeaderDryRunProbe(builder.postgrest.supabaseClient, stopsEveryRequest = true)

    /**
     * This table on [readShapePostgrest], whose requests carry a placeholder token; made when first
     * asked for.
     */
    private val tokenFreeHandle: RemoteTable by lazy {
        SupabaseRemoteTable(
            readShapePostgrest.from(builder.schema, builder.table),
            readShapePostgrest,
        )
    }

    private val readShapeProbe =
        PipelineReadShapeProbe(readShapePostgrest, builder.table, builder.schema)

    /** @see RemoteTable.table */
    override val table: String
        get() = builder.table

    /** @see RemoteTable.schema */
    override val schema: String
        get() = builder.schema

    /** @see RemoteTable.postgrest */
    override val postgrest: Postgrest
        get() = builder.postgrest

    /** @see RemoteTable.select */
    override suspend fun select(
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ): PostgrestResult {
        return builder.select(columns, request)
    }

    /** @see RemoteTable.insert */
    override suspend fun insert(
        body: JsonArray,
        request: InsertRequestBuilder.() -> Unit,
    ): PostgrestResult {
        return builder.insert(body, request)
    }

    /** @see RemoteTable.upsert */
    override suspend fun upsert(
        body: JsonArray,
        request: UpsertRequestBuilder.() -> Unit,
    ): PostgrestResult {
        return builder.upsert(body, request)
    }

    /** @see RemoteTable.update */
    override suspend fun update(
        body: JsonElement,
        request: PostgrestRequestBuilder.() -> Unit,
    ): PostgrestResult {
        return builder.update(body, request)
    }

    /** @see RemoteTable.delete */
    override suspend fun delete(request: PostgrestRequestBuilder.() -> Unit): PostgrestResult {
        return builder.delete(request)
    }

    /** @see PreferHeaderDryRunProbe */
    override suspend fun classify(write: suspend () -> PostgrestResult): DryRunVerdict =
        dryRunProbe.classify(write)

    /** @see RemoteTable.classifyTokenFree */
    override suspend fun classifyTokenFree(
        write: suspend (RemoteTable) -> PostgrestResult
    ): DryRunVerdict = tokenFreeDryRunProbe.classify { write(tokenFreeHandle) }

    /** @see PipelineReadShapeProbe */
    override suspend fun shapeOf(
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        onBuilt: (SelectRequestBuilder) -> Unit,
    ): ReadShape = readShapeProbe.shapeOf(columns, request, onBuilt)
}
