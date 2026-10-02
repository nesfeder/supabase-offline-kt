// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/**
 * Each read strategy has one constructor, so `NetworkFirst(null)` and its siblings compile: a
 * second constructor taking a nullable value would make the call ambiguous.
 */
class ReadStrategyTest {

    @Test
    fun a_strategy_built_with_null_takes_rows_of_any_age() {
        // Given / When
        val strategies =
            listOf(
                ReadStrategy.NetworkFirst(null),
                ReadStrategy.CacheFirst(null),
                ReadStrategy.CacheOnly(null),
            )

        // Then
        assertNull((strategies[0] as ReadStrategy.NetworkFirst).maxAge)
        assertNull((strategies[1] as ReadStrategy.CacheFirst).maxAge)
        assertNull((strategies[2] as ReadStrategy.CacheOnly).maxAge)
    }

    @Test
    fun a_strategy_equals_another_of_the_same_age() {
        // Given / When / Then
        assertEquals(ReadStrategy.NetworkFirst(), ReadStrategy.NetworkFirst(null))
        assertEquals(ReadStrategy.CacheFirst(5.minutes), ReadStrategy.CacheFirst(5.minutes))
        assertEquals(ReadStrategy.CacheOnly(), ReadStrategy.CacheOnly(maxAge = null))
    }
}
