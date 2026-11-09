// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder

/**
 * Extract a best-effort [FilterDescriptor] from a [PostgrestRequestBuilder].
 *
 * We only handle the simple, common case:
 *
 * column -> ["eq.value"]
 *
 * via PostgrestFilterBuilder’s `eq("column", value)` etc. Complex DSL usage (`and(...)`, `or(...)`,
 * foreign-table filters) is intentionally ignored so we don’t risk wrong offline behavior.
 */
internal fun captureFilter(
    defaultSchema: String,
    conversionMethod: PropertyConversionMethod,
    request: PostgrestRequestBuilder.() -> Unit,
): FilterDescriptor? {
    // PostgrestRequestBuilder is abstract since supabase-kt 3.6.0; SelectRequestBuilder is the
    // concrete variant whose construction only populates `params`, which is all the descriptor
    // extraction reads.
    val builder = SelectRequestBuilder(defaultSchema, conversionMethod).apply(request)
    return captureFilter(builder)
}

internal fun captureFilter(builder: PostgrestRequestBuilder): FilterDescriptor? {
    val conditions = mutableListOf<FilterDescriptor.Condition>()

    builder.params.forEach { (key, values) ->
        // Skip special / nested keys (e.g. "and(...)", "or(...)", "table.column").
        if (key.contains("(") || key.contains(")") || key.contains(".")) return@forEach

        // supabase-kt sends every value of a parameter and PostgREST ANDs them, so each value is
        // its own condition, in the order the request holds them
        values.forEach eachValue@{ value ->
            // Expect something like "eq.value" or "eq.123"
            val parts = value.split('.', limit = 2)
            if (parts.size < 2) return@eachValue

            val operatorIdentifier = parts[0]
            val rawValue = parts[1]

            val operator = filterOperatorOrNull(operatorIdentifier) ?: return@eachValue

            // Stored exactly as encoded: trimming whitespace or quotes would replay (and overlay)
            // a different value than the caller wrote.
            conditions +=
                FilterDescriptor.Condition(
                    column = key,
                    operatorId = operator.identifier,
                    value = rawValue,
                )
        }
    }

    if (conditions.isEmpty()) return null
    return FilterDescriptor(
        mode = FilterDescriptor.Mode.AND,
        conditions = conditions,
    )
}

/**
 * Thrown by [captureFilterExactly] when a queued UPDATE/DELETE carries a filter expression the
 * offline descriptor format cannot represent faithfully — a logical group (`or(...)` / `and(...)`),
 * a foreign-table dotted key, or a negated/unrecognized operator prefix such as `not.`.
 *
 * [OfflineTable] catches it and sends the write directly, instead of queueing a mutation that would
 * later replay against a broader row set than the caller targeted.
 */
internal class UncapturableFilterException(message: String) : IllegalArgumentException(message)

/**
 * PostgREST query parameters that [captureFilterExactly] must not read as filter conditions.
 *
 * Exactly one qualifies. `select` shapes the *representation a mutation returns* and nothing else:
 * on a PATCH or DELETE it neither chooses rows nor changes what is written, so a descriptor that
 * omits it replays the identical mutation.
 *
 * Everything else stays fail-closed, and for two distinct reasons — neither of which is "it is not
 * a filter":
 * - `limit` / `offset` / `order` narrow which **rows** the mutation reaches. Dropping one replays
 *   against more rows than the caller wrote.
 * - `columns` narrows which **columns** of the body the server applies. The descriptor cannot carry
 *   it, so dropping it would replay a wider write than the caller made — the same "wrote more than
 *   was asked for" failure, one axis over. (Today it is unreachable here: supabase-kt's
 *   `PostgrestRequestBuilder` only exposes it on inserts, which never take this path. Listing it as
 *   a skip would be a fail-open exemption waiting for that to change.)
 */
private val NON_FILTER_PARAMS: Set<String> = setOf("select")

/**
 * Strict counterpart to [captureFilter], used on the **replay** path (queued UPDATE / DELETE) where
 * silently dropping an un-capturable condition would replay the mutation against more rows than the
 * caller intended.
 *
 * Captures the simple `column -> "op.value"` shape exactly like [captureFilter], but instead of
 * skipping anything it cannot represent it throws [UncapturableFilterException] when
 * [PostgrestRequestBuilder.params] contains:
 * - a key with `(`, `)` or `.` — a logical group (`or(...)` / `and(...)`) or a foreign-table dotted
 *   key; neither is representable in the flat offline descriptor
 * - a value whose operator prefix is not a known [FilterOperator] identifier — this covers
 *   `not.`-negated operators and any group expression encoded into the value
 *
 * Returns `null` only for a genuinely filter-free request (no capturable params), mirroring
 * [captureFilter]'s empty-conditions contract.
 *
 * Every value of a column is captured, one condition per value, in the order the request holds
 * them. supabase-kt sends each value of a query parameter (`age=gte.1&age=lte.10` for two filters
 * on `age`) and PostgREST ANDs them, so a descriptor that kept only the first would replay the
 * write against more rows than the caller named.
 *
 * @param builder request builder whose [PostgrestRequestBuilder.params] describe the target rows.
 * @return a faithful [FilterDescriptor], or `null` when no filter conditions are present.
 * @throws UncapturableFilterException when a condition cannot be captured without broadening.
 */
internal fun captureFilterExactly(builder: PostgrestRequestBuilder): FilterDescriptor? {
    val conditions = mutableListOf<FilterDescriptor.Condition>()

    builder.params.forEach { (key, values) ->
        // `select` only shapes the representation the mutation returns, so a descriptor that omits
        // it replays the identical write. It has to be recognized explicitly because its value is
        // not `op.value` shaped (it carries "*"), and the operator check below would otherwise
        // reject an ordinary `update { filter { … }; select() }` — refusing to queue it, which
        // fails the user's offline write outright. Every other parameter keeps falling through to
        // the throw; see [NON_FILTER_PARAMS] for why that list has exactly one member.
        if (key in NON_FILTER_PARAMS) return@forEach

        if (key.contains('(') || key.contains(')') || key.contains('.')) {
            throw UncapturableFilterException(
                "cannot faithfully capture offline filter for key '$key': logical groups and " +
                    "foreign-table keys are not representable in the offline descriptor"
            )
        }

        // Every value is sent and ANDed, so every value is captured: dropping one would replay the
        // write against more rows than the caller named
        values.forEach { value ->
            val parts = value.split('.', limit = 2)
            val operatorId = parts.firstOrNull()
            val operator = operatorId?.let { candidate -> filterOperatorOrNull(candidate) }
            if (operator == null || parts.size < 2) {
                throw UncapturableFilterException(
                    "cannot faithfully capture offline filter for key '$key': unrecognized " +
                        "operator in '$value' (negated 'not.' operators and group expressions " +
                        "are not supported)"
                )
            }

            // Stored exactly as encoded, so replay sends the very value the caller's request did.
            conditions +=
                FilterDescriptor.Condition(
                    column = key,
                    operatorId = operator.identifier,
                    value = parts[1],
                )
        }
    }

    if (conditions.isEmpty()) return null
    return FilterDescriptor(
        mode = FilterDescriptor.Mode.AND,
        conditions = conditions,
    )
}

/**
 * Convenience wrapper mirroring [captureFilter] but delegating to [captureFilterExactly], so
 * offline-queue callers can build the request the same way they do online and receive strict
 * (throwing) capture semantics.
 *
 * @param defaultSchema schema used to construct the concrete request builder.
 * @param conversionMethod property-name conversion mirrored from the live PostgREST config.
 * @param request the caller's request-configuration block (typically a `filter { … }`).
 * @return a faithful [FilterDescriptor], or `null` when no filter conditions are present.
 * @throws UncapturableFilterException when a condition cannot be captured without broadening.
 */
internal fun captureFilterExactly(
    defaultSchema: String,
    conversionMethod: PropertyConversionMethod,
    request: PostgrestRequestBuilder.() -> Unit,
): FilterDescriptor? {
    val builder = SelectRequestBuilder(defaultSchema, conversionMethod).apply(request)
    return captureFilterExactly(builder)
}
