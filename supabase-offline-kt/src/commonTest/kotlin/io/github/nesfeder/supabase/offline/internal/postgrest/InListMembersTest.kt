// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * An `in` condition reads its list once, however many rows it judges, and a list looked up in a set
 * of its members answers exactly as the scan of them does.
 */
class InListMembersTest {

    @Test
    fun an_in_list_is_parsed_once_across_many_rows() {
        // Given — a delete of 100 ids against a payload of 1,000 rows, 100 of them listed
        var parses = 0
        val ids = List(100) { index -> uuid(index) }
        val condition =
            RowCondition(
                FilterDescriptor.Condition("id", "in", "(${ids.joinToString(",")})"),
                uuidColumns = setOf("id"),
                listParser = { value ->
                    parses++
                    inListMembersOf(value)
                },
            )
        val rows =
            List(1_000) { index ->
                // Every other row writes its id in capitals, as a Swift client does
                val id = uuid(index).let { if (index % 2 == 0) it else it.uppercase() }
                buildJsonObject { put("id", JsonPrimitive(id)) }
            }
        assertEquals(0, parses, "parsed before any row")

        // When
        val matched = rows.count { row -> condition.match(row) == RowMatch.MATCHES }

        // Then
        assertEquals(100, matched)
        assertEquals(1, parses, "parses over 1,000 rows")
    }

    @Test
    fun the_set_and_the_scan_agree_on_every_list_and_value() {
        // Given — lists of one kind, which the set answers, and lists only the scan can read
        val lists =
            listOf(
                UUIDS,
                NUMBERS,
                NUMBERS_IN_OTHER_FORMS,
                QUOTED_TEXTS,
                TEXTS_ALIKE_BUT_FOR_CASE,
                WITH_NULL,
                MIXED,
                "()",
            )

        // When / Then — wherever the set answers, it answers as the scan does, and the condition
        // answers as the scan does on every list
        val answeredBySet = mutableSetOf<String>()
        for (literal in lists) for (asUuid in listOf(true, false)) {
            val members = InListMembers(assertNotNull(inListMembersOf(literal)), asUuid)
            val condition =
                RowCondition(
                    FilterDescriptor.Condition("c", "in", literal),
                    uuidColumns = if (asUuid) setOf("c") else emptySet(),
                )
            for (value in VALUES) {
                val scanned = members.scan(value)
                val lookup = members.lookup(value)
                if (lookup != null) {
                    answeredBySet += literal
                    assertEquals(scanned, lookup, "$literal looked up for $value")
                }
                val row = buildJsonObject { put("c", value) }
                assertEquals(scanned, condition.match(row), "$literal against $value")
            }
        }
        assertEquals(setOf(UUIDS, NUMBERS, QUOTED_TEXTS, WITH_NULL), answeredBySet)
    }

    @Test
    fun the_set_answers_a_list_of_one_kind_and_the_scan_every_other() {
        // UUIDs in both cases, with and without hyphens or braces: one value on a `uuid` column
        val uuids = InListMembers(assertNotNull(inListMembersOf(UUIDS)), asUuid = true)
        assertEquals(RowMatch.MATCHES, assertNotNull(uuids.lookup(text(UUID_A.uppercase()))))
        assertEquals(RowMatch.MATCHES, assertNotNull(uuids.lookup(text("{$UUID_B}"))))
        assertEquals(RowMatch.EXCLUDED, assertNotNull(uuids.lookup(text(UUID_C))))
        assertNull(uuids.lookup(text(DIGITS_32)), "a 32-digit number is not a UUID")
        assertNull(
            InListMembers(assertNotNull(inListMembersOf(UUIDS)), asUuid = false)
                .lookup(text(UUID_A)),
            "a UUID on a column not read as uuid",
        )

        // Whole numbers: a number or a digit string of the same value matches, another excludes
        val numbers = InListMembers(assertNotNull(inListMembersOf(NUMBERS)), asUuid = false)
        assertEquals(RowMatch.MATCHES, assertNotNull(numbers.lookup(JsonPrimitive(10))))
        assertEquals(RowMatch.MATCHES, assertNotNull(numbers.lookup(text("10"))))
        assertEquals(RowMatch.EXCLUDED, assertNotNull(numbers.lookup(JsonPrimitive(11))))
        assertNull(numbers.lookup(JsonPrimitive(10.0)), "a fraction is scanned")
        assertNull(numbers.lookup(text("010")), "leading zeros are scanned")
        assertNull(
            InListMembers(assertNotNull(inListMembersOf(NUMBERS_IN_OTHER_FORMS)), false)
                .lookup(JsonPrimitive(10)),
            "a list with a number in another form is scanned",
        )

        // Quoted elements with commas: text, matched as written, unknown but for letter case
        val texts = InListMembers(assertNotNull(inListMembersOf(QUOTED_TEXTS)), asUuid = false)
        assertEquals(RowMatch.MATCHES, assertNotNull(texts.lookup(text("a,b"))))
        assertEquals(RowMatch.MATCHES, assertNotNull(texts.lookup(text("Home Office"))))
        assertEquals(RowMatch.UNKNOWN, assertNotNull(texts.lookup(text("A,B"))))
        assertEquals(RowMatch.EXCLUDED, assertNotNull(texts.lookup(text("a"))))
        assertNull(texts.lookup(text("Home Office ")), "a trailing space is scanned")
        assertNull(
            InListMembers(assertNotNull(inListMembersOf(TEXTS_ALIKE_BUT_FOR_CASE)), false)
                .lookup(text("run")),
            "a list with two texts alike but for case is scanned",
        )

        // A `null` member: SQL's NULL, or the text "null", which only the value can tell
        val withNull = InListMembers(assertNotNull(inListMembersOf(WITH_NULL)), asUuid = false)
        assertEquals(RowMatch.MATCHES, assertNotNull(withNull.lookup(text("a"))))
        assertEquals(RowMatch.EXCLUDED, assertNotNull(withNull.lookup(text("c"))))
        assertEquals(RowMatch.UNKNOWN, assertNotNull(withNull.lookup(text("null"))))
        assertEquals(RowMatch.UNKNOWN, assertNotNull(withNull.lookup(text("NULL"))))
        assertNull(withNull.lookup(JsonPrimitive(1)), "a number against texts is scanned")

        // A mixed list is scanned whatever the value
        val mixed = InListMembers(assertNotNull(inListMembersOf(MIXED)), asUuid = true)
        VALUES.forEach { value -> assertNull(mixed.lookup(value), "$value") }
    }

    private fun text(value: String) = JsonPrimitive(value)

    /** A UUID with [number] as its first eight digits. */
    private fun uuid(number: Int): String =
        "${number.toString(16).padStart(8, '0')}-0000-4000-8000-000000000000"

    private companion object {
        const val UUID_A = "3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f"
        const val UUID_B = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
        const val UUID_C = "8f14e45f-ceea-467f-a0e5-9ab1c2d3e4f5"
        const val DIGITS_32 = "12345678901234567890123456789012"

        /** Two UUIDs, each written twice: in capitals, in braces, without hyphens. */
        val UUIDS = "(${UUID_A.uppercase()},{$UUID_B},${UUID_A.replace("-", "")},$UUID_B)"
        const val NUMBERS = "(10,20,0,300)"
        const val NUMBERS_IN_OTHER_FORMS = "(10,1e1,010,20.0,-5)"
        const val QUOTED_TEXTS = """("a,b","c,d",Groceries,"Home Office")"""
        const val TEXTS_ALIKE_BUT_FOR_CASE = "(Walk,walk,run)"
        const val WITH_NULL = "(a,null,b)"
        val MIXED =
            "(" +
                listOf(UUIDS, NUMBERS, NUMBERS_IN_OTHER_FORMS, QUOTED_TEXTS, WITH_NULL)
                    .joinToString(",") { list -> list.removeSurrounding("(", ")") } +
                ")"

        /** Values of every shape `in` reads, and some it does not. */
        val VALUES: List<JsonElement> =
            listOf(
                JsonPrimitive(UUID_A),
                JsonPrimitive(UUID_A.uppercase()),
                JsonPrimitive("{${UUID_A.replace("-", "").uppercase()}}"),
                JsonPrimitive(UUID_B),
                JsonPrimitive(UUID_C),
                JsonPrimitive(DIGITS_32),
                JsonPrimitive(10),
                JsonPrimitive(10L),
                JsonPrimitive("10"),
                JsonPrimitive(10.0),
                JsonPrimitive("1e1"),
                JsonPrimitive("010"),
                JsonPrimitive(20),
                JsonPrimitive(20.0f),
                JsonPrimitive("0"),
                JsonPrimitive(-0.0),
                JsonPrimitive(-5),
                JsonPrimitive(300),
                JsonPrimitive(11),
                JsonPrimitive("a,b"),
                JsonPrimitive("A,B"),
                JsonPrimitive("Groceries"),
                JsonPrimitive("groceries"),
                JsonPrimitive("Home Office"),
                JsonPrimitive("Home Office "),
                JsonPrimitive("walk"),
                JsonPrimitive("Walk"),
                JsonPrimitive("WALK"),
                JsonPrimitive("run"),
                JsonPrimitive("a"),
                JsonPrimitive("c"),
                JsonPrimitive("null"),
                JsonPrimitive("NULL"),
                JsonPrimitive("Null"),
                JsonPrimitive(true),
                JsonPrimitive("true"),
                JsonPrimitive("t"),
                JsonPrimitive(""),
                JsonPrimitive("abc"),
                JsonPrimitive("today"),
                JsonPrimitive("ab:cd:ef:01:02:03"),
                JsonPrimitive("2026-01-01"),
                JsonPrimitive("\"a,b\""),
                JsonNull,
                buildJsonArray { add(JsonPrimitive("a")) },
                buildJsonObject { put("k", JsonPrimitive("a")) },
            )
    }
}
