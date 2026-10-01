// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.internal.EngineJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pins the replay path of [replayOnto] against the wrapper-stripping defect: supabase-kt encodes
 * `isIn` params as `in.(a,b,c)` (and range operators as `sl.(min,max)`, array operators as
 * `cs.{a,b}`), the strict capture stores the value with its wrapper intact, and replay must strip
 * that wrapper before splitting — otherwise the replayed filter targets corrupted values like
 * `"(a"` / `"c)"`. Each test drives the same pipeline the offline mutation queue uses: capture
 * ([captureFilterExactly]) → [encodeFilter] → [decodeFilterOrNull] → [replayOnto], asserting the
 * replayed request carries the caller's original param. Overlay parity is asserted via [rowMatch]:
 * a row the overlay treats as matched is one replay reaches.
 *
 * The later cases also pin that the parser tokenizes by *encoding* rather than by delimiter. Only
 * `in`'s `(a,b)` comes from an encoder that escapes its elements (`encodeAsList` → `escapeValue`);
 * `cs`/`cd`/`ov`'s `{a,b}` and every range body are interpolated verbatim, so a `"` or `\` inside
 * one of those is literal content. Unescaping it replays a filter against different rows, which is
 * why each of those cases asserts a byte-exact round trip in both directions.
 */
class FilterReplayTest {

    private val schema = "public"

    // CAMEL_CASE_TO_SNAKE_CASE (not SERIAL_NAME, which is JVM/Android-only) so the test runs on the
    // iOS/Native target too. Column names here are already lowercase, so no conversion is applied.
    private val conversion = PropertyConversionMethod.CAMEL_CASE_TO_SNAKE_CASE

    /** Runs a descriptor through the same persist/restore cycle the mutation queue uses. */
    private fun storeAndRestore(descriptor: FilterDescriptor): FilterDescriptor {
        val encoded = encodeFilter(descriptor, EngineJson)
        return assertNotNull(decodeFilterOrNull(encoded, EngineJson))
    }

    /** Replays a descriptor onto a fresh request builder and returns the resulting params. */
    private fun replayParams(descriptor: FilterDescriptor): Map<String, List<String>> {
        val builder = SelectRequestBuilder(schema, conversion)
        replayOnto(builder, descriptor)
        return builder.params
    }

    private fun rowWithStatus(status: String): JsonObject = buildJsonObject {
        put("status", status)
    }

    @Test
    fun `in filter with multiple elements survives capture encode decode and replay`() {
        // Given a queued mutation captured from an isIn filter with several values
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { isIn("status", listOf("active", "pending", "archived")) }
            }
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When the descriptor is persisted, restored, and replayed
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then the replayed request carries the exact param the caller originally sent
        assertEquals(original.params["status"], replayed["status"])
        assertEquals(listOf("in.(active,pending,archived)"), replayed["status"])
    }

    @Test
    fun `in filter with a single element survives capture encode decode and replay`() {
        // Given the single-element case, where the corrupted form would be "(row-1)"
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { isIn("id", listOf("row-1")) }
            }
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then
        assertEquals(original.params["id"], replayed["id"])
        assertEquals(listOf("in.(row-1)"), replayed["id"])
    }

    @Test
    fun `overlay matching and replayed request agree on in filter values`() {
        // Given a captured isIn filter
        val descriptor =
            assertNotNull(
                captureFilterExactly(schema, conversion) {
                    filter { isIn("status", listOf("active", "pending")) }
                }
            )

        // When the same descriptor drives both the offline overlay and the replayed request
        val replayed = replayParams(descriptor)

        // Then the overlay matches exactly the captured values
        assertTrue(matches(rowWithStatus("active"), descriptor))
        assertTrue(matches(rowWithStatus("pending"), descriptor))
        assertFalse(matches(rowWithStatus("retired"), descriptor))
        assertFalse(matches(rowWithStatus("(active"), descriptor))
        assertFalse(matches(rowWithStatus("pending)"), descriptor))

        // And the replayed request targets those same values
        assertEquals(listOf("in.(active,pending)"), replayed["status"])
    }

    @Test
    fun `range filter value survives capture and replay with its wrapper intact`() {
        // Given a captured range filter, encoded by supabase-kt as "sl.(1,10)"
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { sl("distance", 1 to 10) }
            }
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then the replayed range matches the original instead of the corrupted Pair("(1" to "10)")
        assertEquals(original.params["distance"], replayed["distance"])
        assertEquals(listOf("sl.(1,10)"), replayed["distance"])
    }

    @Test
    fun `cs filter with braces survives capture and replay`() {
        // Given a captured contains filter, encoded by supabase-kt as "cs.{tag1,tag2}"
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { cs("tags", listOf("tag1", "tag2")) }
            }
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then
        assertEquals(original.params["tags"], replayed["tags"])
        assertEquals(listOf("cs.{tag1,tag2}"), replayed["tags"])
    }

    @Test
    fun `in filter with a quoted embedded-comma element survives capture and replay`() {
        // Given an isIn value containing a comma. supabase-kt's escapeValue quote-wraps any
        // element containing , . : ( ) — so this encodes as in.("north,south",east).
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { isIn("region", listOf("north,south", "east")) }
            }
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then the two-element filter replays intact, not as the three-element
        // in.(north,south,east)
        // that a naive comma split produces — that form targets a different set of rows.
        assertEquals(original.params["region"], replayed["region"])
        assertEquals(listOf("""in.("north,south",east)"""), replayed["region"])
    }

    @Test
    fun `in filter elements quoted for dots colons and parens survive capture and replay`() {
        // Given the rest of escapeValue's quote-triggering character set
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { isIn("key", listOf("plain", "has.dot", "has:colon", "has(paren)")) }
            }
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then every element round-trips, quotes and all
        assertEquals(original.params["key"], replayed["key"])
        assertEquals(
            listOf("""in.(plain,"has.dot","has:colon","has(paren)")"""),
            replayed["key"],
        )
    }

    @Test
    fun `overlay matching agrees with replay on a quoted embedded-comma element`() {
        // Given a captured isIn filter whose first element contains a comma
        val descriptor =
            assertNotNull(
                captureFilterExactly(schema, conversion) {
                    filter { isIn("status", listOf("on,hold", "active")) }
                }
            )

        // When the descriptor drives the offline overlay
        // Then it matches the two real values and neither torn half
        assertTrue(matches(rowWithStatus("on,hold"), descriptor))
        assertTrue(matches(rowWithStatus("active"), descriptor))
        assertFalse(matches(rowWithStatus("on"), descriptor))
        assertFalse(matches(rowWithStatus("hold"), descriptor))

        // And the replayed request targets that same pair
        assertEquals(listOf("""in.("on,hold",active)"""), replayParams(descriptor)["status"])
    }

    @Test
    fun `cs filter in its Pair-range form survives capture and replay`() {
        // Given a contains filter built from a Pair. supabase-kt routes that through
        // encodeOverlapAsRange, producing cs.[1,5] — a range, not the cs.{a,b} element list.
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { filter("span", FilterOperator.CS, 1 to 5) }
            }
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then the range replays as a range. Previously it hit parseArray's JSON branch, failed
        // to decode (unquoted tokens are not a JSON string array), yielded null, and the whole
        // condition was silently dropped — broadening the replayed mutation's target set.
        assertEquals(original.params["span"], replayed["span"])
        assertEquals(listOf("cs.[1,5]"), replayed["span"])
    }

    @Test
    fun `ov filter in its Pair-range form survives capture and replay`() {
        // Given the same range encoding under the overlaps operator
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { filter("window", FilterOperator.OV, "lo" to "hi") }
            }
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then
        assertEquals(original.params["window"], replayed["window"])
        assertEquals(listOf("ov.[lo,hi]"), replayed["window"])
    }

    @Test
    fun `ov filter in its list form still replays as a brace list`() {
        // Given the list form of the same operator — the range handling must not capture it
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { ov("tags", listOf("p", "q")) }
            }
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then it stays a brace-wrapped element list, not a bracket range
        assertEquals(original.params["tags"], replayed["tags"])
        assertEquals(listOf("ov.{p,q}"), replayed["tags"])
    }

    @Test
    fun `cs filter list element containing a backslash replays byte-exact`() {
        // Given a cs list element containing a backslash. encodeOverlapAsArray joins plain
        // toString() output — it never calls escapeValue — so the backslash is literal content.
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { cs("tags", listOf("""a\b""")) }
            }
        assertEquals(listOf("""cs.{a\b}"""), original.params["tags"])
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then the backslash survives. Unescaping it here — as the `in` tokenizer must — would
        // replay cs.{ab} and target a different array.
        assertEquals(original.params["tags"], replayed["tags"])
        assertEquals(listOf("""cs.{a\b}"""), replayed["tags"])
    }

    @Test
    fun `cs filter list element containing a double quote replays byte-exact`() {
        // Given a cs list element containing a bare double quote, again unescaped by the encoder
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { cs("tags", listOf("""a"b""")) }
            }
        assertEquals(listOf("""cs.{a"b}"""), original.params["tags"])
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then the quote survives instead of being read as an element delimiter and dropped
        assertEquals(original.params["tags"], replayed["tags"])
        assertEquals(listOf("""cs.{a"b}"""), replayed["tags"])
    }

    @Test
    fun `ov filter list element containing an escape sequence replays byte-exact`() {
        // Given the same corner under a second array operator, with both characters at once
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { ov("tags", listOf("""a\"b""")) }
            }
        assertEquals(listOf("""ov.{a\"b}"""), original.params["tags"])
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then the backslash-quote pair is preserved rather than collapsed to a bare quote
        assertEquals(original.params["tags"], replayed["tags"])
        assertEquals(listOf("""ov.{a\"b}"""), replayed["tags"])
    }

    @Test
    fun `range bound containing a double quote replays instead of being dropped`() {
        // Given a range bound containing a double quote. encodeAsRange interpolates both bounds
        // raw — "(${first},${second})" — so this too is literal content, not quoting.
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { sl("window", """a"b""" to "z") }
            }
        assertEquals(listOf("""sl.(a"b,z)"""), original.params["window"])
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then the range replays. Reading the quote as an opening delimiter swallows the comma,
        // leaves a single bound, and drops the condition — broadening the replayed mutation.
        assertEquals(original.params["window"], replayed["window"])
        assertEquals(listOf("""sl.(a"b,z)"""), replayed["window"])
    }

    @Test
    fun `range bound containing a backslash replays byte-exact`() {
        // Given a range bound containing a backslash
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { sl("window", """a\b""" to "z") }
            }
        assertEquals(listOf("""sl.(a\b,z)"""), original.params["window"])
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then the bound is unchanged rather than unescaped to sl.(ab,z)
        assertEquals(original.params["window"], replayed["window"])
        assertEquals(listOf("""sl.(a\b,z)"""), replayed["window"])
    }

    @Test
    fun `cs Pair-range bound containing a backslash replays byte-exact`() {
        // Given the bracket range form, built by encodeOverlapAsRange's raw interpolation
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { filter("span", FilterOperator.CS, """a\b""" to "z") }
            }
        assertEquals(listOf("""cs.[a\b,z]"""), original.params["span"])
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then
        assertEquals(original.params["span"], replayed["span"])
        assertEquals(listOf("""cs.[a\b,z]"""), replayed["span"])
    }

    @Test
    fun `overlay and replay agree on an in value shaped like a bracket range`() {
        // Given a stored `in` value carrying the bracket shape. It is not decodable JSON, so
        // parseArray yields null and replay drops the condition entirely.
        val descriptor =
            FilterDescriptor(
                conditions =
                    listOf(
                        FilterDescriptor.Condition(
                            column = "status",
                            operatorId = "in",
                            value = "[a,b]",
                        )
                    )
            )

        // When the same descriptor drives the overlay and the replayed request
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then the overlay claims no match either. Matching the bracket contents while replay
        // drops the condition is the dangerous disagreement: the overlay hides rows the replayed
        // mutation would then hit unfiltered.
        assertFalse(matches(rowWithStatus("a"), descriptor))
        assertFalse(matches(rowWithStatus("b"), descriptor))
        assertEquals(null, replayed["status"])
    }

    @Test
    fun `empty in filter matches no row in the overlay`() {
        // Given the captured match-nothing filter, encoded as in.()
        val descriptor =
            assertNotNull(
                captureFilterExactly(schema, conversion) {
                    filter { isIn("status", emptyList()) }
                }
            )

        // When the overlay evaluates rows against it
        // Then nothing matches — including a row whose column is the empty string, which a
        // tokenizer that reports one empty element would wrongly admit
        assertFalse(matches(rowWithStatus(""), descriptor))
        assertFalse(matches(rowWithStatus("active"), descriptor))
        assertEquals(listOf("in.()"), replayParams(descriptor)["status"])
    }

    @Test
    fun `empty in filter replays as a match-nothing filter instead of being dropped`() {
        // Given a captured isIn filter with no values, encoded by supabase-kt as "in.()"
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { isIn("status", emptyList<String>()) }
            }
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then the condition is preserved — dropping it would broaden the replayed mutation to
        // every row instead of the caller's original match-nothing target set
        assertEquals(original.params["status"], replayed["status"])
        assertEquals(listOf("in.()"), replayed["status"])
    }

    /* ─────────────────── replaysFully — the queued-mutation gate ─────────────────── */

    @Test
    fun `replaysFully is false when one condition of several is dropped`() {
        // Given a stored filter whose second condition cannot be parsed back (the bracket shape
        // is not decodable JSON) alongside a first condition that replays fine
        val descriptor =
            FilterDescriptor(
                conditions =
                    listOf(
                        FilterDescriptor.Condition(
                            column = "user_id",
                            operatorId = "eq",
                            value = "u1",
                        ),
                        FilterDescriptor.Condition(
                            column = "status",
                            operatorId = "in",
                            value = "[a,b]",
                        ),
                    )
            )

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then the replayed request carries only the surviving condition — an UPDATE issued from
        // it would rewrite every row of user u1 regardless of status — so the gate must say no
        assertEquals(listOf("eq.u1"), replayed["user_id"])
        assertEquals(null, replayed["status"])
        assertFalse(replaysFully(descriptor))
    }

    @Test
    fun `replaysFully is false for a range bound containing a comma`() {
        // Given a range whose first bound contains a comma. encodeAsRange interpolates both
        // bounds verbatim into "(${first},${second})", so the wire form is indistinguishable from
        // a three-token body and neither reading can be shown to be the caller's.
        val original =
            SelectRequestBuilder(schema, conversion).apply {
                filter { sl("window", "a,b" to "c") }
            }
        assertEquals(listOf("sl.(a,b,c)"), original.params["window"])
        val descriptor = assertNotNull(captureFilterExactly(original))

        // When
        val replayed = replayParams(storeAndRestore(descriptor))

        // Then the condition does not replay at all, rather than replaying as the wrong range
        // sl.(a,b) — and the gate reports the shortfall so the mutation is refused upstream
        assertEquals(null, replayed["window"])
        assertFalse(replaysFully(descriptor))
    }

    @Test
    fun `replaysFully is false for a descriptor carrying no conditions`() {
        // Given a stored descriptor with an empty condition list. Strict capture never produces
        // one — it returns null instead — so this can only be a corrupted or hand-written row.
        val descriptor = FilterDescriptor(conditions = emptyList())

        // When / Then: replaying it would issue the request with no filter at all, which is the
        // very thing the null-descriptor guard refuses. Vacuous truth must not open that door.
        assertEquals(emptyMap(), replayParams(descriptor))
        assertFalse(replaysFully(descriptor))
    }

    @Test
    fun `replaysFully is false for an unrecognized operator id`() {
        // Given a stored row whose operator no longer resolves (a corrupted or pre-strict row)
        val descriptor =
            FilterDescriptor(
                conditions =
                    listOf(
                        FilterDescriptor.Condition(
                            column = "status",
                            operatorId = "bogus",
                            value = "x",
                        )
                    )
            )

        // When / Then: nothing lands on the request, so the mutation must not be issued
        assertEquals(null, replayParams(descriptor)["status"])
        assertFalse(replaysFully(descriptor))
    }

    @Test
    fun `replaysFully is true for every shape this suite pins as replayable`() {
        // Given one descriptor per encoding the replay path claims to support. The gate is
        // fail-closed — a false negative silently drops a user's queued write — so this sweep is
        // the guard against tightening replay without noticing what it stopped accepting.
        val replayable =
            listOf(
                "eq" to
                    captureFilterExactly(schema, conversion) {
                        filter { eq("user_id", "u1") }
                    },
                "in multi" to
                    captureFilterExactly(schema, conversion) {
                        filter { isIn("status", listOf("a", "b", "c")) }
                    },
                "in empty" to
                    captureFilterExactly(schema, conversion) {
                        filter { isIn("status", emptyList<String>()) }
                    },
                "in embedded comma" to
                    captureFilterExactly(schema, conversion) {
                        filter { isIn("status", listOf("a,b", "c")) }
                    },
                "cs list" to
                    captureFilterExactly(schema, conversion) {
                        filter { cs("tags", listOf("a", "b")) }
                    },
                "cs range" to
                    captureFilterExactly(schema, conversion) {
                        filter { filter("span", FilterOperator.CS, "a" to "z") }
                    },
                "ov list" to
                    captureFilterExactly(schema, conversion) {
                        filter { ov("tags", listOf("a", "b")) }
                    },
                "sl range" to
                    captureFilterExactly(schema, conversion) {
                        filter { sl("window", "a" to "z") }
                    },
                "adj range" to
                    captureFilterExactly(schema, conversion) {
                        filter { adjacent("window", "a" to "z") }
                    },
                "is null" to
                    captureFilterExactly(schema, conversion) {
                        filter { exact("deleted_at", null) }
                    },
                "ilike" to
                    captureFilterExactly(schema, conversion) {
                        filter { ilike("name", "%foo%") }
                    },
            )

        // When / Then: each survives the store/restore cycle and replays in full
        replayable.forEach { (label, captured) ->
            val descriptor = assertNotNull(captured, "$label produced no descriptor")
            assertTrue(
                replaysFully(storeAndRestore(descriptor)),
                "$label must stay fully replayable",
            )
        }
    }

    /** Whether the overlay would treat [row] as matched by [descriptor]. */
    private fun matches(row: JsonObject, descriptor: FilterDescriptor): Boolean =
        rowMatch(row, descriptor) == RowMatch.MATCHES
}
