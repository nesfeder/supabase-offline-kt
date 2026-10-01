// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.serialization.Serializable

/**
 * Serializable description of a PostgREST filter expression.
 *
 * This is the compact form we store alongside offline mutations so we can:
 * - apply them to local JSON rows (offline overlay)
 * - replay them into a real PostgrestRequestBuilder when syncing UPDATE/DELETE
 */
@Serializable
internal data class FilterDescriptor(
    /**
     * How conditions are combined. AND is the common case; OR uses PostgREST logical expressions.
     */
    val mode: Mode = Mode.AND,
    val conditions: List<Condition>,
) {
    @Serializable
    enum class Mode {
        AND,
        OR,
    }

    /** A single condition like: `eq("id", "123")` or `ilike("name", "%foo%")`. */
    @Serializable
    data class Condition(
        val column: String,
        /** FilterOperator.identifier, e.g. "eq", "lt", "ilike" */
        val operatorId: String,
        /** Raw value as string (we’ll coerce heuristically when comparing / replaying) */
        val value: String,
    ) {
        val operator: FilterOperator?
            get() = filterOperatorOrNull(operatorId)
    }
}

/**
 * Resolves a PostgREST operator [identifier] to the [FilterOperator] it names, or `null` when it
 * names none.
 *
 * Every capture path and the stored-condition lookup resolve through here. Resolving an operator
 * does not mean the engine can evaluate it locally: [rowMatch] evaluates only a few, while
 * [FilterEngine.applyTo] replays every one of them faithfully.
 *
 * @param identifier The operator prefix of a PostgREST filter value, e.g. `"eq"`.
 * @return The operator, or `null` when the identifier is unknown (including a `not.` negation).
 */
internal fun filterOperatorOrNull(identifier: String): FilterOperator? =
    OPERATORS_BY_IDENTIFIER[identifier]

/** Every [FilterOperator] by its identifier, so a lookup per row and condition is one hash. */
private val OPERATORS_BY_IDENTIFIER: Map<String, FilterOperator> =
    FilterOperator.entries.associateBy { operator -> operator.identifier }
