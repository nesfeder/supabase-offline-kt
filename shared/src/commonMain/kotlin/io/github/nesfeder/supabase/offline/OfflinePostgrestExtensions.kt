package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import kotlinx.serialization.serializer

/**
 * Insert a list of [values], reporting whether the INSERT executed on the server
 * ([MutationOutcome.Applied]) or was queued for offline replay ([MutationOutcome.Queued]).
 * A caller reading the row back can construct the domain object from [values] on
 * [MutationOutcome.Queued] instead of decoding a placeholder.
 *
 * @param values rows to insert.
 * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
 * @param request request-configuration block (e.g. `select()`).
 * @return [MutationOutcome.Applied] with the server response, or [MutationOutcome.Queued].
 */
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.insert(
    values: List<T>,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: InsertRequestBuilder.() -> Unit = {}
): MutationOutcome = insertWith(serializer<T>(), values, mutationStrategy, request)

/**
 * Insert a single [value].
 *
 * @param value row to insert.
 * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
 * @param request request-configuration block (e.g. `select()`).
 * @return [MutationOutcome.Applied] with the server response, or [MutationOutcome.Queued].
 */
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.insert(
    value: T,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: InsertRequestBuilder.() -> Unit = {}
): MutationOutcome = insertWith(serializer(), value, mutationStrategy, request)

/**
 * Upsert a list of [values].
 *
 * @param values rows to upsert.
 * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
 * @param request request-configuration block (e.g. `onConflict = …; select()`).
 * @return [MutationOutcome.Applied] with the server response, or [MutationOutcome.Queued].
 */
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.upsert(
    values: List<T>,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: UpsertRequestBuilder.() -> Unit = {}
): MutationOutcome = upsertWith(serializer<T>(), values, mutationStrategy, request)

/**
 * Upsert a single [value].
 *
 * @param value row to upsert.
 * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
 * @param request request-configuration block (e.g. `onConflict = …; select()`).
 * @return [MutationOutcome.Applied] with the server response, or [MutationOutcome.Queued].
 */
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.upsert(
    value: T,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: UpsertRequestBuilder.() -> Unit = {}
): MutationOutcome = upsertWith(serializer(), value, mutationStrategy, request)

/**
 * Update columns from a single [value], targeting rows via the [request] filter.
 *
 * @param value row to update.
 * @param mutationStrategy offline strategy; defaults to [MutationStrategy.QueueIfOffline].
 * @param deltaColumns columns of [value] carrying a change relative to a snapshot rather than an
 *   absolute; see [OfflinePostgrestQueryBuilder.updateJson] and [NumericDeltaColumn].
 * @param request request-configuration block (e.g. `filter { … }; select()`).
 * @return [MutationOutcome.Applied] with the server response, or [MutationOutcome.Queued].
 */
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.update(
    value: T,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    deltaColumns: List<NumericDeltaColumn> = emptyList(),
    noinline request: PostgrestRequestBuilder.() -> Unit = {}
): MutationOutcome = updateWith(serializer(), value, mutationStrategy, deltaColumns, request)
