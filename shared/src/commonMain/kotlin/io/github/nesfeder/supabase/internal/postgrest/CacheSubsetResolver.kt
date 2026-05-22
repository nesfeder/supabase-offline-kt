package io.github.nesfeder.supabase.internal.postgrest

import io.github.nesfeder.supabase.internal.postgrest.parser.NullsOrder
import io.github.nesfeder.supabase.internal.postgrest.parser.OrderClause
import io.github.nesfeder.supabase.internal.postgrest.parser.OrderDirection
import io.github.nesfeder.supabase.internal.postgrest.parser.SelectQueryMetadata
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull

/**
 * True if this metadata (broader cached query) can satisfy [requested] (narrower) via in-memory filter/sort.
 *
 * Requires:
 * - Same table as [requested]
 * - `supportsSubsetDerivation` is true for both this metadata and [requested]
 * - Columns: this is [Columns.ALL], or column lists match exactly
 * - Every filter on this query appears on [requested] (cached rows are a superset)
 * - Sort output using the order clauses from [requested] in memory; ignore cached row order
 */
internal fun SelectQueryMetadata.canProvideSubsetFor(requested: SelectQueryMetadata): Boolean {
    if (table != requested.table) return false
    if (!supportsSubsetDerivation || !requested.supportsSubsetDerivation) return false

    if (columns.value != Columns.ALL.value && columns.value != requested.columns.value) return false

    val candidateConditions = filterDescriptor?.conditions ?: emptyList()
    val requestedConditions = requested.filterDescriptor?.conditions ?: emptyList()

    // Requested must be at least as restrictive as candidate (else cache could be too small).
    return requestedConditions.containsAll(candidateConditions)
}

/**
 * Derives the cached row payload for a narrower SELECT ([requestedMetadata]) from a broader cached result
 * ([candidatePayload], [candidateMetadata]) so the request can be served from cache alone.
 *
 * Returns `null` if the payload cannot be parsed or the candidate metadata is not actually a superset
 * of the requested metadata.
 */
internal fun deriveSubsetPayload(
    candidatePayload: String,
    candidateMetadata: SelectQueryMetadata,
    requestedMetadata: SelectQueryMetadata,
    json: Json
): String? {
    val array = runCatching {
        json.parseToJsonElement(candidatePayload).jsonArray
    }.getOrNull() ?: return null

    val elements = array.filterIsInstance<JsonObject>()

    val extraConditions =
        (requestedMetadata.filterDescriptor?.conditions ?: emptyList()) -
            (candidateMetadata.filterDescriptor?.conditions ?: emptyList()).toSet()

    val filtered = if (extraConditions.isEmpty()) {
        elements
    } else {
        val extraDescriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = extraConditions
        )
        elements.filter { obj ->
            FilterEngine.matchesRow(obj, extraDescriptor)
        }
    }

    val ordered = if (requestedMetadata.orderClauses.isEmpty()) {
        filtered
    } else {
        filtered.sortedWith(jsonObjectComparator(requestedMetadata.orderClauses))
    }

    return JsonArray(ordered).toString()
}

private fun jsonObjectComparator(clauses: List<OrderClause>): Comparator<JsonObject> {
    return Comparator { leftRow, rightRow ->
        compareJsonObjectsForOrder(leftRow, rightRow, clauses)
    }
}

/**
 * Compares two [JsonObject]s according to the provided [orderClauses].
 * 
 * This is an in-memory implementation of the ordering that PostgREST would apply to query
 * results based on the `order` param.
 */
internal fun compareJsonObjectsForOrder(
    leftRow: JsonObject,
    rightRow: JsonObject,
    orderClauses: List<OrderClause>
): Int {
    orderClauses.forEach { clause ->
        val leftValue = leftRow[clause.column]
        val rightValue = rightRow[clause.column]

        val ascendingComparison = leftValue.compareTo(rightValue, clause.nullsOrder)

        val orderedComparison = when (clause.direction) {
            OrderDirection.ASC -> ascendingComparison
            OrderDirection.DESC -> -ascendingComparison
        }
        if (orderedComparison != 0) return orderedComparison
    }
    return 0
}

/**
 * Compares two [JsonElement]s for ordering, treating `null` and `JsonNull` as equal and according to [nullsOrder].
 */
internal fun JsonElement?.compareTo(other: JsonElement?, nullsOrder: NullsOrder): Int {
    val left = this
    val right = other
    val nullsFirst = nullsOrder == NullsOrder.NULLS_FIRST

    val leftIsNull = left == null || left is JsonNull
    val rightIsNull = right == null || right is JsonNull
    when {
        leftIsNull && rightIsNull -> return 0
        leftIsNull -> return if (nullsFirst) -1 else 1
        rightIsNull -> return if (nullsFirst) 1 else -1
    }

    val leftPrimitive = left as? JsonPrimitive
    val rightPrimitive = right as? JsonPrimitive
    if (leftPrimitive == null || rightPrimitive == null) {
        val leftFallback = left.toString()
        val rightFallback = right.toString()
        return leftFallback.compareTo(rightFallback)
    }

    val leftAsLong = leftPrimitive.longOrNull
    val rightAsLong = rightPrimitive.longOrNull
    if (leftAsLong != null && rightAsLong != null) {
        return leftAsLong.compareTo(rightAsLong)
    }

    val leftAsDouble = leftPrimitive.doubleOrNull
    val rightAsDouble = rightPrimitive.doubleOrNull
    if (leftAsDouble != null && rightAsDouble != null) {
        return leftAsDouble.compareTo(rightAsDouble)
    }

    val leftAsBoolean = leftPrimitive.booleanOrNull
    val rightAsBoolean = rightPrimitive.booleanOrNull
    if (leftAsBoolean != null && rightAsBoolean != null) {
        return leftAsBoolean.compareTo(rightAsBoolean)
    }

    val leftText = leftPrimitive.contentOrNull ?: leftPrimitive.toString()
    val rightText = rightPrimitive.contentOrNull ?: rightPrimitive.toString()
    return leftText.compareTo(rightText)
}
