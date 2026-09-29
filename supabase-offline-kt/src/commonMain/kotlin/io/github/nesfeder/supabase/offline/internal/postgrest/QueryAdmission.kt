package io.github.nesfeder.supabase.offline.internal.postgrest

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Which locally written rows a cached query admits: every row that none of its conditions excludes,
 * as far as [rowMatch] can tell. Order, limit, offset and range never switch that off; a condition
 * the engine does not evaluate never excludes a row.
 *
 * @property conditions the query's conditions that [rowMatch] evaluates, all of which must hold.
 */
internal class QueryAdmission(val conditions: List<FilterDescriptor.Condition>) {

    /** Whether [row] may appear in the query. A row that is not an object cannot be judged and is admitted. */
    fun admits(row: JsonElement): Boolean {
        val rowObject = row as? JsonObject ?: return true
        return conditions.none { condition -> rowMatch(rowObject, condition) == RowMatch.EXCLUDED }
    }

    /** Admissions that need no parsing. */
    companion object {

        /** Admits every row: a query whose filters are not known. */
        val EVERY_ROW: QueryAdmission = QueryAdmission(emptyList())
    }
}

/**
 * The admission of the query cached under [cacheKey] (see [cacheKeyOf]). Only the first value of a
 * column counts, since supabase-kt sends only that one; `select`, `order`, `limit`, `offset` and the
 * range suffix choose columns or a window, never rows.
 */
internal fun admissionOf(cacheKey: String): QueryAdmission {
    val query = cacheKey.substringBefore(RANGE_MARKER).substringAfter('?', missingDelimiterValue = "")
    val firstValues = linkedMapOf<String, String>()
    for (parameter in query.split('&')) {
        val separator = parameter.indexOf('=')
        if (separator <= 0) continue
        firstValues.getOrPut(parameter.substring(0, separator)) { parameter.substring(separator + 1) }
    }
    return QueryAdmission(firstValues.mapNotNull { (column, value) -> evaluatedConditionOf(column, value) })
}

/**
 * The condition a `column=operator.operand` parameter states, when [rowMatch] evaluates it; `null`
 * for anything else: a non-filter parameter, a negation, a logical group or an operator left unevaluated.
 */
private fun evaluatedConditionOf(column: String, value: String): FilterDescriptor.Condition? {
    if (column in WINDOW_AND_PROJECTION_PARAMETERS || !isPlainColumn(column)) return null
    val separator = value.indexOf('.')
    if (separator <= 0) return null
    val operator = offlineFilterOperatorOrNull(value.substring(0, separator)) ?: return null
    if (operator !in EVALUATED_OPERATORS) return null
    return FilterDescriptor.Condition(column = column, operatorId = operator.identifier, value = value.substring(separator + 1))
}

/** Where [cacheKeyOf] appends a `Range` header's window. */
private const val RANGE_MARKER = "|range="

/** Query parameters that pick the columns or the window of a result, never which rows qualify. */
private val WINDOW_AND_PROJECTION_PARAMETERS: Set<String> = setOf("select", "order", "limit", "offset")
