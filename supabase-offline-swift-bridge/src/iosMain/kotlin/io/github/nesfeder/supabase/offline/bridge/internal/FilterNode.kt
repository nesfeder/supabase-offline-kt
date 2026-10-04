// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import io.github.jan.supabase.postgrest.query.filter.TextSearchType
import kotlinx.serialization.json.JsonPrimitive

/**
 * One condition of a description, applied onto supabase-kt's own filter builder with the call a
 * Kotlin caller would make, so the request, and the cache key built from it, are the same.
 *
 * Every value arrives as the text the Swift side rendered it to; supabase-kt renders it on.
 */
internal sealed interface FilterNode {

    /** Adds this condition to [builder]. */
    fun applyTo(builder: PostgrestFilterBuilder)

    /**
     * `filter(column, operator, value)`, or `filterNot` when [negated]: [value] is a string, a list
     * of strings, a pair of strings or `null`, which supabase-kt renders by the operator.
     */
    class Condition(
        private val column: String,
        private val operator: FilterOperator,
        private val value: Any?,
        private val negated: Boolean,
    ) : FilterNode {
        override fun applyTo(builder: PostgrestFilterBuilder) {
            if (negated) builder.filterNot(column, operator, value)
            else builder.filter(column, operator, value)
        }
    }

    /** `or { … }` or `and { … }` over [children], negated or on a referenced table. */
    class Group(
        private val isOr: Boolean,
        private val children: List<FilterNode>,
        private val negated: Boolean,
        private val referencedTable: String?,
    ) : FilterNode {
        override fun applyTo(builder: PostgrestFilterBuilder) {
            val nested: PostgrestFilterBuilder.() -> Unit = { children.forEach { it.applyTo(this) } }
            if (isOr) builder.or(negated, referencedTable, nested)
            else builder.and(negated, referencedTable, nested)
        }
    }

    /** `likeAll`, `likeAny`, `ilikeAll` or `ilikeAny` over [patterns]. */
    class PatternList(
        private val kind: String,
        private val column: String,
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
        private val column: String,
        private val query: String,
        private val type: TextSearchType,
        private val config: String?,
    ) : FilterNode {
        override fun applyTo(builder: PostgrestFilterBuilder) {
            builder.textSearch(column, query, type, config)
        }
    }

    companion object {
        /** The `kind` of a [Condition]. */
        const val CONDITION = "filter"

        /** The `kind` of an `or` [Group]. */
        const val OR = "or"

        /** The `kind` of an `and` [Group]. */
        const val AND = "and"

        /** The `kind`s of a [PatternList]. */
        const val LIKE_ALL = "likeAll"
        const val LIKE_ANY = "likeAny"
        const val ILIKE_ALL = "ilikeAll"
        const val ILIKE_ANY = "ilikeAny"

        /** The `kind` of a [TextSearch]. */
        const val TEXT_SEARCH = "textSearch"
    }
}

/** The filter nodes of [description]'s [key] array, in order. */
internal fun readFilterNodes(description: DescriptionObject, key: String): List<FilterNode> =
    description.objects(key).map(::readFilterNode)

/** One filter node; its `kind` says which. */
private fun readFilterNode(node: DescriptionObject): FilterNode {
    val filter =
        when (val kind = node.string("kind")) {
            FilterNode.CONDITION -> readCondition(node)
            FilterNode.OR,
            FilterNode.AND ->
                FilterNode.Group(
                    isOr = kind == FilterNode.OR,
                    children = readFilterNodes(node, "filters"),
                    negated = node.boolean("negated", default = false),
                    referencedTable = node.optionalString("referencedTable"),
                )
            FilterNode.LIKE_ALL,
            FilterNode.LIKE_ANY,
            FilterNode.ILIKE_ALL,
            FilterNode.ILIKE_ANY ->
                FilterNode.PatternList(
                    kind = kind,
                    column = node.string("column"),
                    patterns =
                        node.optionalStrings("patterns")
                            ?: throw InvalidDescriptionException(
                                "${node.fieldPath("patterns")} is required"
                            ),
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
 * the pinned supabase-kt does not have is refused, never dropped.
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
    val forms = listOf("value", "values", "range").filter { node.has(it) }
    if (forms.size > 1) {
        throw InvalidDescriptionException(
            "${node.path}: give one of \"value\", \"values\" and \"range\", not ${forms.joinToString()}"
        )
    }
    val value: Any? =
        when (forms.singleOrNull()) {
            "values" -> node.optionalStrings("values")
            "range" -> readRange(node)
            else -> readSingleValue(node)
        }
    return FilterNode.Condition(
        column = column,
        operator = operator,
        value = value,
        negated = node.boolean("negated", default = false),
    )
}

/** The text of `value`, or `null` when it is `null` or absent: supabase-kt renders it `null`. */
private fun readSingleValue(node: DescriptionObject): String? {
    val value = node.element("value") ?: return null
    if (value !is JsonPrimitive || !value.isString) {
        throw InvalidDescriptionException("${node.fieldPath("value")} must be a string or null")
    }
    return value.content
}

/** `range`: exactly two strings, a lower and an upper bound. */
private fun readRange(node: DescriptionObject): Pair<String, String> {
    val bounds = node.optionalStrings("range").orEmpty()
    if (bounds.size != 2) {
        throw InvalidDescriptionException("${node.fieldPath("range")} must hold exactly two strings")
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
