// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * An `in` condition reads its list once, however many rows its filter judges, and a list looked up
 * in a set of its members answers exactly as `eq` on each member does.
 */
class InListMembersTest {

    @Test
    fun an_in_list_is_parsed_once_across_many_rows() {
        // Given — a delete of 100 ids against a payload of 1,000 rows, 100 of them listed, judged
        // by one filter as the overlay judges a payload
        var parses = 0
        val ids = List(100) { index -> uuid(index) }
        val filter =
            RowFilter(
                FilterDescriptor(
                    conditions =
                        listOf(FilterDescriptor.Condition("id", "in", "(${ids.joinToString(",")})"))
                ),
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
        val matched = rows.count { row -> filter.match(row) == RowMatch.MATCHES }

        // Then
        assertEquals(100, matched)
        assertEquals(1, parses, "parses over 1,000 rows")
    }

    @Test
    fun the_set_and_the_scan_answer_as_eq_on_each_member_does() {
        // Given — lists of one kind, which the set answers, and lists only the scan can read
        val lists =
            listOf(
                UUIDS,
                UUIDS_WITH_A_NUMBER,
                NUMBERS,
                NUMBERS_IN_OTHER_FORMS,
                SIGNED_AND_FRACTIONAL_ZEROS,
                QUOTED_TEXTS,
                ESCAPED_TEXTS,
                TEXTS_ALIKE_BUT_FOR_CASE,
                TEXTS_ALIKE_BUT_FOR_NON_ASCII_CASE,
                BOOLEAN_LETTERS,
                BOOLEAN_WORDS,
                WITH_NULL,
                MIXED,
                "()",
            )

        // When / Then — the lookup where it answers, the scan, and the condition each answer as
        // `eq` on each member does, combined as SQL's OR
        val answeredBySet = mutableSetOf<String>()
        for (literal in lists) for (asUuid in listOf(true, false)) {
            val memberTexts = assertNotNull(inListMembersOf(literal))
            val members = InListMembers(memberTexts, asUuid)
            val uuidColumns = if (asUuid) setOf(COLUMN) else emptySet()
            val condition =
                RowCondition(FilterDescriptor.Condition(COLUMN, "in", literal), uuidColumns)
            for (value in VALUES) {
                val row = buildJsonObject { put(COLUMN, value) }
                val expected = eqOnEachMember(row, memberTexts, uuidColumns)
                val where = "$literal against $value, asUuid=$asUuid"
                members.lookup(value)?.let { lookup ->
                    answeredBySet += literal
                    assertEquals(expected, lookup, "lookup: $where")
                }
                assertEquals(expected, members.scan(value), "scan: $where")
                assertEquals(expected, condition.match(row), "condition: $where")
            }
        }
        assertEquals(setOf(UUIDS, NUMBERS, QUOTED_TEXTS, ESCAPED_TEXTS, WITH_NULL), answeredBySet)
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
        assertNull(
            InListMembers(assertNotNull(inListMembersOf(UUIDS_WITH_A_NUMBER)), asUuid = true)
                .lookup(text(UUID_A)),
            "a list of UUIDs with a 32-digit number is scanned",
        )

        // Whole numbers: a number or a digit string of the same value matches, another excludes
        val numbers = InListMembers(assertNotNull(inListMembersOf(NUMBERS)), asUuid = false)
        assertEquals(RowMatch.MATCHES, assertNotNull(numbers.lookup(JsonPrimitive(10))))
        assertEquals(RowMatch.MATCHES, assertNotNull(numbers.lookup(text("10"))))
        assertEquals(RowMatch.EXCLUDED, assertNotNull(numbers.lookup(JsonPrimitive(11))))
        assertEquals(RowMatch.EXCLUDED, assertNotNull(numbers.lookup(text("1"))))
        assertNull(numbers.lookup(JsonPrimitive(10.0)), "a fraction is scanned")
        assertNull(numbers.lookup(text("010")), "leading zeros are scanned")
        assertNull(numbers.lookup(JsonPrimitive(true)), "a boolean is scanned")
        for (list in listOf(NUMBERS_IN_OTHER_FORMS, SIGNED_AND_FRACTIONAL_ZEROS)) {
            assertNull(
                InListMembers(assertNotNull(inListMembersOf(list)), false)
                    .lookup(JsonPrimitive(10)),
                "$list, a list with a number in another form, is scanned",
            )
        }

        // Text, quoted elements with commas and escapes among it: matched as written, unknown
        // but for letter case
        val texts = InListMembers(assertNotNull(inListMembersOf(QUOTED_TEXTS)), asUuid = false)
        assertEquals(RowMatch.MATCHES, assertNotNull(texts.lookup(text("a,b"))))
        assertEquals(RowMatch.MATCHES, assertNotNull(texts.lookup(text("Home Office"))))
        assertEquals(RowMatch.UNKNOWN, assertNotNull(texts.lookup(text("A,B"))))
        assertEquals(RowMatch.EXCLUDED, assertNotNull(texts.lookup(text("a"))))
        assertNull(texts.lookup(text("Home Office ")), "a trailing space is scanned")
        assertNull(texts.lookup(text("f")), "a boolean word is scanned")
        val escaped = InListMembers(assertNotNull(inListMembersOf(ESCAPED_TEXTS)), asUuid = false)
        assertEquals(RowMatch.MATCHES, assertNotNull(escaped.lookup(text("a\"b"))))
        assertEquals(RowMatch.MATCHES, assertNotNull(escaped.lookup(text("c\\d"))))
        assertEquals(RowMatch.EXCLUDED, assertNotNull(escaped.lookup(text("ab"))))
        for (list in
            listOf(
                TEXTS_ALIKE_BUT_FOR_CASE,
                TEXTS_ALIKE_BUT_FOR_NON_ASCII_CASE,
                BOOLEAN_LETTERS,
                BOOLEAN_WORDS,
            )) {
            assertNull(
                InListMembers(assertNotNull(inListMembersOf(list)), false).lookup(text("run")),
                "$list is scanned",
            )
        }

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

    /**
     * What `in` means, written out without the code under test: `eq` on each member, combined as
     * SQL's OR; a `null` member is unknown for the text "null" and excludes any other value.
     */
    private fun eqOnEachMember(
        row: JsonObject,
        members: List<String>,
        uuidColumns: Set<String>,
    ): RowMatch =
        members.fold(RowMatch.EXCLUDED) { combined, member ->
            val match =
                if (member.equals("null", ignoreCase = true)) {
                    val value = row.getValue(COLUMN)
                    if (
                        value is JsonPrimitive &&
                            value.isString &&
                            value.content.equals("null", ignoreCase = true)
                    )
                        RowMatch.UNKNOWN
                    else RowMatch.EXCLUDED
                } else {
                    rowMatch(row, FilterDescriptor.Condition(COLUMN, "eq", member), uuidColumns)
                }
            combined or match
        }

    private fun text(value: String) = JsonPrimitive(value)

    /** A UUID with [number] as its first eight digits. */
    private fun uuid(number: Int): String =
        "${number.toString(16).padStart(8, '0')}-0000-4000-8000-000000000000"

    private companion object {
        const val COLUMN = "c"
        const val UUID_A = "3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f"
        const val UUID_B = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
        const val UUID_C = "8f14e45f-ceea-467f-a0e5-9ab1c2d3e4f5"
        const val DIGITS_32 = "12345678901234567890123456789012"

        /** Two UUIDs, each written twice: in capitals, in braces, without hyphens. */
        val UUIDS = "(${UUID_A.uppercase()},{$UUID_B},${UUID_A.replace("-", "")},$UUID_B)"
        val UUIDS_WITH_A_NUMBER = "($UUID_A,$DIGITS_32,$UUID_B)"
        const val NUMBERS = "(10,20,0,300)"
        const val NUMBERS_IN_OTHER_FORMS = "(10,1e1,010,20.0,-5)"
        const val SIGNED_AND_FRACTIONAL_ZEROS = "(-0,0.0,1.0)"
        const val QUOTED_TEXTS = """("a,b","c,d",Groceries,"Home Office")"""

        /** Elements with an escaped quote and an escaped backslash, as supabase-kt writes them. */
        const val ESCAPED_TEXTS = """("a\"b","c\\d")"""
        const val TEXTS_ALIKE_BUT_FOR_CASE = "(Walk,walk,run)"
        const val TEXTS_ALIKE_BUT_FOR_NON_ASCII_CASE = "(café,cafÉ)"
        const val BOOLEAN_LETTERS = "(t,f)"
        const val BOOLEAN_WORDS = "(true,yes)"
        const val WITH_NULL = "(a,null,b)"
        val MIXED =
            "(" +
                listOf(
                        UUIDS,
                        NUMBERS,
                        NUMBERS_IN_OTHER_FORMS,
                        QUOTED_TEXTS,
                        BOOLEAN_WORDS,
                        WITH_NULL,
                    )
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
                JsonPrimitive("-0"),
                JsonPrimitive(-0.0),
                JsonPrimitive(0.0),
                JsonPrimitive(1),
                JsonPrimitive("1"),
                JsonPrimitive(1.0),
                JsonPrimitive(-5),
                JsonPrimitive(300),
                JsonPrimitive(11),
                JsonPrimitive("a,b"),
                JsonPrimitive("A,B"),
                JsonPrimitive("\"a,b\""),
                JsonPrimitive("a\"b"),
                JsonPrimitive("c\\d"),
                JsonPrimitive("ab"),
                JsonPrimitive("Groceries"),
                JsonPrimitive("groceries"),
                JsonPrimitive("Home Office"),
                JsonPrimitive("Home Office "),
                JsonPrimitive("walk"),
                JsonPrimitive("Walk"),
                JsonPrimitive("WALK"),
                JsonPrimitive("run"),
                JsonPrimitive("café"),
                JsonPrimitive("cafÉ"),
                JsonPrimitive("CAFÉ"),
                JsonPrimitive("a"),
                JsonPrimitive("c"),
                JsonPrimitive("null"),
                JsonPrimitive("NULL"),
                JsonPrimitive("Null"),
                JsonPrimitive(true),
                JsonPrimitive(false),
                JsonPrimitive("true"),
                JsonPrimitive("t"),
                JsonPrimitive("f"),
                JsonPrimitive("yes"),
                JsonPrimitive(""),
                JsonPrimitive("abc"),
                JsonPrimitive("today"),
                JsonPrimitive("ab:cd:ef:01:02:03"),
                JsonPrimitive("2026-01-01"),
                JsonNull,
                buildJsonArray { add(JsonPrimitive("a")) },
                buildJsonObject { put("k", JsonPrimitive("a")) },
            )
    }
}
