// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.knownOperation
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.MisreadValue
import io.github.nesfeder.supabase.offline.internal.postgrest.RemoteTable
import io.github.nesfeder.supabase.offline.internal.postgrest.RowMatch
import io.github.nesfeder.supabase.offline.internal.postgrest.decodeFilterOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.isPlainColumn
import io.github.nesfeder.supabase.offline.internal.postgrest.misreadReplayValueOf
import io.github.nesfeder.supabase.offline.internal.postgrest.replayOnto
import io.github.nesfeder.supabase.offline.internal.postgrest.replaysFully
import io.github.nesfeder.supabase.offline.internal.postgrest.rowMatch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds the request that replays a queued row write, or says why it cannot be sent faithfully: its
 * body and filter read back, a guarded UPDATE or DELETE told apart for [GuardedReplay], and the
 * INSERT, UPSERT, UPDATE and DELETE sent as supabase-kt's builders would send them.
 */
internal class ReplayRequests(
    /** Each table's version column, which guards a queued UPDATE or DELETE of one row. */
    private val versionColumns: VersionColumns,
    /** Each table's key columns. */
    private val primaryKeys: PrimaryKeys,
    private val engineLogger: EngineLogger,
) {

    /** A mutation ready to send, or refused before any request. */
    sealed interface Prepared {
        /** Ready; [request] sends it and says whether rows of it were already on the server. */
        class Ready(val request: suspend (RemoteTable) -> Boolean) : Prepared

        class Refused(val outcome: ReplayOutcome.Rejected) : Prepared

        /**
         * A guarded UPDATE ([body] set) or DELETE (`null`): [filter] plus `eq` on [versionColumn],
         * first at [baseVersion]; [keyFilter] reads its one row back by [keyColumns].
         */
        class Guarded(
            val body: JsonObject?,
            val filter: FilterDescriptor,
            val keyFilter: FilterDescriptor,
            val keyColumns: List<String>,
            val versionColumn: String,
            val baseVersion: String,
        ) : Prepared {

            /** What a send of [sent] asks back: the key, the version and the columns it sets. */
            fun returnedColumns(sent: JsonObject?): Columns =
                selectedColumns(keyColumns + versionColumn + sent?.keys.orEmpty())

            /** What the read by key asks for: [returnedColumns] and the filter's other columns. */
            fun readColumns(sent: JsonObject?): Columns =
                selectedColumns(
                    keyColumns +
                        versionColumn +
                        sent?.keys.orEmpty() +
                        filter.conditions
                            .map { condition -> condition.column }
                            .filter(::isPlainColumn)
                )

            /**
             * How [row] meets the conditions of [filter] beyond the key, as the overlay evaluates
             * them: [RowMatch.MATCHES] when there is none, [RowMatch.UNKNOWN] when one is not
             * evaluated and none is false.
             */
            fun filterBeyondKeyMatch(row: JsonObject): RowMatch {
                val beyondKey =
                    filter.conditions.filterNot { condition -> condition.column in keyColumns }
                if (beyondKey.isEmpty()) return RowMatch.MATCHES
                return rowMatch(row, FilterDescriptor(conditions = beyondKey))
            }
        }
    }

    fun refuse(mutation: MutationRow, reason: String, message: String): Prepared.Refused {
        engineLogger.warning {
            "Queued ${mutation.operation} on ${mutation.table} $reason; moving it to the rejected list"
        }
        return Prepared.Refused(ReplayOutcome.Rejected(message = message, serverError = null))
    }

    /**
     * The request that replays [mutation], or why it cannot be sent faithfully.
     *
     * @param earlierSendUnconfirmed Whether an earlier send of it may have reached the server.
     */
    fun requestFor(mutation: MutationRow, earlierSendUnconfirmed: Boolean): Prepared {
        val json = EngineJson
        val filter = decodeFilterOrNull(mutation.filterJson, json)
        // A stored filter that no longer decodes is not a missing one: sending without it would
        // reach rows the write never targeted
        if (mutation.filterJson != null && filter == null)
            return refuse(
                mutation,
                "has a filter that can no longer be read",
                "The queued filter can no longer be read",
            )
        return when (mutation.knownOperation) {
            MutationOperation.INSERT -> {
                val body =
                    parseJsonArrayOrNull(json, mutation.bodyJson)
                        ?: return refuse(
                            mutation,
                            BODY_NO_LONGER_PARSES,
                            "The queued body no longer parses",
                        )
                Prepared.Ready { tableApi ->
                    sendInsert(
                        tableApi,
                        mutation.table,
                        body,
                        mutation.defaultToNull,
                        filter,
                        earlierSendUnconfirmed,
                    )
                }
            }

            MutationOperation.UPSERT -> {
                val body =
                    parseJsonArrayOrNull(json, mutation.bodyJson)
                        ?: return refuse(
                            mutation,
                            BODY_NO_LONGER_PARSES,
                            "The queued body no longer parses",
                        )
                Prepared.Ready { tableApi ->
                    sendUpsert(
                        tableApi = tableApi,
                        body = body,
                        onConflict = mutation.onConflict,
                        ignoreDuplicates = mutation.ignoreDuplicates,
                        defaultToNull = mutation.defaultToNull,
                        filter = filter,
                    )
                    false
                }
            }

            MutationOperation.UPDATE -> {
                val body =
                    parseJsonElementOrNull(json, mutation.bodyJson)
                        ?: return refuse(
                            mutation,
                            BODY_NO_LONGER_PARSES,
                            "The queued body no longer parses",
                        )
                val replayableFilter =
                    replayableFilterOrNull(filter)
                        ?: return refuseUnreplayableFilter(mutation, filter)
                misreadReplayValueOf(replayableFilter)?.let { misread ->
                    return refuseMisreadFilterValue(mutation, misread)
                }
                if (body is JsonObject) {
                    guardedOrNull(mutation, replayableFilter, body)?.let { guarded ->
                        return guarded
                    }
                }
                Prepared.Ready { tableApi ->
                    sendUpdate(tableApi, body, replayableFilter)
                    false
                }
            }

            MutationOperation.DELETE -> {
                val replayableFilter =
                    replayableFilterOrNull(filter)
                        ?: return refuseUnreplayableFilter(mutation, filter)
                misreadReplayValueOf(replayableFilter)?.let { misread ->
                    return refuseMisreadFilterValue(mutation, misread)
                }
                guardedOrNull(mutation, replayableFilter, body = null)?.let { guarded ->
                    return guarded
                }
                Prepared.Ready { tableApi ->
                    sendDelete(tableApi, replayableFilter)
                    false
                }
            }

            null ->
                refuse(
                    mutation,
                    "has an unknown operation",
                    "The queued operation is not recognized",
                )
        }
    }

    /**
     * [mutation] as a guarded replay of [filter], with [body] for an UPDATE: when it has a base,
     * its table still has a version column [filter] does not name, and [filter] pins one row's key.
     */
    private fun guardedOrNull(
        mutation: MutationRow,
        filter: FilterDescriptor,
        body: JsonObject?,
    ): Prepared.Guarded? {
        val baseVersion = mutation.baseVersion ?: return null
        val versionColumn = versionColumns.of(mutation.table) ?: return null
        if (filter.conditions.any { condition -> condition.column == versionColumn }) return null
        val key = pinnedRowKeyOf(filter, primaryKeys.of(mutation.table)) ?: return null
        val keyFilter =
            FilterDescriptor(
                conditions =
                    key.map { (column, value) ->
                        FilterDescriptor.Condition(column, "eq", value.jsonPrimitive.content)
                    }
            )
        return Prepared.Guarded(
            body = body,
            filter = filter,
            keyFilter = keyFilter,
            keyColumns = key.keys.toList(),
            versionColumn = versionColumn,
            baseVersion = baseVersion,
        )
    }

    /**
     * [filter] when replaying it reaches exactly the rows it captured. A missing or lossy filter
     * would reach more rows than the caller targeted, so it is never sent.
     */
    private fun replayableFilterOrNull(filter: FilterDescriptor?): FilterDescriptor? =
        filter?.takeIf { descriptor ->
            replaysFully(descriptor)
        }

    private fun refuseUnreplayableFilter(
        mutation: MutationRow,
        filter: FilterDescriptor?,
    ): Prepared.Refused =
        if (filter == null) {
            refuse(
                mutation,
                "has no filter",
                "An ${mutation.operation} without a filter is never replayed",
            )
        } else {
            refuse(
                mutation,
                "has a filter that no longer replays in full",
                "The queued filter no longer replays in full",
            )
        }

    /**
     * Refuses a write whose stored filter holds [misread], a value the server would read with its
     * escapes: queued by a release that did not refuse it, its send would match nothing. The
     * rejected entry names the column and the operator, never the value.
     */
    private fun refuseMisreadFilterValue(
        mutation: MutationRow,
        misread: MisreadValue,
    ): Prepared.Refused =
        refuse(
            mutation,
            "has a filter value the server would read with its escapes",
            "The queued filter's `${misread.operator}` value on `${misread.column}` holds a " +
                "quote or a backslash and none of `,.:()`, which supabase-kt escapes but does " +
                "not quote, so the server would read the escapes as the value",
        )

    private fun parseJsonArrayOrNull(json: Json, raw: String): JsonArray? =
        try {
            json.parseToJsonElement(raw) as? JsonArray
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun parseJsonElementOrNull(json: Json, raw: String): JsonElement? =
        try {
            json.parseToJsonElement(raw)
        } catch (_: IllegalArgumentException) {
            null
        }

    /* ───────────────────────────── Remote calls ───────────────────────────── */

    /**
     * Replays an INSERT. One that may already be on the server, and whose rows all carry the
     * table's primary key, goes out as an upsert on that key that ignores duplicates and counts the
     * rows it wrote; every other INSERT goes out as sent, so a duplicate key is refused by the
     * server. An upsert with no matching unique constraint (`42P10`) is sent again as the plain
     * INSERT.
     *
     * @return whether the server already held rows of it, taken as an earlier send's.
     */
    private suspend fun sendInsert(
        tableApi: RemoteTable,
        table: String,
        body: JsonArray,
        defaultToNull: Boolean?,
        filter: FilterDescriptor?,
        earlierSendUnconfirmed: Boolean,
    ): Boolean {
        val keyColumns = primaryKeys.of(table)
        if (!earlierSendUnconfirmed || !everyRowCarriesPrimaryKey(body, keyColumns)) {
            insertAsSent(tableApi, body, defaultToNull, filter)
            return false
        }
        val result =
            try {
                tableApi.upsert(body) {
                    this.onConflict = keyColumns.joinToString(",")
                    this.ignoreDuplicates = true
                    this.defaultToNull = defaultToNull ?: true
                    count(Count.EXACT)
                    if (filter != null) replayOnto(this, filter)
                }
            } catch (failure: PostgrestRestException) {
                if (failure.code != NO_MATCHING_CONFLICT_TARGET) throw failure
                engineLogger.warning {
                    "Replayed INSERT on $table has no unique key to match; sending it as a plain INSERT"
                }
                insertAsSent(tableApi, body, defaultToNull, filter)
                return false
            }
        val written = result.countOrNull()
        if (written != null && written < body.size) {
            engineLogger.info {
                "Replayed INSERT on $table: ${body.size - written} of ${body.size} rows were already on the server, taken as its earlier send"
            }
            return true
        }
        return false
    }

    private suspend fun insertAsSent(
        tableApi: RemoteTable,
        body: JsonArray,
        defaultToNull: Boolean?,
        filter: FilterDescriptor?,
    ) {
        tableApi.insert(body) {
            this.defaultToNull = defaultToNull ?: true
            if (filter != null) replayOnto(this, filter)
        }
    }

    private suspend fun sendUpsert(
        tableApi: RemoteTable,
        body: JsonArray,
        onConflict: String?,
        ignoreDuplicates: Boolean?,
        defaultToNull: Boolean?,
        filter: FilterDescriptor? = null,
    ) {
        tableApi.upsert(body) {
            this.onConflict = onConflict
            this.ignoreDuplicates = ignoreDuplicates ?: false
            this.defaultToNull = defaultToNull ?: true
            if (filter != null) replayOnto(this, filter)
        }
    }

    private suspend fun sendUpdate(
        tableApi: RemoteTable,
        body: JsonElement,
        filter: FilterDescriptor,
    ) {
        tableApi.update(body) { replayOnto(this, filter) }
    }

    private suspend fun sendDelete(
        tableApi: RemoteTable,
        filter: FilterDescriptor,
    ) {
        tableApi.delete { replayOnto(this, filter) }
    }

    private companion object {
        /** Postgres: `ON CONFLICT` names columns with no unique or exclusion constraint. */
        const val NO_MATCHING_CONFLICT_TARGET = "42P10"
        const val BODY_NO_LONGER_PARSES = "has a body that no longer parses"
    }
}

/**
 * [columns], each once, as a PostgREST `select` list: a name with anything but letters, digits and
 * underscores is double-quoted.
 */
private fun selectedColumns(columns: List<String>): Columns =
    Columns.list(
        columns.distinct().map { column ->
            if (column.all { character -> character.isLetterOrDigit() || character == '_' }) column
            else "\"" + column.replace("\"", "\\\"") + "\""
        }
    )
