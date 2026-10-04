// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder

/** The `maybeSingle()` rows of `ReadShapeTest`: none, since supabase-kt 3.6 has no `maybeSingle()`. */
internal fun maybeSingleReadShapes(
    oneRow: String
): List<Triple<String, SelectRequestBuilder.() -> Unit, String>> = emptyList()
