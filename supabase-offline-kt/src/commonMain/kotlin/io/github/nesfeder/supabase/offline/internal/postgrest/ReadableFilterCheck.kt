// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder

/**
 * Refuses a request the server would read as another one: [requireReadablePatternLists] and
 * [requireReadableEscapedValues] on [builder]. Two filters on one column are not among them:
 * supabase-kt sends every value of a parameter and PostgREST ANDs them.
 *
 * @throws IllegalArgumentException naming the column and never a value.
 */
internal fun requireReadableFilters(builder: PostgrestRequestBuilder) {
    requireReadablePatternLists(builder)
    requireReadableEscapedValues(builder)
}

/** [request], then [requireReadableFilters] on the builder it configured. */
internal fun <B : PostgrestRequestBuilder> refusingMisreadFilters(
    request: B.() -> Unit
): B.() -> Unit = {
    request()
    requireReadableFilters(this)
}
