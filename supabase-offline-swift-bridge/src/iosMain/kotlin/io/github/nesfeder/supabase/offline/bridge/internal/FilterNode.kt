// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import io.github.jan.supabase.postgrest.query.filter.TextSearchType
import kotlinx.serialization.json.JsonPrimitive

/**
 * One condition of a description, applied with the filter-builder call a Kotlin caller would make.
 * The request, and the cache key built from it, are then the same. Every value arrives as the text
 * the Swift side rendered it to; supabase-kt renders it on.
 */
internal sealed interface FilterNode {

    /** Adds this condition to [builder]. */
    fun applyTo(builder: PostgrestFilterBuilder)

    /**
     * `filter(column, operator, value)`, or `filterNot` when [negated]: [value] is a string, a list
     * of strings, a pair of strings or `null`, which supabase-kt renders by the operator.
     */
    class Condition(
        val column: String,
        private val operator: FilterOperator,
        private val value: Any?,
        private val negated: Boolean,
    ) : FilterNode {
        /** The operator's request name, such as `eq` or `in`. */
        val operatorName: String
            get() = operator.identifier

        override fun applyTo(builder: PostgrestFilterBuilder) {
            if (negated) builder.filterNot(column, operator, value)
            else builder.filter(column, operator, value)
        }

        /**
         * Whether supabase-kt 3.6 would send a value of this condition in a form the server reads
         * differently: an element of an `in` list at any level, or a single value [inGroup], that
         * holds `"` or `\` and none of `,.:()`. supabase-kt puts a backslash before each `"` and
         * `\` of such a value and quotes it only when it holds one of `,.:()`, and PostgREST reads
         * an unquoted element or group value as written, escapes included. A top-level single
         * value, a list of the array operators, a range and `null` are sent as written.
         */
        fun hasValueReadDifferently(inGroup: Boolean): Boolean =
            when {
                operator == FilterOperator.IN && value is List<*> ->
                    value.any { element -> element is String && isReadDifferentlyUnquoted(element) }
                inGroup && value is String -> isReadDifferentlyUnquoted(value)
                else -> false
            }
    }

    /** `or { … }` or `and { … }` over [children], negated or on a referenced table. */
    class Group(
        private val isOr: Boolean,
        private val children: List<FilterNode>,
        private val negated: Boolean,
        private val referencedTable: String?,
    ) : FilterNode {
        /** The query parameter supabase-kt writes the group to, such as `not.authors.or`. */
        val parameter: String =
            (if (negated) "not." else "") +
                (referencedTable?.let { "$it." } ?: "") +
                (if (isOr) OR else AND)

        override fun applyTo(builder: PostgrestFilterBuilder) {
            val nested: PostgrestFilterBuilder.() -> Unit = {
                children.forEach { it.applyTo(this) }
            }
            if (isOr) builder.or(negated, referencedTable, nested)
            else builder.and(negated, referencedTable, nested)
        }
    }

    /** `likeAll`, `likeAny`, `ilikeAll` or `ilikeAny` over [patterns]. */
    class PatternList(
        private val kind: String,
        val column: String,
        private val patterns: List<String>,
    ) : FilterNode {
        override fun applyTo(builder: PostgrestFilterBuilder) {
            when (kind) {
                LIKE_ALL -> builder.likeAll(column, patterns)
                LIKE_ANY -> builder.likeAny(column, patterns)
                ILIKE_ALL -> builder.ilikeAll(column, patterns)
                else -> builder.ilikeAny(column, patterns)
            }
        }
    }

    /** `textSearch(column, query, type, config)`. */
    class TextSearch(
        val column: String,
        private val query: String,
        private val type: TextSearchType,
        private val config: String?,
    ) : FilterNode {
        override fun applyTo(builder: PostgrestFilterBuilder) {
            builder.textSearch(column, query, type, config)
        }
    }

    /** The `kind` each node is named by in a description. */
    companion object {
        /** The `kind` of a [Condition]. */
        const val CONDITION = "filter"

        /** The `kind` of an `or` [Group]. */
        const val OR = "or"

        /** The `kind` of an `and` [Group]. */
        const val AND = "and"

        /** The `kind` of a [PatternList] every pattern must match, case-sensitively. */
        const val LIKE_ALL = "likeAll"

        /** The `kind` of a [PatternList] one pattern must match, case-sensitively. */
        const val LIKE_ANY = "likeAny"

        /** The `kind` of a [PatternList] every pattern must match, ignoring case. */
        const val ILIKE_ALL = "ilikeAll"

        /** The `kind` of a [PatternList] one pattern must match, ignoring case. */
        const val ILIKE_ANY = "ilikeAny"

        /** The `kind` of a [TextSearch]. */
        const val TEXT_SEARCH = "textSearch"
    }
}

/**
 * The filter nodes of [description]'s [key] array, in order: the description's own list when
 * [isTopLevel], a group's otherwise. A list supabase-kt would send narrower than written, or with a
 * value the server would read differently, is refused (see [refuseWhatSupabaseKtDrops]).
 */
internal fun readFilterNodes(
    description: DescriptionObject,
    key: String,
    isTopLevel: Boolean,
): List<FilterNode> {
    val nodes = description.objects(key).map(::readFilterNode)
    refuseWhatSupabaseKtDrops(nodes, description.fieldPath(key), isTopLevel)
    return nodes
}

/**
 * Refuses a filter list supabase-kt 3.6 would quietly drop part of, so a read is never wider than
 * the caller wrote it, or would send with a value the server reads differently, so it never matches
 * other rows than written. At the top level supabase-kt sends one filter per column and keeps one
 * group per parameter; at any level a text search replaces its column's earlier filters, and an
 * `in` element or a group value holding `"` or `\` and none of `,.:()` goes out escaped but
 * unquoted ([FilterNode.Condition.hasValueReadDifferently]). The message names the column and the
 * operator, never the value.
 */
private fun refuseWhatSupabaseKtDrops(nodes: List<FilterNode>, path: String, isTopLevel: Boolean) {
    val filteredColumns = mutableSetOf<String>()
    val groupParameters = mutableSetOf<String>()
    nodes.forEachIndexed { index, node ->
        val column =
            when (node) {
                is FilterNode.Condition -> node.column
                is FilterNode.PatternList -> node.column
                is FilterNode.TextSearch -> node.column
                is FilterNode.Group -> null
            }
        when {
            node is FilterNode.Condition && node.hasValueReadDifferently(inGroup = !isTopLevel) ->
                throw InvalidDescriptionException(
                    "$path[$index]: a value of \"${node.operatorName}\" on \"$column\" holds a " +
                        "quote or a backslash and none of ,.:(), which supabase-kt escapes but does " +
                        "not quote, so the server would read the escapes as the value; only a " +
                        "single top-level filter sends such a value as written"
                )
            node is FilterNode.Group && isTopLevel && !groupParameters.add(node.parameter) ->
                throw InvalidDescriptionException(
                    "$path[$index]: a second \"${node.parameter}\" group at the top level, of " +
                        "which supabase-kt sends only the last; put both groups in one \"and\" group"
                )
            column == null -> Unit
            node is FilterNode.TextSearch && column in filteredColumns ->
                throw InvalidDescriptionException(
                    "$path[$index]: a text search on \"$column\" replaces the filters before it on " +
                        "that column; put the text search first, or the filters in an \"and\" group"
                )
            isTopLevel && !filteredColumns.add(column) ->
                throw InvalidDescriptionException(
                    "$path[$index]: a second filter on \"$column\" at the top level, which " +
                        "supabase-kt drops (it sends one filter per column); put the filters on " +
                        "\"$column\" in one \"and\" group"
                )
            else -> filteredColumns += column
        }
    }
}

/** Whether [text] would be escaped but left unquoted: a `"` or `\`, and none of `,.:()`. */
private fun isReadDifferentlyUnquoted(text: String): Boolean =
    text.any { character -> character == '"' || character == '\\' } &&
        text.none { character -> character in ",.:()" }

/** One filter node; its `kind` says which. */
private fun readFilterNode(node: DescriptionObject): FilterNode {
    val filter =
        when (val kind = node.string("kind")) {
            FilterNode.CONDITION -> readCondition(node)
            FilterNode.OR,
            FilterNode.AND -> {
                val children = readFilterNodes(node, "filters", isTopLevel = false)
                if (children.isEmpty()) {
                    throw InvalidDescriptionException(
                        "${node.path}: an empty \"$kind\" group, which supabase-kt drops; leave it out"
                    )
                }
                FilterNode.Group(
                    isOr = kind == FilterNode.OR,
                    children = children,
                    negated = node.boolean("negated", default = false),
                    referencedTable = node.optionalString("referencedTable"),
                )
            }
            FilterNode.LIKE_ALL,
            FilterNode.LIKE_ANY,
            FilterNode.ILIKE_ALL,
            FilterNode.ILIKE_ANY ->
                FilterNode.PatternList(
                    kind = kind,
                    column = node.string("column"),
                    patterns = node.strings("patterns"),
                )
            FilterNode.TEXT_SEARCH ->
                FilterNode.TextSearch(
                    column = node.string("column"),
                    query = node.string("query"),
                    type = readTextSearchType(node),
                    config = node.optionalString("config"),
                )
            else ->
                throw UnsupportedDescriptionException(
                    "${node.fieldPath("kind")}: \"$kind\" is not a filter this build offers"
                )
        }
    node.requireNoOtherFields()
    return filter
}

/**
 * A [FilterNode.Condition]. The operator is any of supabase-kt's own, by its PostgREST name; one
 * the pinned supabase-kt does not have is refused, never dropped. Exactly one value form must be
 * given, and it must be one the operator takes ([valueFormsOf]): supabase-kt renders whatever it is
 * handed, so a list for `eq` would be sent as `eq.[x, y]`.
 */
private fun readCondition(node: DescriptionObject): FilterNode.Condition {
    val column = node.string("column")
    val operatorName = node.string("operator")
    val operator =
        FilterOperator.entries.firstOrNull { it.identifier == operatorName }
            ?: throw UnsupportedDescriptionException(
                "${node.fieldPath("operator")}: \"$operatorName\" is not a filter operator this " +
                    "build offers"
            )
    val allowed = valueFormsOf(operatorName)
    val given = VALUE_FORMS.filter { node.containsKey(it) }
    val form =
        given.singleOrNull()
            ?: throw InvalidDescriptionException(
                "${node.path}: give exactly one of ${allowed.joinToString { "\"$it\"" }} for " +
                    "\"$operatorName\"" +
                    if (given.isEmpty()) "" else ", not ${given.joinToString { "\"$it\"" }}"
            )
    if (form !in allowed) {
        throw InvalidDescriptionException(
            "${node.path}: \"$operatorName\" takes ${allowed.joinToString(" or ") { "\"$it\"" }}, " +
                "not \"$form\""
        )
    }
    val value: Any? =
        when (form) {
            VALUES -> node.strings(VALUES)
            RANGE -> readRange(node)
            else -> readSingleValue(node)
        }
    return FilterNode.Condition(
        column = column,
        operator = operator,
        value = value,
        negated = node.boolean("negated", default = false),
    )
}

/** The single-value form of a condition's value. */
private const val VALUE = "value"

/** The list form of a condition's value. */
private const val VALUES = "values"

/** The bound-pair form of a condition's value. */
private const val RANGE = "range"

/** Every value form, in the order an error names them. */
private val VALUE_FORMS = listOf(VALUE, VALUES, RANGE)

/**
 * The value forms [operator] takes, by how supabase-kt renders it: `in` a list; the array operators
 * a list, a range or a raw literal (such as a jsonb value); the range operators a range; every
 * other operator one value.
 */
private fun valueFormsOf(operator: String): List<String> =
    when (operator) {
        "in" -> listOf(VALUES)
        "cs",
        "cd",
        "ov" -> listOf(VALUE, VALUES, RANGE)
        "sl",
        "sr",
        "nxl",
        "nxr",
        "adj" -> listOf(RANGE)
        else -> listOf(VALUE)
    }

/** The text of `value`, or `null` when it is `null`: supabase-kt renders it `null`. */
private fun readSingleValue(node: DescriptionObject): String? {
    val value = node.element(VALUE) ?: return null
    if (value !is JsonPrimitive || !value.isString) {
        throw InvalidDescriptionException("${node.fieldPath(VALUE)} must be a string or null")
    }
    return value.content
}

/** `range`: exactly two strings, a lower and an upper bound. */
private fun readRange(node: DescriptionObject): Pair<String, String> {
    val bounds = node.strings(RANGE)
    if (bounds.size != 2) {
        throw InvalidDescriptionException("${node.fieldPath(RANGE)} must hold exactly two strings")
    }
    return bounds[0] to bounds[1]
}

/** A text search's `type`: `plain`, `phrase`, `websearch`, or absent for none. */
private fun readTextSearchType(node: DescriptionObject): TextSearchType =
    when (val type = node.optionalString("type")) {
        null -> TextSearchType.NONE
        "plain" -> TextSearchType.PLAINTO
        "phrase" -> TextSearchType.PHRASETO
        "websearch" -> TextSearchType.WEBSEARCH
        else ->
            throw UnsupportedDescriptionException(
                "${node.fieldPath("type")}: \"$type\" is not a text search type this build offers"
            )
    }
