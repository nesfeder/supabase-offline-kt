// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder

/**
 * The `maybeSingle()` rows of `ReadShapeTest`, a supabase-kt 3.8 read shape: its name, its request,
 * and the server's answer, [oneRow].
 */
internal fun maybeSingleReadShapes(
    oneRow: String
): List<Triple<String, SelectRequestBuilder.() -> Unit, String>> =
    listOf(
        Triple(
            "maybeSingle",
            {
                filter { eq("list_id", "A") }
                maybeSingle()
            },
            oneRow,
        )
    )
