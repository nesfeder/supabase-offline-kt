// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.QueryCache
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

/**
 * The parsed payloads [QueryCache] keeps are bounded by their texts' length, and the least recently
 * used goes first when one more would pass the bound. Before this, the whole set was dropped once
 * it passed 1 MB, and a payload's new text counted on top of its old one.
 */
class ParsedPayloadsTest {

    /** A store keeping 300 characters of payload text parsed. */
    private val store =
        newTestStore(
            queryCacheLimits =
                QueryCacheLimits(
                    maxPayloadBytes = null,
                    evictionHeadroomBytes = 0L,
                    parsedPayloadCharsKept = 300L,
                )
        )

    private val cache: QueryCache
        get() = store.queryCache

    @Test
    fun the_least_recently_used_payload_goes_first() {
        // Given three payloads that fill the bound, the first read again after the others
        val a = row("a", 100)
        val b = row("b", 100)
        val c = row("c", 100)
        listOf(a, b, c).forEach(cache::parsedPayloadOf)
        assertSame(cache.parsedPayloadOf(a), cache.parsedPayloadOf(a))
        assertEquals(3, cache.payloadParses)

        // When a fourth is parsed
        cache.parsedPayloadOf(row("d", 100))

        // Then the least recently used, the second, went, and only it is parsed again
        assertEquals(4, cache.payloadParses)
        listOf(a, c, row("d", 100)).forEach(cache::parsedPayloadOf)
        assertEquals(4, cache.payloadParses)
        cache.parsedPayloadOf(b)
        assertEquals(5, cache.payloadParses)
    }

    @Test
    fun a_payload_longer_than_the_bound_is_parsed_each_time_and_keeps_nothing_out() {
        // Given two kept payloads
        val a = row("a", 100)
        val b = row("b", 100)
        listOf(a, b).forEach(cache::parsedPayloadOf)

        // When a payload longer than the whole bound is read twice
        repeat(2) { cache.parsedPayloadOf(row("big", 400)) }

        // Then it was parsed both times, and the kept ones stayed
        assertEquals(4, cache.payloadParses)
        listOf(a, b).forEach(cache::parsedPayloadOf)
        assertEquals(4, cache.payloadParses)
    }

    @Test
    fun a_payloads_new_text_takes_the_place_of_its_old_one() {
        // Given three payloads that fill the bound
        val b = row("b", 100)
        val c = row("c", 100)
        listOf(row("a", 100), b, c).forEach(cache::parsedPayloadOf)

        // When the first query's payload is read with another text of the same length
        val changed = row("a", 100, fill = 'y')
        cache.parsedPayloadOf(changed)

        // Then it was parsed once more, and nothing else went: the old text no longer counts
        assertEquals(4, cache.payloadParses)
        listOf(b, c, changed).forEach(cache::parsedPayloadOf)
        assertEquals(4, cache.payloadParses)
    }

    @Test
    fun emptying_the_cache_forgets_every_parsed_payload() = runTest {
        // Given a kept payload
        val a = row("a", 100)
        cache.parsedPayloadOf(a)

        // When local data is cleared
        store.localData.clearLocalData()

        // Then the payload is parsed again when next read
        cache.parsedPayloadOf(a)
        assertEquals(2, cache.payloadParses)
    }

    /** A cached row of `items` under [cacheKey] whose payload is a JSON array of [length] chars. */
    private fun row(cacheKey: String, length: Int, fill: Char = 'x'): CachedQueryRow =
        CachedQueryRow(
            table = "items",
            cacheKey = cacheKey,
            payload = "[\"" + fill.toString().repeat(length - 4) + "\"]",
            updatedAt = 0L,
        )
}
