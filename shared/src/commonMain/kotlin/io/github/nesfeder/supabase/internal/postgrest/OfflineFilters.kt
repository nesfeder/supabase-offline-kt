@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.internal.postgrest

import io.github.nesfeder.supabase.internal.postgrest.FilterEngine.applyTo
import io.github.nesfeder.supabase.internal.postgrest.FilterEngine.decodeOrNull
import io.github.nesfeder.supabase.internal.postgrest.FilterEngine.encode
import io.github.nesfeder.supabase.internal.postgrest.FilterEngine.matchesRow
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import io.github.jan.supabase.postgrest.query.filter.TextSearchType
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Serializable description of a PostgREST filter expression.
 *
 * This is the compact form we store alongside offline mutations so we can:
 * - apply them to local JSON rows (offline overlay)
 * - replay them into a real PostgrestRequestBuilder when syncing UPDATE/DELETE
 */
@Serializable
internal data class FilterDescriptor(
    /** How conditions are combined. AND is the common case; OR uses PostgREST logical expressions. */
    val mode: Mode = Mode.AND,
    val conditions: List<Condition>
) {
    @Serializable
    enum class Mode { AND, OR }

    /**
     * A single condition like: `eq("id", "123")` or `ilike("name", "%foo%")`.
     */
    @Serializable
    data class Condition(
        val column: String,
        /** FilterOperator.identifier, e.g. "eq", "lt", "ilike" */
        val operatorId: String,
        /** Raw value as string (we’ll coerce heuristically when comparing / replaying) */
        val value: String
    ) {
        val operator: FilterOperator?
            get() = FilterOperator.entries.firstOrNull { it.identifier == operatorId }
    }
}

/**
 * Extract a best-effort [FilterDescriptor] from a [PostgrestRequestBuilder].
 *
 * We only handle the simple, common case:
 *
 *   column -> ["eq.value"]
 *
 * via PostgrestFilterBuilder’s `eq("column", value)` etc.  Complex DSL usage
 * (`and(...)`, `or(...)`, foreign-table filters) is intentionally ignored so we
 * don’t risk wrong offline behaviour.
 */
internal fun buildDescriptorFromRequest(
    conversionMethod: PropertyConversionMethod,
    request: PostgrestRequestBuilder.() -> Unit
): FilterDescriptor? {
    val builder = PostgrestRequestBuilder(conversionMethod).apply(request)
    return buildDescriptorFromBuilder(builder)
}

internal fun buildDescriptorFromBuilder(
    builder: PostgrestRequestBuilder
): FilterDescriptor? {
    val conditions = mutableListOf<FilterDescriptor.Condition>()

    builder.params.forEach { (key, values) ->
        // Skip special / nested keys (e.g. "and(...)", "or(...)", "table.column").
        if (key.contains("(") || key.contains(")") || key.contains(".")) return@forEach

        val first = values.firstOrNull() ?: return@forEach
        // Expect something like "eq.value" or "eq.123"
        val parts = first.split('.', limit = 2)
        if (parts.size < 2) return@forEach

        val opIdentifier = parts[0]
        val rawValue = parts[1]

        val operator = FilterOperator.entries.firstOrNull { it.identifier == opIdentifier }
            ?: return@forEach

        val normalizedValue = rawValue.trim().trim('"', '\'')

        conditions += FilterDescriptor.Condition(
            column = key,
            operatorId = operator.identifier,
            value = normalizedValue
        )
    }

    if (conditions.isEmpty()) return null
    return FilterDescriptor(
        mode = FilterDescriptor.Mode.AND,
        conditions = conditions
    )
}

/**
 * Encodes / decodes filter descriptors and replays them on demand.
 *
 * - [encode] / [decodeOrNull] : store & restore conditions for UPDATE/DELETE mutations
 * - [applyTo]           : replay a FilterDescriptor onto PostgrestRequestBuilder
 * - [matchesRow]        : used by the offline overlay engine for SELECTs
 */
internal object FilterEngine {

    private val json = Json { ignoreUnknownKeys = true }

    /* ─────────────────────── Encoding / Decoding ─────────────────────── */

    fun encode(descriptor: FilterDescriptor, json: Json): String =
        json.encodeToString(FilterDescriptor.serializer(), descriptor)

    fun decodeOrNull(filterJson: String?, json: Json): FilterDescriptor? {
        if (filterJson.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(FilterDescriptor.serializer(), filterJson)
        }.getOrNull()
    }

    /* ───────────────────── Replay onto PostgREST ───────────────────── */

    /**
     * Replays filters onto a live [PostgrestRequestBuilder].
     *
     * Used by OfflineMutationSyncer when syncing UPDATE / DELETE operations.
     *
     * - For [FilterDescriptor.Mode.AND], we just call each condition on the filter DSL:
     *   `filter { eq(...); lt(...); ilike(...) }`
     *
     * - For [FilterDescriptor.Mode.OR], we wrap the conditions in a logical OR block:
     *   `filter { or { eq(...); lt(...); } }`
     *
     * The underlying `PostgrestFilterBuilder` takes care of building the correct
     * `or=(...)` / `and=(...)` query params.
     */
    fun applyTo(
        builder: PostgrestRequestBuilder,
        descriptor: FilterDescriptor
    ) {
        builder.filter {
            when (descriptor.mode) {
                FilterDescriptor.Mode.AND -> {
                    descriptor.conditions.forEach { cond ->
                        applyCondition(cond)
                    }
                }

                FilterDescriptor.Mode.OR -> {
                    or {
                        descriptor.conditions.forEach { cond ->
                            applyCondition(cond)
                        }
                    }
                }
            }
        }
    }

    /**
     * Apply a single condition to the [PostgrestFilterBuilder] using the same
     * DSL that callers would use online.
     */
    private fun PostgrestFilterBuilder.applyCondition(cond: FilterDescriptor.Condition) {
        val operator = cond.operator ?: return
        val rawValue = cond.value

        when (operator) {
            // Basic comparison
            FilterOperator.EQ -> eq(cond.column, rawValue)
            FilterOperator.NEQ -> neq(cond.column, rawValue)
            FilterOperator.GT -> gt(cond.column, rawValue)
            FilterOperator.GTE -> gte(cond.column, rawValue)
            FilterOperator.LT -> lt(cond.column, rawValue)
            FilterOperator.LTE -> lte(cond.column, rawValue)

            // Patterns
            FilterOperator.LIKE -> like(cond.column, rawValue)
            FilterOperator.ILIKE -> ilike(cond.column, rawValue)
            FilterOperator.MATCH -> match(cond.column, rawValue)
            FilterOperator.IMATCH -> imatch(cond.column, rawValue)

            // IS: Supabase-kt exposes this via `exact(column, Boolean?)`
            FilterOperator.IS -> {
                val boolValue: Boolean? = when (rawValue.lowercase()) {
                    "true" -> true
                    "false" -> false
                    "null" -> null
                    // "unknown" isn't directly supported by the current exact() API;
                    // we just treat it as null to avoid throwing.
                    "unknown" -> null
                    else -> null
                }
                exact(cond.column, boolValue)
            }

            // IN
            FilterOperator.IN -> {
                val list = parseArray(rawValue)
                if (!list.isNullOrEmpty()) {
                    isIn(cond.column, list)
                }
            }

            // Array / JSON operators
            FilterOperator.CS -> {
                val list = parseArray(rawValue)
                if (!list.isNullOrEmpty()) {
                    cs(cond.column, list)
                }
            }

            FilterOperator.CD -> {
                val list = parseArray(rawValue)
                if (!list.isNullOrEmpty()) {
                    cd(cond.column, list)
                }
            }

            // Ranges
            FilterOperator.SL -> {
                val range = parseRange(rawValue) ?: return
                sl(cond.column, range)
            }

            FilterOperator.SR -> {
                val range = parseRange(rawValue) ?: return
                sr(cond.column, range)
            }

            FilterOperator.NXL -> {
                val range = parseRange(rawValue) ?: return
                nxl(cond.column, range)
            }

            FilterOperator.NXR -> {
                val range = parseRange(rawValue) ?: return
                nxr(cond.column, range)
            }

            FilterOperator.ADJ -> {
                val range = parseRange(rawValue) ?: return
                adjacent(cond.column, range)
            }

            FilterOperator.OV -> {
                val list = parseArray(rawValue)
                if (!list.isNullOrEmpty()) {
                    ov(cond.column, list)
                }
            }

            // Full text search: map onto textSearch + TextSearchType
            FilterOperator.FTS -> textSearch(cond.column, rawValue, TextSearchType.NONE)
            FilterOperator.PLFTS -> textSearch(cond.column, rawValue, TextSearchType.PLAINTO)
            FilterOperator.PHFTS -> textSearch(cond.column, rawValue, TextSearchType.PHRASETO)
            FilterOperator.WFTS -> textSearch(cond.column, rawValue, TextSearchType.WEBSEARCH)
        }
    }

    /**
     * Parse Condition.value into a list of strings for array-like operators:
     *
     * - If the string looks like JSON array (`[ "a", "b" ]`), we parse via kotlinx.serialization.
     * - Otherwise, we treat it as a comma-separated list (e.g. `"a,b,c"`).
     */
    private fun parseArray(raw: String?): List<Any>? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // JSON array form
        if (trimmed.startsWith("[")) {
            return runCatching {
                json.decodeFromString(ListSerializer(String.serializer()), trimmed)
            }.getOrNull()
        }

        // Comma-separated form
        return trimmed.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Parse Condition.value into a range represented as Pair<Any, Any>.
     *
     * Supported string forms:
     * - JSON array: `["min","max"]`
     * - Comma-separated: `"min,max"`
     */
    private fun parseRange(raw: String?): Pair<Any, Any>? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // JSON array form
        if (trimmed.startsWith("[")) {
            val list = runCatching {
                json.decodeFromString(ListSerializer(String.serializer()), trimmed)
            }.getOrNull()
            if (list != null && list.size >= 2) {
                return list[0] to list[1]
            }
            return null
        }

        // Comma-separated
        val parts = trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            return parts[0] to parts[1]
        }

        return null
    }

    /* ───────────────────── Offline matching for overlay ───────────────────── */

    /**
     * Returns true if [row] matches [descriptor] according to its conditions.
     *
     * Used by the offline overlay logic to apply pending mutations to a
     * cached SELECT payload.
     *
     * This is intentionally conservative: unsupported operators default to
     * "no match" so we don't accidentally hide rows offline.
     */
    fun matchesRow(
        row: JsonObject,
        descriptor: FilterDescriptor
    ): Boolean {
        return when (descriptor.mode) {
            FilterDescriptor.Mode.AND ->
                descriptor.conditions.all { conditionMatches(row, it) }

            FilterDescriptor.Mode.OR ->
                descriptor.conditions.any { conditionMatches(row, it) }
        }
    }

    /**
     * Apply a single condition to a JSON row.
     *
     * We favour “safe and correct” over “everything”: if we’re not sure we can evaluate
     * something faithfully offline (full-text search, PostGIS, complex range ops), we
     * simply return false for that condition so we don’t accidentally diverge from the
     * server’s behaviour.
     */
    private fun conditionMatches(
        row: JsonObject,
        condition: FilterDescriptor.Condition
    ): Boolean {
        val operator = condition.operator ?: return false
        val rawField: JsonElement = row[condition.column] ?: return false
        val fieldValue = rawField.jsonPrimitive
        val filterValue = condition.value

        fun JsonPrimitive.asDoubleOrNull(): Double? =
            doubleOrNull ?: content.toDoubleOrNull()

        fun JsonPrimitive.asBooleanOrNull(): Boolean? =
            booleanOrNull ?: content.toBooleanStrictOrNull()

        return when (operator) {
            FilterOperator.EQ -> fieldValue.content == filterValue
            FilterOperator.NEQ -> fieldValue.content != filterValue

            FilterOperator.GT,
            FilterOperator.GTE,
            FilterOperator.LT,
            FilterOperator.LTE -> {
                val fvNum = fieldValue.asDoubleOrNull()
                val filterNum = filterValue.toDoubleOrNull()
                if (fvNum == null || filterNum == null) return false
                when (operator) {
                    FilterOperator.GT -> fvNum > filterNum
                    FilterOperator.GTE -> fvNum >= filterNum
                    FilterOperator.LT -> fvNum < filterNum
                    FilterOperator.LTE -> fvNum <= filterNum
                    else -> false
                }
            }

            FilterOperator.LIKE,
            FilterOperator.MATCH -> {
                val needle = filterValue.replace("%", "").lowercase()
                fieldValue.content.lowercase().contains(needle)
            }

            FilterOperator.ILIKE,
            FilterOperator.IMATCH -> {
                val needle = filterValue.replace("%", "").lowercase()
                fieldValue.content.lowercase().contains(needle)
            }

            FilterOperator.IS -> {
                when (filterValue.lowercase()) {
                    "null" -> fieldValue.contentOrNull == null
                    "true" -> fieldValue.asBooleanOrNull() == true
                    "false" -> fieldValue.asBooleanOrNull() == false
                    else -> fieldValue.content.equals(filterValue, ignoreCase = true)
                }
            }

            FilterOperator.IN -> {
                // PostgREST value is usually "(a,b,c)"
                val items = filterValue
                    .trim('(', ')')
                    .split(',')
                    .map { it.trim().trim('"', '\'') }

                items.contains(fieldValue.content)
            }

            // We DO NOT try to emulate these complex / domain-specific operators offline:
            // containment, ranges, adjacency, overlaps, FTS, etc. We return false so we
            // don't accidentally "match" rows that the server wouldn't.
            FilterOperator.CS,
            FilterOperator.CD,
            FilterOperator.SL,
            FilterOperator.SR,
            FilterOperator.NXL,
            FilterOperator.NXR,
            FilterOperator.ADJ,
            FilterOperator.OV,
            FilterOperator.FTS,
            FilterOperator.PLFTS,
            FilterOperator.PHFTS,
            FilterOperator.WFTS -> false
        }
    }
}