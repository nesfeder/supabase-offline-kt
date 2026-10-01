// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:Suppress("EXTENSION_SHADOWED_BY_MEMBER")

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder

/*
 * The typed writes are members of OfflinePostgrestQueryBuilder, as supabase-kt's are, and a member
 * always wins over an extension. These extensions remain so that `import
 * io.github.nesfeder.supabase.offline.insert` and its siblings still resolve; a call through them
 * reaches the member.
 */

/**
 * Inserts [values]; the member [OfflinePostgrestQueryBuilder.insert] is what a call reaches.
 *
 * @param values the rows.
 * @param mutationStrategy whether the write may wait in the mutation queue.
 * @param request the rest of the request.
 * @return the outcome.
 */
@Deprecated(
    "insert is a member of OfflinePostgrestQueryBuilder; remove the import",
    ReplaceWith("insert(values, mutationStrategy, request)"),
)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.insert(
    values: List<T>,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: InsertRequestBuilder.() -> Unit = {},
): MutationOutcome = insert(values, mutationStrategy, request)

/**
 * Inserts [value]; the member [OfflinePostgrestQueryBuilder.insert] is what a call reaches.
 *
 * @param value the row.
 * @param mutationStrategy whether the write may wait in the mutation queue.
 * @param request the rest of the request.
 * @return the outcome.
 */
@Deprecated(
    "insert is a member of OfflinePostgrestQueryBuilder; remove the import",
    ReplaceWith("insert(value, mutationStrategy, request)"),
)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.insert(
    value: T,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: InsertRequestBuilder.() -> Unit = {},
): MutationOutcome = insert(value, mutationStrategy, request)

/**
 * Upserts [values]; the member [OfflinePostgrestQueryBuilder.upsert] is what a call reaches.
 *
 * @param values the rows.
 * @param mutationStrategy whether the write may wait in the mutation queue.
 * @param request the rest of the request.
 * @return the outcome.
 */
@Deprecated(
    "upsert is a member of OfflinePostgrestQueryBuilder; remove the import",
    ReplaceWith("upsert(values, mutationStrategy, request)"),
)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.upsert(
    values: List<T>,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: UpsertRequestBuilder.() -> Unit = {},
): MutationOutcome = upsert(values, mutationStrategy, request)

/**
 * Upserts [value]; the member [OfflinePostgrestQueryBuilder.upsert] is what a call reaches.
 *
 * @param value the row.
 * @param mutationStrategy whether the write may wait in the mutation queue.
 * @param request the rest of the request.
 * @return the outcome.
 */
@Deprecated(
    "upsert is a member of OfflinePostgrestQueryBuilder; remove the import",
    ReplaceWith("upsert(value, mutationStrategy, request)"),
)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.upsert(
    value: T,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: UpsertRequestBuilder.() -> Unit = {},
): MutationOutcome = upsert(value, mutationStrategy, request)

/**
 * Sets the columns of [value]; the member [OfflinePostgrestQueryBuilder.update] is what a call
 * reaches.
 *
 * @param value the columns to set.
 * @param mutationStrategy whether the write may wait in the mutation queue.
 * @param request the filter, and the rest of the request.
 * @return the outcome.
 */
@Deprecated(
    "update is a member of OfflinePostgrestQueryBuilder; remove the import",
    ReplaceWith("update(value, mutationStrategy, request)"),
)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.update(
    value: T,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: PostgrestRequestBuilder.() -> Unit = {},
): MutationOutcome = update(value, mutationStrategy, request)
