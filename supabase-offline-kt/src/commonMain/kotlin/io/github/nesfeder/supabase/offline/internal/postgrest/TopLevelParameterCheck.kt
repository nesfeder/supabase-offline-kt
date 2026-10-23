// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder

/**
 * Refuses a request whose top-level query parameter holds more than one value, as two filters on
 * one column (`gte("age", 1); lte("age", 10)`) leave it. supabase-kt sends only the first value, so
 * the request would read, or queue a write against, more rows than it names.
 *
 * @throws IllegalArgumentException naming the parameter and never a value.
 */
internal fun requireOneValuePerTopLevelParameter(builder: PostgrestRequestBuilder) {
    val repeated = builder.params.entries.firstOrNull { (_, values) -> values.size > 1 } ?: return
    throw IllegalArgumentException(
        "two top-level filters on `${repeated.key}`: supabase-kt sends only the first; " +
            "put them in one `and { }` group"
    )
}

/**
 * [request], then [requireOneValuePerTopLevelParameter], [requireReadablePatternLists] and
 * [requireReadableEscapedValues] on the builder it configured.
 */
internal fun <B : PostgrestRequestBuilder> refusingMisreadFilters(
    request: B.() -> Unit
): B.() -> Unit = {
    request()
    requireOneValuePerTopLevelParameter(this)
    requireReadablePatternLists(this)
    requireReadableEscapedValues(this)
}
