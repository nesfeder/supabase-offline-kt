// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A queued `is` condition replays exactly as the caller wrote it. `is` takes `null`, `not_null`,
 * `true`, `false` and `unknown`; replaying any of them as another reaches different rows.
 */
class IsFilterReplayTest {

    private fun newBuilder() =
        SelectRequestBuilder("public", PropertyConversionMethod.CAMEL_CASE_TO_SNAKE_CASE)

    /**
     * Captures `is.[value]` on `deleted_at` as the queue does, and replays it onto a fresh builder.
     */
    private fun replayed(value: String): List<String>? {
        val captured =
            assertNotNull(
                captureFilterExactly(
                    newBuilder().apply { filter { filter("deleted_at", FilterOperator.IS, value) } }
                )
            )
        val replayBuilder = newBuilder()
        replayOnto(replayBuilder, captured)
        return replayBuilder.params["deleted_at"]
    }

    @Test
    fun is_not_null_replays_as_is_not_null() {
        assertEquals(listOf("is.not_null"), replayed("not_null"))
    }

    @Test
    fun is_unknown_replays_as_is_unknown() {
        assertEquals(listOf("is.unknown"), replayed("unknown"))
    }

    @Test
    fun is_null_true_and_false_replay_unchanged() {
        assertEquals(listOf("is.null"), replayed("null"))
        assertEquals(listOf("is.true"), replayed("true"))
        assertEquals(listOf("is.false"), replayed("false"))
    }
}
