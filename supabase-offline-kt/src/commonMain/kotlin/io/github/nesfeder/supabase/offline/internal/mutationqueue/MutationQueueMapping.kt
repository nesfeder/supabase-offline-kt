// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.ConflictResolution
import io.github.nesfeder.supabase.offline.ConflictedMutation
import io.github.nesfeder.supabase.offline.MutationConflict
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.knownOperation
import io.github.nesfeder.supabase.offline.internal.db.mutationOperationStoredAs
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.decodeFilterOrNull
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * This mutation row as a [PendingMutation].
 *
 * @param json The engine's [Json].
 * @param primaryKeys Where the table's key columns come from, for [PendingMutation.rowKeys].
 * @return The pending mutation, or `null` for a row whose operation no build of the engine writes.
 */
internal fun MutationRow.toPendingMutation(json: Json, primaryKeys: PrimaryKeys): PendingMutation? {
    val operation = knownOperation ?: return null
    val body = bodyOf(operation, bodyJson, json)
    return PendingMutation(
        id = id,
        table = table,
        operation = operation,
        createdAt = Instant.fromEpochMilliseconds(createdAtMs),
        rowKeys = rowKeysOf(body, filterJson, primaryKeys.of(table), json),
        body = body,
        baseVersion = baseVersion,
    )
}

/**
 * This persisted conflict as a [ConflictedMutation].
 *
 * @param json The engine's [Json].
 * @param primaryKeys Where the table's key columns come from, for [ConflictedMutation.rowKeys].
 * @return The conflicted mutation. A stored operation no build writes is reported as an update.
 */
internal fun StoredConflict.toConflictedMutation(
    json: Json,
    primaryKeys: PrimaryKeys,
): ConflictedMutation {
    val operation = mutationOperationStoredAs(operation) ?: MutationOperation.UPDATE
    val body = bodyOf(operation, bodyJson, json)
    return ConflictedMutation(
        id = id,
        table = table,
        operation = operation,
        createdAt = Instant.fromEpochMilliseconds(createdAtMs),
        rowKeys = rowKeysOf(body, filterJson, primaryKeys.of(table), json),
        body = body,
        base = baseJson?.let { text -> jsonObjectOrNull(text, json) } ?: JsonObject(emptyMap()),
        server = serverRowJson?.let { text -> jsonObjectOrNull(text, json) },
        serverVersion = serverVersion,
        changedOnServer = changedOnServer.toSet(),
        conflictingColumns = conflictingColumns.toSet(),
        message = message,
        conflictedAt = Instant.fromEpochMilliseconds(conflictedAtMs),
    )
}

/**
 * This conflict as a [ConflictResolver] is asked about it.
 *
 * @param json The engine's [Json].
 * @param primaryKeys Where the table's key columns come from, for [MutationConflict.rowKey].
 */
internal fun ConflictCase.toMutationConflict(
    json: Json,
    primaryKeys: PrimaryKeys,
): MutationConflict {
    val filter = decodeFilterOrNull(mutation.filterJson, json)
    return MutationConflict(
        mutationId = mutation.id,
        table = mutation.table,
        operation = mutation.knownOperation ?: MutationOperation.UPDATE,
        rowKey =
            filter?.let { pinnedRowKeyOf(it, primaryKeys.of(mutation.table)) }
                ?: JsonObject(emptyMap()),
        base = base,
        local = local,
        server = (this as? ConflictCase.Moved)?.server,
        changedOnServer = (this as? ConflictCase.Moved)?.changedOnServer.orEmpty(),
        conflictingColumns = (this as? ConflictCase.Moved)?.conflictingColumns.orEmpty(),
    )
}

/** [resolution] as the replay acts on it. */
internal fun conflictDecisionOf(resolution: ConflictResolution): ConflictDecision =
    when (resolution) {
        ConflictResolution.KeepLocal -> ConflictDecision.KeepLocal
        ConflictResolution.KeepServer -> ConflictDecision.KeepServer
        is ConflictResolution.Apply -> ConflictDecision.Apply(resolution.columns)
        ConflictResolution.Hold -> ConflictDecision.Hold
    }

/** [text] as a JSON object; `null` when it is not one. */
private fun jsonObjectOrNull(text: String, json: Json): JsonObject? =
    try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * This persisted rejection as a [RejectedMutation].
 *
 * @param json The engine's [Json].
 * @param primaryKeys Where the table's key columns come from, for [RejectedMutation.rowKeys].
 * @return The rejected mutation. A stored operation no build writes is reported as an update.
 */
internal fun StoredRejection.toRejectedMutation(
    json: Json,
    primaryKeys: PrimaryKeys,
): RejectedMutation {
    val operation = mutationOperationStoredAs(operation) ?: MutationOperation.UPDATE
    val body = bodyOf(operation, bodyJson, json)
    return RejectedMutation(
        id = id,
        table = table,
        operation = operation,
        createdAt = Instant.fromEpochMilliseconds(createdAtMs),
        rowKeys = rowKeysOf(body, filterJson, primaryKeys.of(table), json),
        body = body,
        serverError =
            serverError?.let { stored ->
                ServerError(
                    status = stored.status,
                    code = stored.code,
                    message = stored.message,
                    details = stored.details,
                    hint = stored.hint,
                )
            },
        message = message,
        rejectedAt = Instant.fromEpochMilliseconds(rejectedAtMs),
        bodyOmittedChars = bodyOmittedChars,
    )
}

/** A DELETE has no body; any other body that no longer parses is reported as absent. */
private fun bodyOf(operation: MutationOperation, bodyJson: String, json: Json): JsonElement? {
    if (operation == MutationOperation.DELETE) return null
    return try {
        json.parseToJsonElement(bodyJson)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * The key values a write touches: one per body row that carries the whole key, or — for a filtered
 * write — the key's `eq` conditions when the filter pins every key column.
 */
private fun rowKeysOf(
    body: JsonElement?,
    filterJson: String?,
    keyColumns: List<String>,
    json: Json,
): List<JsonObject> {
    val fromBody =
        when (body) {
            is JsonArray -> body.mapNotNull { row -> primaryKeyOf(row, keyColumns) }
            else -> emptyList()
        }
    if (fromBody.isNotEmpty()) return fromBody

    val filter = decodeFilterOrNull(filterJson, json) ?: return emptyList()
    return listOfNotNull(pinnedRowKeyOf(filter, keyColumns))
}

/**
 * The one row key [filter] pins: each of [keyColumns] by exactly one `eq` condition of an AND
 * filter, as text. `null` when it pins none.
 */
internal fun pinnedRowKeyOf(filter: FilterDescriptor, keyColumns: List<String>): JsonObject? {
    if (filter.mode != FilterDescriptor.Mode.AND || keyColumns.isEmpty()) return null
    val keyValues = keyColumns.associateWith { column ->
        val condition =
            filter.conditions.singleOrNull { condition ->
                condition.column == column && condition.operatorId == EQ_OPERATOR
            } ?: return null
        JsonPrimitive(condition.value)
    }
    return JsonObject(keyValues)
}

private const val EQ_OPERATOR = "eq"
