// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
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
 */
internal fun MutationRow.toPendingMutation(json: Json, primaryKeys: PrimaryKeys): PendingMutation {
    val body = bodyOf(operation, bodyJson, json)
    return PendingMutation(
        id = id,
        table = table,
        operation = operation,
        createdAt = Instant.fromEpochMilliseconds(createdAtMs),
        rowKeys = rowKeysOf(body, filterJson, primaryKeys.of(table), json),
        body = body,
    )
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
    if (filter.mode != FilterDescriptor.Mode.AND) return emptyList()
    val keyValues = keyColumns.associateWith { column ->
        val condition =
            filter.conditions.singleOrNull { condition ->
                condition.column == column && condition.operatorId == EQ_OPERATOR
            } ?: return emptyList()
        JsonPrimitive(condition.value)
    }
    return listOf(JsonObject(keyValues))
}

private const val EQ_OPERATOR = "eq"
