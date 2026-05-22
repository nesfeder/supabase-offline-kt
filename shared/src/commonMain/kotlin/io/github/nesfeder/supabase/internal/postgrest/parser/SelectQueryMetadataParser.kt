@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.internal.postgrest.parser

import io.github.nesfeder.supabase.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.internal.postgrest.SignatureBuilder
import io.github.nesfeder.supabase.internal.postgrest.buildDescriptorFromBuilder
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder

/**
 * Parsed summary of a SELECT request's cache-relevant properties.
 *
 * Used to determine whether a broader cached query can safely provide rows for a
 * narrower query via in-memory filtering, without touching the network.
 */
internal data class SelectQueryMetadata(
    val table: String,
    val columns: Columns,
    /**
     * Simple AND filter conditions extracted from the request, or null if there is no WHERE clause.
     */
    val filterDescriptor: FilterDescriptor?,
    /**
     * True when the query uses an HTTP Range header (or persisted `|range=` in a cache signature).
     */
    val hasRange: Boolean,
    /**
     * True when the query has `limit` or `offset` query params.
     */
    val hasPagination: Boolean,
    /**
     * Parsed ORDER BY clauses from the `order` param.
     *
     * - Empty when no `order` param is present.
     * - Empty (and [hasComplexParams] = true) when the `order` param was present but could not be parsed.
     * - Non-empty when the param was present and successfully parsed.
     *
     * Subset derivation applies this ordering in memory after filtering.
     */
    val orderClauses: List<OrderClause>,
    /**
     * True when the request contains params that the SDK cannot fully interpret
     * (OR groups, nested/foreign-table filters, unknown operators, malformed order, etc.).
     */
    val hasComplexParams: Boolean,
    val signature: String
) {

    /**
     * True when this query has no range, no limit/offset pagination, and no uninterpretable params,
     * so it can participate in in-memory subset derivation (as cached superset or as the narrower request).
     */
    val supportsSubsetDerivation: Boolean
        get() = !hasComplexParams && !hasRange && !hasPagination
}

internal object SelectQueryMetadataParser {

    private const val PARAM_SELECT = "select"
    private const val PARAM_ORDER = "order"
    private const val PARAM_LIMIT = "limit"
    private const val PARAM_OFFSET = "offset"

    private val SELECT_META_PARAM_KEYS = setOf(
        PARAM_SELECT,
        PARAM_ORDER,
        PARAM_LIMIT,
        PARAM_OFFSET
    )

    private val PAGINATION_PARAM_KEYS = setOf(
        PARAM_LIMIT,
        PARAM_OFFSET
    )

    /**
     * Builds metadata by applying [request] against a temporary [SelectRequestBuilder].
     *
     * The [request] lambda is applied twice (once here and once inside [SignatureBuilder.build]).
     * It must be side-effect-free — standard PostgREST DSL lambdas always are.
     */
    fun fromRequestBuilder(
        table: String,
        columns: Columns,
        propertyConversionMethod: PropertyConversionMethod,
        request: SelectRequestBuilder.() -> Unit,
    ): SelectQueryMetadata {
        val builder = SelectRequestBuilder(propertyConversionMethod).apply(request)
        val signature = SignatureBuilder.build(
            table = table,
            columns = columns,
            builderFactory = { SelectRequestBuilder(propertyConversionMethod) },
            request = request
        )

        val params = builder.params
        val filterDescriptor = buildDescriptorFromBuilder(builder)
        val rangeHeader = builder.headers.build()["Range"]

        val hasRange = rangeHeader != null
        val hasPagination = hasPaginationParams(params)

        // Any param key not captured by the filter descriptor and not a known meta key is "complex".
        val capturedKeys = filterDescriptor?.conditions?.map { it.column }?.toSet() ?: emptySet()
        var hasComplexParams = params.keys.any { key ->
            !isSelectMetaParamKey(key) && key !in capturedKeys
        }

        val orderClauses = OrderClauseParser.fromRaw(params[PARAM_ORDER])
        if (orderClauses == null) {
            hasComplexParams = true
        }

        return SelectQueryMetadata(
            table = table,
            columns = columns,
            filterDescriptor = filterDescriptor,
            hasRange = hasRange,
            hasPagination = hasPagination,
            orderClauses = orderClauses.orEmpty(),
            hasComplexParams = hasComplexParams,
            signature = signature
        )
    }

    /**
     * Reconstructs metadata from a stored [signature] string, without the original [SelectRequestBuilder] lambda.
     *
     * The signature format produced by [SignatureBuilder] is:
     * ```
     * table?key=op.value&key2=op2.value2|range=start-end
     * ```
     *
     * Used to evaluate candidate cache rows (where only the signature string is persisted).
     */
    fun fromSignature(table: String, signature: String): SelectQueryMetadata {
        val afterTable = signature.removePrefix(table)

        // Split off the |range=... suffix
        val rangeIndex = afterTable.indexOf("|range=")
        val hasRange = rangeIndex >= 0
        val queryPart = if (rangeIndex >= 0) {
            afterTable.substring(0, rangeIndex)
        } else {
            afterTable
        }

        val params = parseQueryStringParams(queryPart.removePrefix("?"))
        val hasPagination = hasPaginationParams(params)

        // Re-derive Columns from the "select" param; absent = Columns.ALL
        val selectValue = params[PARAM_SELECT]?.firstOrNull()
        val columns = if (selectValue != null) {
            Columns.list(*selectValue.split(",").toTypedArray())
        } else {
            Columns.ALL
        }

        // Reconstruct filter conditions, mirroring the logic in buildDescriptorFromBuilder
        val conditions = mutableListOf<FilterDescriptor.Condition>()
        var hasComplexParams = false

        params.forEach { (key, values) ->
            if (isSelectMetaParamKey(key)) return@forEach
            if (values.size > 1) {
                hasComplexParams = true
                return@forEach
            }
            // OR/AND groups or nested/foreign-table params have special key syntax
            if (key.contains("(") || key.contains(")") || key.contains(".")) {
                hasComplexParams = true
                return@forEach
            }

            val first = values.firstOrNull() ?: return@forEach
            val parts = first.split('.', limit = 2)
            if (parts.size < 2) {
                hasComplexParams = true
                return@forEach
            }

            val opIdentifier = parts[0]
            val rawValue = parts[1].trim().trim('"', '\'')
            if (FilterOperator.entries.none { it.identifier == opIdentifier }) {
                // Unknown operator (e.g. malformed param) is treated as complex since we can't
                // be sure how to interpret it.
                hasComplexParams = true
                return@forEach
            }
            conditions += FilterDescriptor.Condition(
                column = key,
                operatorId = opIdentifier,
                value = rawValue
            )
        }

        val filterDescriptor = if (conditions.isNotEmpty()) {
            // The signature format only captures simple AND conditions, so we can safely
            // reconstruct a descriptor if we found any conditions.
            FilterDescriptor(
                mode = FilterDescriptor.Mode.AND,
                conditions = conditions
            )
        } else {
            null
        }

        val orderClausesResult = OrderClauseParser.fromRaw(params[PARAM_ORDER])
        if (orderClausesResult == null) {
            hasComplexParams = true
        }

        return SelectQueryMetadata(
            table = table,
            columns = columns,
            filterDescriptor = filterDescriptor,
            hasRange = hasRange,
            hasPagination = hasPagination,
            orderClauses = orderClausesResult ?: emptyList(),
            hasComplexParams = hasComplexParams,
            signature = signature
        )
    }

    /**
     * Parses a PostgREST query param string like `key=op.value&key2=op2.value2` into a map of keys to lists of values.
     * Handles repeated keys (e.g. `?id=eq.1&id=eq.2`) by collecting values into lists.
     * Does not perform URL decoding since the signature format does not encode special characters.
     *
     * E.g. `id=eq.1&id=eq.2&order=updated_at.desc` becomes:
     * ```
     * {
     *  "id": ["eq.1", "eq.2"],
     *  "order": ["updated_at.desc"]
     * }
     */
    private fun parseQueryStringParams(queryString: String): MutableMap<String, MutableList<String>> =
        queryString.split("&").fold(mutableMapOf()) { acc, pair ->
            val equalsIndex = pair.indexOf('=')
            when {
                equalsIndex >= 0 -> {
                    val key = pair.substring(0, equalsIndex)
                    val value = pair.substring(equalsIndex + 1)
                    acc.getOrPut(key) { mutableListOf() }.add(value)
                }

                pair.isNotEmpty() -> acc.getOrPut(pair) { mutableListOf() }
            }
            acc
        }

    /**
     * Meta keys are those used to capture properties relevant for cache subset derivation logic,
     * but not part of the filter conditions.
     *
     * E.g. `order` is meta because it doesn't affect which rows match but does affect how they are
     * sorted in memory after filtering.
     */
    private fun isSelectMetaParamKey(key: String): Boolean {
        return SELECT_META_PARAM_KEYS.contains(key)
    }

    private fun hasPaginationParams(params: Map<String, *>): Boolean {
        return PAGINATION_PARAM_KEYS.any { params.containsKey(it) }
    }
}