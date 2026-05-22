package io.github.nesfeder.supabase.internal.postgrest.parser

/**
 * Parses PostgREST `order` query param values into [OrderClause] lists.
 */
internal object OrderClauseParser {

    /**
     * @return Parsed clauses, or an empty list when the param is absent or blank; `null` when a non-blank
     * value cannot be interpreted (caller should treat as complex params).
     */
    fun fromRaw(orderParamValues: List<String>?): List<OrderClause>? {
        val raw = orderParamValues?.firstOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return parsePostgrestOrderString(raw)
    }

    /**
     * Parses a PostgREST `order` param value into a list of [OrderClause]s.
     *
     * Each comma-separated segment must be one of:
     *  - `column.asc` / `column.desc` — nulls default to last
     *  - `column.asc.nullsfirst` / `column.desc.nullslast` — explicit null placement
     *
     * Returns `null` if any segment cannot be parsed, signaling that the caller should treat
     * the whole `order` param as uninterpretable.
     */
    private fun parsePostgrestOrderString(raw: String): List<OrderClause>? {
        val segments = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return segments.map { segment ->
            parseOrderClauseSegment(segment) ?: return null
        }
    }

    /**
     * Parses one segment of a PostgREST `order` param value into an [OrderClause].
     *
     * E.g.
     * - `updated_at.desc` -> OrderClause("updated_at", DESC, NULLS_LAST)
     * - `name.asc.nullsfirst` -> OrderClause("name", ASC, NULLS_FIRST)
     */
    private fun parseOrderClauseSegment(segment: String): OrderClause? {
        val parts = segment.split('.')
        // Must have at least column and direction; nulls order is optional. More than 3 parts is invalid.
        if (parts.size !in 2..3) return null
        if (parts.any { it.isEmpty() }) return null

        val column = parts[0]
        val direction = OrderDirection.parseOrNull(parts[1]) ?: return null
        val nullsOrder = parts.getOrNull(2)?.let { token ->
            NullsOrder.parseOrNull(token) ?: return null
        } ?: NullsOrder.NULLS_LAST

        return OrderClause(
            column = column,
            direction = direction,
            nullsOrder = nullsOrder
        )
    }
}

/**
 * One segment of a PostgREST `order` query param, e.g. `updated_at.desc.nullslast`.
 *
 * Matches the shape produced by supabase-kt's `SelectRequestBuilder.order`.
 */
internal data class OrderClause(
    val column: String,
    val direction: OrderDirection,
    val nullsOrder: NullsOrder,
)

/** Sort direction for one segment of a PostgREST `order` param (`asc` / `desc`). */
internal enum class OrderDirection(val rawValue: String) {
    ASC("asc"),
    DESC("desc");

    companion object {
        fun parseOrNull(segment: String): OrderDirection? {
            val key = segment.lowercase()
            return entries.firstOrNull { it.rawValue == key }
        }
    }
}

/** Null ordering for one segment of a PostgREST `order` param (`nullsfirst` / `nullslast`). */
internal enum class NullsOrder(val rawValue: String) {
    NULLS_FIRST("nullsfirst"),
    NULLS_LAST("nullslast");

    companion object {
        fun parseOrNull(segment: String): NullsOrder? {
            val key = segment.lowercase()
            return entries.firstOrNull { it.rawValue == key }
        }
    }
}
