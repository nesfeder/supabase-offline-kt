package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.serialization.json.JsonObject

/**
 * Parsed view of a cached SELECT signature's WHERE clause, used to decide whether a
 * newly-inserted row actually belongs in that cached query's result set.
 *
 * Produced by [parseSignatureFilters] from a canonical PostgREST signature (as built by
 * [SignatureBuilder]). The whole point is to let the offline mutation overlay stop patching
 * INSERT/UPSERT rows into cached payloads whose filter excludes them.
 *
 * @property descriptor The AND-combined conditions recovered from the signature's filter params,
 *   or `null` when the signature carries no filter params (an unfiltered query returning every
 *   row).
 * @property exhaustive `true` only when [descriptor] provably captures the *entire* row-membership
 *   predicate — every query param was either a filter condition the admit gate can evaluate without
 *   a false negative (an [ADMIT_EVALUABLE_OPERATOR_IDS] operator) or a pure column projection, and
 *   the query has no ordering/limit/offset/range window that could place a matching row outside the
 *   cached slice. When `false`, callers must fall back to the legacy always-apply behavior; we never
 *   under-apply on a signature we cannot fully model.
 */
internal data class SignatureFilters(
    val descriptor: FilterDescriptor?,
    val exhaustive: Boolean,
) {
    /**
     * Whether [rowObject] should be admitted into a cached payload governed by this signature.
     *
     * Conservative by construction:
     * - a non-[exhaustive] signature admits everything (preserve the legacy behavior rather than
     *   risk hiding a row the server would return),
     * - an [exhaustive] signature with a `null` [descriptor] is an unfiltered query and admits
     *   everything,
     * - otherwise the row must satisfy every captured condition via [FilterEngine.matchesRow].
     *
     * @param rowObject The candidate INSERT/UPSERT-appended row.
     * @return `true` if the row may be appended to the cached payload for this signature.
     */
    fun admits(rowObject: JsonObject): Boolean {
        if (!exhaustive) return true
        val activeDescriptor = descriptor ?: return true
        return FilterEngine.matchesRow(rowObject, activeDescriptor)
    }
}

private const val PARAM_SELECT = "select"
private const val PARAM_ORDER = "order"
private const val PARAM_LIMIT = "limit"
private const val PARAM_OFFSET = "offset"
private const val RANGE_MARKER = "|range="

/**
 * Operator identifiers whose [FilterEngine.matchesRow] evaluation the admit gate can trust: for
 * these, `matchesRow` returning `false` reliably means the server would also exclude the row (it
 * over-approximates the server predicate and never yields a false negative). Only a signature whose
 * every captured condition uses one of these keeps [SignatureFilters.exhaustive].
 *
 * Deliberately narrow — the equality operators, which is what cached per-owner and per-key
 * queries typically filter on. Every other recognized [FilterOperator] is excluded because
 * `matchesRow` can drop a row the server would return:
 * - comparison operators (`gt`/`gte`/`lt`/`lte`) evaluate to `false` on non-numeric values such as
 *   ISO timestamps,
 * - pattern operators (`like`/`ilike`/`match`/`imatch`) approximate the pattern with a substring
 *   check that misses interior-`%` matches,
 * - `is` returns `false` for a row that simply omits the null column,
 * - containment / range / overlap / full-text operators (`cs`/`cd`/`ov`/`fts`/…) are answered
 *   `false` unconditionally.
 *
 * Marking any of those non-exhaustive falls back to the legacy always-append behavior, which
 * over-applies rather than hiding a row the server would have returned.
 */
private val ADMIT_EVALUABLE_OPERATOR_IDS: Set<String> = setOf(
    FilterOperator.EQ.identifier,
    FilterOperator.NEQ.identifier,
)

/**
 * Parse a canonical PostgREST SELECT [signature] (as produced by [SignatureBuilder]) into a
 * [SignatureFilters] describing which rows the query admits.
 *
 * The contract is deliberately conservative: over-application is only avoided when it is provably
 * safe, and we never under-apply. A signature is marked [SignatureFilters.exhaustive] **only** when
 * every query param is either a filter condition the admit gate can evaluate without a false
 * negative (an [ADMIT_EVALUABLE_OPERATOR_IDS] operator, interpreted exactly as
 * [buildDescriptorFromBuilder] does) or a pure column projection (`select`), and there is:
 * - no `or(...)`/`and(...)` logical group,
 * - no dotted / foreign-table key,
 * - no `not.`-negated, unrecognized, or non-[ADMIT_EVALUABLE_OPERATOR_IDS] operator (a comparison,
 *   pattern, containment, range, overlap, or full-text predicate [FilterEngine.matchesRow] cannot
 *   faithfully reproduce offline),
 * - no ordering (`order`), truncation (`limit`), pagination (`offset`), or positional `range` window.
 *
 * Anything less yields a non-exhaustive result (or `null` for blank input), which callers treat as
 * "apply as before" so a row the server would have returned is never hidden offline.
 *
 * Signature shape: `table?key=op.value&key=op.value&order=...|range=start-end`. The `table?` prefix
 * and `|range=...` suffix are stripped; the remaining query is split on `&`.
 *
 * @param signature The canonical signature string.
 * @return A [SignatureFilters], or `null` when [signature] is blank or cannot be parsed.
 */
internal fun parseSignatureFilters(signature: String): SignatureFilters? {
    if (signature.isBlank()) return null

    return runCatching {
        // A "|range=start-end" suffix means the cached payload is a positional window, so a new
        // matching row might belong to a different slice — never claim exhaustiveness for it.
        val hasRange = signature.contains(RANGE_MARKER)
        val withoutRange = signature.substringBefore(RANGE_MARKER)

        // Everything after the first '?' is the query string; no '?' means an unfiltered query.
        val questionIndex = withoutRange.indexOf('?')
        val queryString = if (questionIndex < 0) "" else withoutRange.substring(questionIndex + 1)

        val conditions = mutableListOf<FilterDescriptor.Condition>()
        var exhaustive = true
        var windowed = false

        for (token in queryString.split('&')) {
            if (token.isEmpty()) continue

            val separatorIndex = token.indexOf('=')
            if (separatorIndex < 0) {
                // A bare param with no value — can't interpret it, so don't claim exhaustiveness.
                exhaustive = false
                continue
            }

            val key = token.substring(0, separatorIndex)
            val rawParamValue = token.substring(separatorIndex + 1)

            when {
                // Column projection only; it never changes which rows are returned.
                key == PARAM_SELECT -> Unit

                // Ordering / truncation / pagination all produce a positional window in which a new
                // matching row's membership can't be determined offline.
                key == PARAM_ORDER || key == PARAM_LIMIT || key == PARAM_OFFSET -> windowed = true

                // Logical groups like or=(...)/and=(...) are not modeled offline.
                key.contains('(') || key.contains(')') ||
                    rawParamValue.contains('(') || rawParamValue.contains(')') -> exhaustive = false

                // Dotted / foreign-table keys are not modeled offline.
                key.contains('.') -> exhaustive = false

                else -> {
                    val condition = parseCondition(key, rawParamValue)
                    when {
                        condition == null ->
                            // Unrecognized operator (this also covers `not.`-negation) or a value
                            // with no `op.` prefix — can't model it.
                            exhaustive = false

                        condition.operatorId !in ADMIT_EVALUABLE_OPERATOR_IDS ->
                            // Recognized but not one the admit gate can evaluate without risking a
                            // false negative (comparison on a timestamp, interior-`%` pattern,
                            // containment/range/overlap/full-text). Fall back to over-applying.
                            exhaustive = false

                        else -> conditions += condition
                    }
                }
            }
        }

        val descriptor = if (conditions.isEmpty()) {
            null
        } else {
            FilterDescriptor(mode = FilterDescriptor.Mode.AND, conditions = conditions)
        }

        SignatureFilters(
            descriptor = descriptor,
            exhaustive = exhaustive && !windowed && !hasRange,
        )
    }.getOrNull()
}

/**
 * Interpret a single `key=op.value` param exactly as [buildDescriptorFromBuilder] does: the value
 * must be `operator.operand` where `operator` is a recognized [FilterOperator].
 *
 * @param key The param key (the row column).
 * @param rawParamValue The raw `op.value` param value.
 * @return A [FilterDescriptor.Condition], or `null` when the operator is unrecognized (e.g. a
 *   `not.`-negated param) or the value has no `operator.` prefix — signalling the caller to mark
 *   the whole signature non-exhaustive.
 */
private fun parseCondition(key: String, rawParamValue: String): FilterDescriptor.Condition? {
    val parts = rawParamValue.split('.', limit = 2)
    if (parts.size < 2) return null

    val operatorId = parts[0]
    val operand = parts[1]
    val operator = FilterOperator.entries.firstOrNull { it.identifier == operatorId } ?: return null

    val normalizedValue = operand.trim().trim('"', '\'')
    return FilterDescriptor.Condition(
        column = key,
        operatorId = operator.identifier,
        value = normalizedValue,
    )
}
