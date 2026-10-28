// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.encodeToJsonElement
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.PostgrestUpdate
import io.github.jan.supabase.postgrest.query.buildPostgrestUpdate
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.internal.postgrest.OfflineTable
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray

/**
 * One table, read through the device's cache and written through the mutation queue: the offline
 * counterpart of supabase-kt's [PostgrestQueryBuilder], with its verbs, overloads and request DSL.
 *
 * Get one with [Offline.from] or `offline["table"]`. Each verb takes the offline choice as a named
 * parameter with a default, placed before the trailing request lambda: `readStrategy` for reads,
 * `mutationStrategy` for writes.
 *
 * Writes return [MutationOutcome] instead of [PostgrestResult]: [MutationOutcome.Applied] carries
 * the server's response, [MutationOutcome.Queued] says the write waits in the mutation queue. See
 * [MutationStrategy.QueueIfOffline] for how a write moves through the queue.
 *
 * ### Moving a call from supabase-kt
 *
 * Most calls written against supabase-kt's `supabase.from(table)` compile against
 * `supabase.offline.from(table)` with only the receiver changed and the [offline] accessor imported
 * (`io.github.nesfeder.supabase.offline.offline`). These need an edit:
 * - **A write whose result is read**, as in `insert(row) { select() }.decodeSingle<Row>()`: read
 *   the server's response with [MutationOutcome.resultOrNull], which is `null` when the write was
 *   queued.
 * - **A suspending call or an early `return` inside a request lambda.** supabase-kt's verbs are
 *   `inline`; these are not, because the library keeps the lambda and runs it again (to learn the
 *   request's shape, and to queue and replay a write). Make the call, or return, before the verb,
 *   and use its result inside the lambda.
 * - **A request lambda passed inside the parentheses**, as in `insert(row, { select() })`: the
 *   second positional parameter here is `mutationStrategy`. Name the lambda, `request = { … }`, or
 *   put it after the parentheses.
 * - **Realtime's `selectAsFlow(…)`** on a table: this builder's own [selectAsFlow] is the offline
 *   one. Keep realtime calls on `supabase.from(table)`.
 * - **The typed `rpc`** is this library's extension on [Offline]: import
 *   `io.github.nesfeder.supabase.offline.rpc` in place of `io.github.jan.supabase.postgrest.rpc`.
 * - **Two top-level filters on one column**, as in `filter { gte("age", 1); lte("age", 10) }`:
 *   supabase-kt sends only the first, so a read, an update or a delete here throws
 *   [IllegalArgumentException] before anything is sent or queued. Put them in one group, `filter {
 *   and { gte("age", 1); lte("age", 10) } }`, which sends both. A second top-level `or` group, or a
 *   text search after a filter on its column, replaces what came before, and is not caught.
 * - **A pattern list the server would misread**: supabase-kt quotes a pattern only when it holds
 *   one of `,.:()`, so `likeAll`, `likeAny`, `ilikeAll` or `ilikeAny` with an empty list, or with a
 *   pattern that is empty, `NULL`, edged with white space or holding a brace, throws
 *   [IllegalArgumentException] before anything is sent or queued.
 *
 * A verb may run its `request` lambda more than once: a read once to learn its shape and once per
 * request sent, a queueable write up to three times. The lambda must configure the same request
 * every time: no side effects, and no clock or counter read.
 */
public class OfflinePostgrestQueryBuilder internal constructor(private val engine: OfflineTable) {

    /** The PostgREST plugin the requests go through. Its serializer encodes the typed writes. */
    public val postgrest: Postgrest
        get() = engine.postgrest

    /** The table the requests target. */
    public val table: String
        get() = engine.table

    /** The schema the requests target: the client's default, unless [Offline.from] named one. */
    public val schema: String
        get() = engine.schema

    /**
     * Reads rows once, from the server or the device's cache as [readStrategy] says.
     *
     * Each request is cached on its own, under its columns, filters, order and range: offline, a
     * request never answered online has nothing cached, even when another cached read of the table
     * holds the rows it asks for. To show one row offline, read the list a screen already reads,
     * with the same request, and pick the row from it.
     *
     * Only a list of rows is cached. A read that asks for another shape (`single()`,
     * `maybeSingle()` from supabase-kt 3.8, `head`, a `count` with rows or without, `csv()`,
     * `geojson()`, `stripNulls()` or `explain()`) goes to the server under every strategy but
     * [ReadStrategy.CacheOnly], is not stored, and offline fails as [ReadStrategy.NetworkOnly]
     * does; under [ReadStrategy.CacheOnly] it sends nothing and throws [CacheMissException].
     *
     * @param columns the columns to select.
     * @param readStrategy where the rows come from; [ReadStrategy.NetworkFirst] by default, which
     *   serves the cached rows when the server cannot be reached.
     * @param request filters, order, range and the rest of the request, as in supabase-kt.
     * @return the server's response, or the cached rows (with the user's queued writes applied) as
     *   a result of the same shape. [PostgrestResult.isFromCache], [PostgrestResult.fetchedAt] and
     *   [PostgrestResult.hasPendingMutations] say which, and how old;
     *   [PostgrestResult.pendingRowKeys] names the rows a queued write changed.
     * @throws CacheMissException under [ReadStrategy.CacheOnly], when nothing cached may answer the
     *   read, or the read asks for a shape the cache never holds.
     * @throws NetworkDisabledException while network access is off ([Offline.disableNetwork]) and
     *   the cache cannot answer the read.
     * @throws OfflineClientClosedException when the client is closed.
     * @throws IllegalArgumentException when two top-level filters name one column (see
     *   [OfflinePostgrestQueryBuilder]).
     */
    @Throws(Throwable::class)
    public suspend fun select(
        columns: Columns = Columns.ALL,
        readStrategy: ReadStrategy = ReadStrategy.NetworkFirst(),
        request: SelectRequestBuilder.() -> Unit = {},
    ): PostgrestResult = engine.select(columns, readStrategy, request)

    /**
     * Reads rows as they change: the cached rows, with the user's queued writes applied, emitted
     * again whenever a write, a refresh or a server record changes them.
     *
     * Each emission is a [QuerySnapshot]: its rows (`null` while nothing is known yet; `[]` when
     * the server returned no rows), whether they are the cache's, when they were fetched, whether a
     * queued write changed them, and how the refresh behind them stands ([RefreshState]). Each
     * request is cached on its own, as for [select].
     *
     * A queued write shows at once, as it was sent: the new rows a queued INSERT or UPSERT adds
     * that the request's filters admit come after the server's rows, whatever the request's order
     * and limit, and lack the columns the server fills in (defaults, triggers) until it has the
     * write. Decode such rows into a type whose server-filled fields are optional.
     *
     * Every strategy ends the same way when the client closes: a collection live at [Offline.close]
     * completes, so a screen torn down with the client sees its flow end, not an error, and
     * `first()` on one that has not emitted yet throws [NoSuchElementException] (`firstOrNull()`
     * returns `null`); one started after it fails with [IllegalStateException] through the flow's
     * own error channel. A read that fails for another reason delivers the values before the
     * failure, then the failure itself. A slow collector can be handed a value a newer one has
     * already replaced; the last it is handed is the latest.
     *
     * A read of a shape the cache does not hold (see [select]) emits the server's answer once, with
     * [RefreshState.Idle], and completes; offline it fails, and under [FlowReadStrategy.CacheOnly]
     * it fails at once with [CacheMissException]. [request] may run several times, as for [select].
     *
     * Unlike supabase-kt's Realtime `selectAsFlow`, this one needs no Realtime channel: it follows
     * the device's cache and refreshes it from the server.
     *
     * @param columns the columns to select.
     * @param readStrategy how the read starts and refreshes;
     *   [FlowReadStrategy.CacheFirstThenNetwork] by default.
     * @param request filters, order, range and the rest of the request, as in supabase-kt.
     * @return the flow of emissions. It fails with [IllegalArgumentException] when two top-level
     *   filters name one column (see [OfflinePostgrestQueryBuilder]).
     */
    public fun selectAsFlow(
        columns: Columns = Columns.ALL,
        readStrategy: FlowReadStrategy = FlowReadStrategy.CacheFirstThenNetwork,
        request: SelectRequestBuilder.() -> Unit = {},
    ): Flow<QuerySnapshot> = engine.selectAsFlow(columns, readStrategy, request)

    /**
     * Inserts the rows of [body].
     *
     * @param body the rows, a JSON array of objects.
     * @param mutationStrategy whether the write may wait in the mutation queue;
     *   [MutationStrategy.QueueIfOffline] by default.
     * @param request the rest of the request, such as `select()` to have the rows returned.
     * @return [MutationOutcome.Applied] with the server's response, or [MutationOutcome.Queued].
     * @throws MutationNotQueueableException when a write the mutation queue cannot hold cannot be
     *   sent for want of a connection; see [MutationStrategy.QueueIfOffline].
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class)
    public suspend fun insert(
        body: JsonArray,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: InsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome = engine.insert(body, mutationStrategy, request)

    /**
     * Inserts [values], encoded by [postgrest]'s serializer, as supabase-kt encodes them.
     *
     * @param values the rows.
     * @param mutationStrategy whether the write may wait in the mutation queue;
     *   [MutationStrategy.QueueIfOffline] by default.
     * @param request the rest of the request, such as `select()` to have the rows returned.
     * @return [MutationOutcome.Applied] with the server's response, or [MutationOutcome.Queued].
     * @throws MutationNotQueueableException when a write the mutation queue cannot hold cannot be
     *   sent for want of a connection; see [MutationStrategy.QueueIfOffline].
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class)
    public suspend inline fun <reified T : Any> insert(
        values: List<T>,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        noinline request: InsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome =
        insert(
            postgrest.serializer.encodeToJsonElement(values).jsonArray,
            mutationStrategy,
            request,
        )

    /**
     * Inserts [value], encoded by [postgrest]'s serializer, as supabase-kt encodes it.
     *
     * @param value the row.
     * @param mutationStrategy whether the write may wait in the mutation queue;
     *   [MutationStrategy.QueueIfOffline] by default.
     * @param request the rest of the request, such as `select()` to have the row returned.
     * @return [MutationOutcome.Applied] with the server's response, or [MutationOutcome.Queued].
     * @throws MutationNotQueueableException when a write the mutation queue cannot hold cannot be
     *   sent for want of a connection; see [MutationStrategy.QueueIfOffline].
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class)
    public suspend inline fun <reified T : Any> insert(
        value: T,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        noinline request: InsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome = insert(listOf(value), mutationStrategy, request)

    /**
     * Upserts the rows of [body]: inserts them, or merges them into the rows they conflict with.
     *
     * @param body the rows, a JSON array of objects.
     * @param mutationStrategy whether the write may wait in the mutation queue;
     *   [MutationStrategy.QueueIfOffline] by default.
     * @param request the rest of the request, such as `onConflict` and `select()`.
     * @return [MutationOutcome.Applied] with the server's response, or [MutationOutcome.Queued].
     * @throws MutationNotQueueableException when a write the mutation queue cannot hold cannot be
     *   sent for want of a connection; see [MutationStrategy.QueueIfOffline].
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class)
    public suspend fun upsert(
        body: JsonArray,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: UpsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome = engine.upsert(body, mutationStrategy, request)

    /**
     * Upserts [values], encoded by [postgrest]'s serializer, as supabase-kt encodes them.
     *
     * @param values the rows.
     * @param mutationStrategy whether the write may wait in the mutation queue;
     *   [MutationStrategy.QueueIfOffline] by default.
     * @param request the rest of the request, such as `onConflict` and `select()`.
     * @return [MutationOutcome.Applied] with the server's response, or [MutationOutcome.Queued].
     * @throws MutationNotQueueableException when a write the mutation queue cannot hold cannot be
     *   sent for want of a connection; see [MutationStrategy.QueueIfOffline].
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class)
    public suspend inline fun <reified T : Any> upsert(
        values: List<T>,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        noinline request: UpsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome =
        upsert(
            postgrest.serializer.encodeToJsonElement(values).jsonArray,
            mutationStrategy,
            request,
        )

    /**
     * Upserts [value], encoded by [postgrest]'s serializer, as supabase-kt encodes it.
     *
     * @param value the row.
     * @param mutationStrategy whether the write may wait in the mutation queue;
     *   [MutationStrategy.QueueIfOffline] by default.
     * @param request the rest of the request, such as `onConflict` and `select()`.
     * @return [MutationOutcome.Applied] with the server's response, or [MutationOutcome.Queued].
     * @throws MutationNotQueueableException when a write the mutation queue cannot hold cannot be
     *   sent for want of a connection; see [MutationStrategy.QueueIfOffline].
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class)
    public suspend inline fun <reified T : Any> upsert(
        value: T,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        noinline request: UpsertRequestBuilder.() -> Unit = {},
    ): MutationOutcome = upsert(listOf(value), mutationStrategy, request)

    /**
     * Sets the columns [update] names on the rows [request] filters, with supabase-kt's update DSL
     * (`set("column", value)`, `Row::column setTo value`, `setToNull("column")`).
     *
     * A queued update replays exactly the values it sets. A value several devices change at once
     * belongs in a server-side computation, not in a queued write.
     *
     * @param update the columns to set.
     * @param mutationStrategy whether the write may wait in the mutation queue;
     *   [MutationStrategy.QueueIfOffline] by default. Queueing needs a filter the queue can record
     *   exactly; see [MutationStrategy.QueueIfOffline].
     * @param request the filter, and the rest of the request, such as `select()`.
     * @return [MutationOutcome.Applied] with the server's response, or [MutationOutcome.Queued].
     * @throws MutationNotQueueableException when a write the mutation queue cannot hold cannot be
     *   sent for want of a connection; see [MutationStrategy.QueueIfOffline].
     * @throws OfflineClientClosedException when the client is closed.
     * @throws IllegalArgumentException when two top-level filters name one column (see
     *   [OfflinePostgrestQueryBuilder]).
     */
    @Throws(Throwable::class)
    public suspend fun update(
        update: PostgrestUpdate.() -> Unit = {},
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: PostgrestRequestBuilder.() -> Unit = {},
    ): MutationOutcome = engine.update(updateBodyOf(update), mutationStrategy, request)

    /**
     * Sets the columns of [body] on the rows [request] filters. A queued update replays exactly the
     * values in [body].
     *
     * @param body the columns to set, a JSON object.
     * @param mutationStrategy whether the write may wait in the mutation queue;
     *   [MutationStrategy.QueueIfOffline] by default. Queueing needs a filter the queue can record
     *   exactly; see [MutationStrategy.QueueIfOffline].
     * @param request the filter, and the rest of the request, such as `select()`.
     * @return [MutationOutcome.Applied] with the server's response, or [MutationOutcome.Queued].
     * @throws MutationNotQueueableException when a write the mutation queue cannot hold cannot be
     *   sent for want of a connection; see [MutationStrategy.QueueIfOffline].
     * @throws OfflineClientClosedException when the client is closed.
     * @throws IllegalArgumentException when two top-level filters name one column (see
     *   [OfflinePostgrestQueryBuilder]).
     */
    @Throws(Throwable::class)
    public suspend fun update(
        body: JsonElement,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: PostgrestRequestBuilder.() -> Unit = {},
    ): MutationOutcome = engine.update(body, mutationStrategy, request)

    /**
     * Sets the columns of [value], encoded by [postgrest]'s serializer, on the rows [request]
     * filters.
     *
     * @param value the columns to set.
     * @param mutationStrategy whether the write may wait in the mutation queue;
     *   [MutationStrategy.QueueIfOffline] by default. Queueing needs a filter the queue can record
     *   exactly; see [MutationStrategy.QueueIfOffline].
     * @param request the filter, and the rest of the request, such as `select()`.
     * @return [MutationOutcome.Applied] with the server's response, or [MutationOutcome.Queued].
     * @throws MutationNotQueueableException when a write the mutation queue cannot hold cannot be
     *   sent for want of a connection; see [MutationStrategy.QueueIfOffline].
     * @throws OfflineClientClosedException when the client is closed.
     * @throws IllegalArgumentException when two top-level filters name one column (see
     *   [OfflinePostgrestQueryBuilder]).
     */
    @Throws(Throwable::class)
    public suspend inline fun <reified T : Any> update(
        value: T,
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        noinline request: PostgrestRequestBuilder.() -> Unit = {},
    ): MutationOutcome =
        update(postgrest.serializer.encodeToJsonElement(value), mutationStrategy, request)

    /**
     * Deletes the rows [request] filters.
     *
     * @param mutationStrategy whether the write may wait in the mutation queue;
     *   [MutationStrategy.QueueIfOffline] by default. Queueing needs a filter the queue can record
     *   exactly; see [MutationStrategy.QueueIfOffline].
     * @param request the filter, and the rest of the request, such as `select()`.
     * @return [MutationOutcome.Applied] with the server's response, or [MutationOutcome.Queued].
     * @throws MutationNotQueueableException when a write the mutation queue cannot hold cannot be
     *   sent for want of a connection; see [MutationStrategy.QueueIfOffline].
     * @throws OfflineClientClosedException when the client is closed.
     * @throws IllegalArgumentException when two top-level filters name one column (see
     *   [OfflinePostgrestQueryBuilder]).
     */
    @Throws(Throwable::class)
    public suspend fun delete(
        mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
        request: PostgrestRequestBuilder.() -> Unit = {},
    ): MutationOutcome = engine.delete(mutationStrategy, request)

    /**
     * The JSON object [update] describes, built as supabase-kt builds it for its own update: the
     * table's property conversion and [postgrest]'s serializer.
     */
    @OptIn(SupabaseInternal::class)
    private fun updateBodyOf(update: PostgrestUpdate.() -> Unit): JsonElement =
        buildPostgrestUpdate(
            postgrest.config.propertyConversionMethod,
            postgrest.serializer,
            update,
        )
}
