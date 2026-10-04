// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder

/**
 * What a description adds to any PostgREST request, in the order supabase-kt's own calls would add
 * it: its filters, then its modifiers (`order`, `limit`, `range`) in the order given, then the
 * count and the response's shape.
 */
internal class RequestShape(
    private val filters: List<FilterNode>,
    private val modifiers: List<Modifier>,
    private val count: Count?,
    private val single: Boolean,
    private val format: ResponseFormat,
    private val stripNulls: Boolean,
) {
    /** Applies this shape onto [request], as a Kotlin caller's request lambda would. */
    fun applyTo(request: PostgrestRequestBuilder) {
        if (filters.isNotEmpty()) request.filter { filters.forEach { it.applyTo(this) } }
        modifiers.forEach { it.applyTo(request) }
        count?.let(request::count)
        if (single) request.single()
        when (format) {
            ResponseFormat.JSON -> Unit
            ResponseFormat.CSV -> request.csv()
            ResponseFormat.GEOJSON -> request.geojson()
        }
        if (stripNulls) request.stripNulls()
    }

    /** One of `order`, `limit` and `range`, on the main table or a referenced one. */
    sealed interface Modifier {

        /** Adds this modifier to [request]. */
        fun applyTo(request: PostgrestRequestBuilder)

        /** `order(column, order, nullsFirst, referencedTable)`. */
        class Ordering(
            private val column: String,
            private val ascending: Boolean,
            private val nullsFirst: Boolean,
            private val referencedTable: String?,
        ) : Modifier {
            override fun applyTo(request: PostgrestRequestBuilder) {
                val order = if (ascending) Order.ASCENDING else Order.DESCENDING
                request.order(column, order, nullsFirst, referencedTable)
            }
        }

        /** `limit(count, referencedTable)`. */
        class Limit(private val count: Long, private val referencedTable: String?) : Modifier {
            override fun applyTo(request: PostgrestRequestBuilder) {
                request.limit(count, referencedTable)
            }
        }

        /** `range(from, to, referencedTable)`, both bounds inclusive. */
        class Range(
            private val from: Long,
            private val to: Long,
            private val referencedTable: String?,
        ) : Modifier {
            override fun applyTo(request: PostgrestRequestBuilder) {
                request.range(from, to, referencedTable)
            }
        }
    }

    /** The body a request asks for: JSON (the default), CSV or GeoJSON. */
    enum class ResponseFormat {
        /** JSON rows. */
        JSON,

        /** `csv()`. */
        CSV,

        /** `geojson()`. */
        GEOJSON,
    }
}

/** The filters, modifiers, count and response shape of [description]. */
internal fun readRequestShape(description: DescriptionObject): RequestShape =
    RequestShape(
        filters = readFilterNodes(description, "filters"),
        modifiers = description.objects("modifiers").map(::readModifier),
        count = readCount(description),
        single = description.boolean("single", default = false),
        format = readFormat(description),
        stripNulls = description.boolean("stripNulls", default = false),
    )

/** One modifier; its `kind` says which. */
private fun readModifier(node: DescriptionObject): RequestShape.Modifier {
    val referencedTable = node.optionalString("referencedTable")
    val modifier =
        when (val kind = node.string("kind")) {
            "order" ->
                RequestShape.Modifier.Ordering(
                    column = node.string("column"),
                    ascending = node.boolean("ascending", default = true),
                    nullsFirst = node.boolean("nullsFirst", default = false),
                    referencedTable = referencedTable,
                )
            "limit" -> RequestShape.Modifier.Limit(node.long("count"), referencedTable)
            "range" ->
                RequestShape.Modifier.Range(
                    from = node.long("from"),
                    to = node.long("to"),
                    referencedTable = referencedTable,
                )
            else ->
                throw UnsupportedDescriptionException(
                    "${node.fieldPath("kind")}: \"$kind\" is not a modifier this build offers"
                )
        }
    node.requireNoOtherFields()
    return modifier
}

/** `count`: `exact`, `planned`, `estimated`, or absent. */
private fun readCount(description: DescriptionObject): Count? {
    val name = description.optionalString("count") ?: return null
    return Count.entries.firstOrNull { it.identifier == name }
        ?: throw UnsupportedDescriptionException(
            "${description.fieldPath("count")}: \"$name\" is not a count this build offers"
        )
}

/** `format`: `json` (the default), `csv` or `geojson`. */
private fun readFormat(description: DescriptionObject): RequestShape.ResponseFormat =
    when (val format = description.optionalString("format")) {
        null,
        "json" -> RequestShape.ResponseFormat.JSON
        "csv" -> RequestShape.ResponseFormat.CSV
        "geojson" -> RequestShape.ResponseFormat.GEOJSON
        else ->
            throw UnsupportedDescriptionException(
                "${description.fieldPath("format")}: \"$format\" is not a format this build offers"
            )
    }
