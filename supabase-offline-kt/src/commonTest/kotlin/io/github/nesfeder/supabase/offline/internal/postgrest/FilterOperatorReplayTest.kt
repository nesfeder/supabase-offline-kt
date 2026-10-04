// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every filter operator of the supabase-kt the library is built against replays. The replay names
 * each operator, by a branch or by identifier, and skips any other, so a new upstream operator fails
 * here until the replay names it: the check the compiler made while the replay matched every
 * operator exhaustively.
 */
class FilterOperatorReplayTest {

    @Test
    fun every_operator_supabase_kt_defines_replays() {
        // Given — one stored condition per operator, holding a value of the shape it takes
        val conditions =
            FilterOperator.entries.map { operator ->
                FilterDescriptor.Condition(
                    column = "status",
                    operatorId = operator.identifier,
                    value = sampleValueFor(operator.identifier),
                )
            }

        // When
        val unreplayed =
            conditions
                .filterNot { condition ->
                    replaysFully(FilterDescriptor(conditions = listOf(condition)))
                }
                .map { condition -> condition.operatorId }

        // Then
        assertEquals(emptyList(), unreplayed, "operators the replay does not name")
    }

    /** A stored value of the shape [identifier]'s replay parses: a list, a range or a scalar. */
    private fun sampleValueFor(identifier: String): String =
        when (identifier) {
            "in" -> "(open,done)"
            "cs",
            "cd",
            "ov" -> "{open,done}"
            "sl",
            "sr",
            "nxl",
            "nxr",
            "adj" -> "[1,2]"
            else -> "done"
        }
}
