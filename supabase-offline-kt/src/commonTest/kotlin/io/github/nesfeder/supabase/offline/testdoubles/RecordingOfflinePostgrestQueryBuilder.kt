// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilder
import io.github.nesfeder.supabase.offline.OfflineReadResult
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.ReadStrategy
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * [OfflinePostgrestQueryBuilder] that records every typed (`*With`) mutation call and answers
 * [MutationOutcome.Queued], for tests of the reified extension functions that route onto those
 * verbs. The untyped verbs and reads are not expected and throw.
 */
internal class RecordingOfflinePostgrestQueryBuilder : OfflinePostgrestQueryBuilder {

    /** One recorded typed-verb call. */
    data class Call(
        val verb: String,
        val serializer: KSerializer<*>,
        val values: List<Any?>,
        val mutationStrategy: MutationStrategy,
    )

    /** Every typed-verb call received, in order. */
    val calls: MutableList<Call> = mutableListOf()

    private fun unexpected(member: String): Nothing =
        error("RecordingOfflinePostgrestQueryBuilder.$member must not be called in this test")

    override suspend fun select(
        columns: Columns,
        readStrategy: ReadStrategy,
        request: SelectRequestBuilder.() -> Unit,
    ): PostgrestResult = unexpected("select")

    override fun selectAsFlow(
        columns: Columns,
        readStrategy: FlowReadStrategy,
        request: SelectRequestBuilder.() -> Unit,
    ): Flow<OfflineReadResult> = unexpected("selectAsFlow")

    override suspend fun insertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit,
    ): MutationOutcome = unexpected("insertJson")

    override suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit,
    ): MutationOutcome = record(Call("insertWith(list)", serializer, values, mutationStrategy))

    override suspend fun <T : Any> insertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: InsertRequestBuilder.() -> Unit,
    ): MutationOutcome =
        record(Call("insertWith(value)", serializer, listOf(value), mutationStrategy))

    override suspend fun upsertJson(
        body: JsonArray,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit,
    ): MutationOutcome = unexpected("upsertJson")

    override suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        values: List<T>,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit,
    ): MutationOutcome = record(Call("upsertWith(list)", serializer, values, mutationStrategy))

    override suspend fun <T : Any> upsertWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: UpsertRequestBuilder.() -> Unit,
    ): MutationOutcome =
        record(Call("upsertWith(value)", serializer, listOf(value), mutationStrategy))

    override suspend fun updateJson(
        body: JsonElement,
        mutationStrategy: MutationStrategy,
        request: PostgrestRequestBuilder.() -> Unit,
    ): MutationOutcome = unexpected("updateJson")

    override suspend fun <T : Any> updateWith(
        serializer: KSerializer<T>,
        value: T,
        mutationStrategy: MutationStrategy,
        request: PostgrestRequestBuilder.() -> Unit,
    ): MutationOutcome =
        record(Call("updateWith(value)", serializer, listOf(value), mutationStrategy))

    override suspend fun delete(
        mutationStrategy: MutationStrategy,
        request: PostgrestRequestBuilder.() -> Unit,
    ): MutationOutcome = unexpected("delete")

    private fun record(call: Call): MutationOutcome {
        calls += call
        return MutationOutcome.Queued(
            PendingMutation(
                id = calls.size.toLong(),
                table = "",
                operation = MutationOperation.Insert,
                createdAt = Instant.fromEpochMilliseconds(0),
                rowKeys = emptyList(),
                body = null,
            )
        )
    }
}
