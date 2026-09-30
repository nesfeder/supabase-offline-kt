// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.internal.postgrest.DryRunVerdict
import io.github.nesfeder.supabase.offline.internal.postgrest.PostgrestQueryBuilderWrapper
import io.github.nesfeder.supabase.offline.internal.postgrest.ReadShape
import io.ktor.http.HeadersBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Scripted [PostgrestQueryBuilderWrapper], with no HTTP: SELECT answers [onSelect], and each write
 * records its call, then answers its handler. A write whose handler is `null` throws, so an
 * unexpected write fails the test; a handler may throw to script a failure. Build answers with
 * [postgrestResultOf].
 */
internal class FakePostgrestQueryBuilderWrapper(
    override val table: String,
    override val postgrest: Postgrest,
    override val schema: String = "public",
    private val onSelect: suspend () -> PostgrestResult = {
        error("FakePostgrestQueryBuilderWrapper.select must not be called in this test")
    },
    private val onInsert: (suspend (JsonArray) -> PostgrestResult)? = null,
    private val onUpsert: (suspend (JsonArray) -> PostgrestResult)? = null,
    private val onUpdate: (suspend (JsonElement) -> PostgrestResult)? = null,
    private val onDelete: (suspend () -> PostgrestResult)? = null,
    /**
     * What [classify] answers: `true` a dry run, `false` an ordinary write (the default), `null` a
     * check that cannot tell. A scripted handle builds no HTTP request, so it cannot see a dry run.
     */
    private val dryRunVerdict: Boolean? = false,
) : PostgrestQueryBuilderWrapper {

    /** Bodies passed to [insert], in call order — lets tests assert what the builder encoded. */
    val recordedInsertBodies: MutableList<JsonArray> = mutableListOf()

    /** Bodies passed to [upsert], in call order. */
    val recordedUpsertBodies: MutableList<JsonArray> = mutableListOf()

    /** Bodies passed to [update], in call order. */
    val recordedUpdateBodies: MutableList<JsonElement> = mutableListOf()

    /** Number of [delete] invocations — lets tests assert a guarded replay never fired. */
    var recordedDeleteCount: Int = 0
        private set

    private fun unsupported(member: String): Nothing =
        error("FakePostgrestQueryBuilderWrapper.$member must not be called in this test")

    override suspend fun select(
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ): PostgrestResult = onSelect()

    override suspend fun insert(
        body: JsonArray,
        request: InsertRequestBuilder.() -> Unit,
    ): PostgrestResult {
        recordedInsertBodies += body
        return onInsert?.invoke(body) ?: unsupported("insert")
    }

    override suspend fun upsert(
        body: JsonArray,
        request: UpsertRequestBuilder.() -> Unit,
    ): PostgrestResult {
        recordedUpsertBodies += body
        return onUpsert?.invoke(body) ?: unsupported("upsert")
    }

    override suspend fun update(
        body: JsonElement,
        request: PostgrestRequestBuilder.() -> Unit,
    ): PostgrestResult {
        recordedUpdateBodies += body
        return onUpdate?.invoke(body) ?: unsupported("update")
    }

    override suspend fun delete(request: PostgrestRequestBuilder.() -> Unit): PostgrestResult {
        recordedDeleteCount++
        return onDelete?.invoke() ?: unsupported("delete")
    }

    /** Answers [dryRunVerdict] without running [write]: `null` for a check that cannot tell. */
    override suspend fun classify(write: suspend () -> PostgrestResult): DryRunVerdict =
        when (dryRunVerdict) {
            true -> DryRunVerdict.DryRun
            false -> DryRunVerdict.NotDryRun
            null -> DryRunVerdict.Unavailable
        }

    /**
     * Every read is a row list: a scripted handle builds no HTTP request whose shape could be read.
     */
    override suspend fun shapeOf(
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        onBuilt: (SelectRequestBuilder) -> Unit,
    ): ReadShape = ReadShape.RowList
}

/**
 * Wraps [payload] in a [PostgrestResult] with no headers, as the engine builds a result from a
 * cached payload; for the handlers of [FakePostgrestQueryBuilderWrapper].
 */
internal fun postgrestResultOf(payload: String, postgrest: Postgrest): PostgrestResult =
    PostgrestResult(payload, HeadersBuilder().build(), postgrest)
