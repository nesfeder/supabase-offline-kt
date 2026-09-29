// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.NumericDeltaColumn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Serializer for the [MutationRow.deltaJson] payload — a plain list of declarations. */
private val DeltaColumnsSerializer = ListSerializer(NumericDeltaColumn.serializer())

/**
 * Encodes [columns] for storage in [MutationRow.deltaJson], or `null` when nothing is declared so
 * an ordinary UPDATE keeps writing a `NULL` column.
 *
 * @param columns The delta declarations attached to the mutation.
 * @param json The store's [Json] instance.
 * @return The encoded declarations, or `null` when [columns] is empty.
 */
internal fun encodeNumericDeltaColumns(
    columns: List<NumericDeltaColumn>,
    json: Json,
): String? = if (columns.isEmpty()) null else json.encodeToString(DeltaColumnsSerializer, columns)

/**
 * Decodes [MutationRow.deltaJson] back into its declarations.
 *
 * A corrupted or unreadable value resolves to "no declarations", which degrades the replay to the
 * absolute write it would have been before delta encoding existed — strictly the pre-existing
 * behaviour, never a broadened or skipped write. This is local queue metadata, not a server
 * payload: there is no schema upstream to be a signal about, and refusing to replay the user's
 * queued edit over it would lose the write outright.
 *
 * @param deltaJson The stored value, or `null` for an ordinary UPDATE.
 * @param json The store's [Json] instance.
 * @return The declared delta columns, empty when none are stored or the value cannot be read.
 */
internal fun decodeNumericDeltaColumns(
    deltaJson: String?,
    json: Json,
): List<NumericDeltaColumn> {
    if (deltaJson.isNullOrBlank()) return emptyList()
    return try {
        json.decodeFromString(DeltaColumnsSerializer, deltaJson)
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
}

/**
 * Rebases the declared delta columns of an UPDATE [body] onto [serverRow], the row as the server
 * holds it right now.
 *
 * For each declaration whose column is present in both the body and [serverRow] as a number, the
 * written value becomes `serverCurrent + (desired - snapshot)`: whatever moved the column between
 * enqueue and replay survives, and the caller's own change still applies on top. Every other key of
 * the body is passed through untouched, so a delta-declared UPDATE is still an ordinary PATCH of
 * exactly the columns its author wrote.
 *
 * A declaration is skipped — leaving the absolute value the body carries — when the column is
 * absent from the body, absent from [serverRow], or non-numeric on either side. That is the
 * pre-delta behaviour, so an unexpected shape degrades rather than dropping the write.
 *
 * A rebased value is clamped to [NumericDeltaColumn.minValue] when one is declared: the rebase
 * measures the caller's intent against a number the caller never saw, so a downward intent can
 * carry a column below its domain if the server moved down too. Clamping keeps the write inside
 * what the column can hold; the absolute body, which states a value the caller actually computed,
 * is never clamped.
 *
 * The rebased value keeps the form the caller wrote. When the body wrote the column as an integer
 * and the snapshot, the server's value and the floor are whole numbers, the arithmetic is done in
 * [Long] and the body gets an integer back: `13`, never `13.0`, which Postgres refuses for an `int`
 * column. Doing it in [Long] also keeps a `bigint` exact beyond 2^53. Otherwise the value is
 * computed and written as a [Double], as before.
 *
 * @param body The stored UPDATE body.
 * @param serverRow The freshly re-read row the UPDATE targets.
 * @param deltaColumns The declarations attached to the mutation.
 * @return The body to send, or [body] itself when nothing could be rebased.
 */
internal fun rebaseNumericDeltas(
    body: JsonElement,
    serverRow: JsonObject,
    deltaColumns: List<NumericDeltaColumn>,
): JsonElement {
    if (deltaColumns.isEmpty()) return body
    val bodyObject = body as? JsonObject ?: return body

    val rebased = bodyObject.toMutableMap()
    var changed = false
    for (declaration in deltaColumns) {
        val value =
            rebaseIntegral(bodyObject, serverRow, declaration)
                ?: rebaseDecimal(bodyObject, serverRow, declaration)
                ?: continue
        if (value != bodyObject[declaration.column]) {
            rebased[declaration.column] = value
            changed = true
        }
    }
    return if (changed) JsonObject(rebased) else body
}

/**
 * The rebase in [Long] arithmetic, when the caller wrote an integer and every other input is whole.
 *
 * @return The rebased integer, or `null` when the column does not qualify.
 */
private fun rebaseIntegral(
    body: JsonObject,
    serverRow: JsonObject,
    declaration: NumericDeltaColumn,
): JsonPrimitive? {
    val desired = body.integerLiteralOrNull(declaration.column) ?: return null
    val serverCurrent = serverRow.wholeNumberOrNull(declaration.column) ?: return null
    val snapshot = declaration.snapshotValue.toWholeLongOrNull() ?: return null
    val floor =
        declaration.minValue?.let { minValue -> minValue.toWholeLongOrNull() ?: return null }
    val onServerBase = serverCurrent + (desired - snapshot)
    return JsonPrimitive(if (floor == null) onServerBase else onServerBase.coerceAtLeast(floor))
}

/**
 * The rebase in [Double] arithmetic.
 *
 * @return The rebased value, or `null` when the column is absent or non-numeric on either side.
 */
private fun rebaseDecimal(
    body: JsonObject,
    serverRow: JsonObject,
    declaration: NumericDeltaColumn,
): JsonPrimitive? {
    val desired = body.numberOrNull(declaration.column) ?: return null
    val serverCurrent = serverRow.numberOrNull(declaration.column) ?: return null
    val onServerBase = serverCurrent + (desired - declaration.snapshotValue)
    val value =
        declaration.minValue?.let { floor -> onServerBase.coerceAtLeast(floor) } ?: onServerBase
    return if (value == desired) body[declaration.column] as JsonPrimitive else JsonPrimitive(value)
}

/** The numeric value of [key], or `null` when it is absent, null, or not a number. */
private fun JsonObject.numberOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString.not() }?.doubleOrNull

/** The value of [key] when it is written as an integer literal (no fraction, no exponent). */
private fun JsonObject.integerLiteralOrNull(key: String): Long? {
    val primitive = (this[key] as? JsonPrimitive)?.takeIf { it.isString.not() } ?: return null
    return primitive.content.toLongOrNull()
}

/** The value of [key] when it is a whole number, however it is written (`12` or `12.0`). */
private fun JsonObject.wholeNumberOrNull(key: String): Long? {
    val primitive = (this[key] as? JsonPrimitive)?.takeIf { it.isString.not() } ?: return null
    return primitive.content.toLongOrNull() ?: primitive.doubleOrNull?.toWholeLongOrNull()
}

/** This value as a [Long] when it is a whole number a [Long] holds exactly, else `null`. */
private fun Double.toWholeLongOrNull(): Long? {
    if (isNaN() || isInfinite() || this % 1.0 != 0.0) return null
    if (this < Long.MIN_VALUE.toDouble() || this >= Long.MAX_VALUE.toDouble()) return null
    return toLong()
}
