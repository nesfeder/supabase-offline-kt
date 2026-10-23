// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * What the engine can tell about a row against a filter. [EXCLUDED] covers both a false comparison
 * and one with a null (SQL's unknown), since a `WHERE` clause drops the row either way.
 */
internal enum class RowMatch {
    /** The server would return the row. */
    MATCHES,

    /** The server would not return the row. */
    EXCLUDED,

    /** The engine does not evaluate the condition, or cannot read the row's value for it. */
    UNKNOWN;

    /** Both this and [other] must match: SQL's `AND`. */
    infix fun and(other: RowMatch): RowMatch =
        when {
            this == EXCLUDED || other == EXCLUDED -> EXCLUDED
            this == UNKNOWN || other == UNKNOWN -> UNKNOWN
            else -> MATCHES
        }

    /** Either this or [other] must match: SQL's `OR`. */
    infix fun or(other: RowMatch): RowMatch =
        when {
            this == MATCHES || other == MATCHES -> MATCHES
            this == UNKNOWN || other == UNKNOWN -> UNKNOWN
            else -> EXCLUDED
        }
}

/** The operators [rowMatch] evaluates; every other one is [RowMatch.UNKNOWN]. */
internal val EVALUATED_OPERATORS: Set<FilterOperator> =
    setOf(FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.IN, FilterOperator.IS)

/**
 * [filter] against [row]: its conditions combined by its mode. Code judging many rows builds one
 * [RowFilter] and keeps it: this builds one for the row.
 *
 * @param uuidColumns columns read as Postgres reads a `uuid`, such as a table's key: one UUID in
 *   any letter case, braces or hyphens is one value.
 */
internal fun rowMatch(
    row: JsonObject,
    filter: FilterDescriptor,
    uuidColumns: Set<String> = emptySet(),
): RowMatch = RowFilter(filter, uuidColumns).match(row)

/**
 * [filter] made ready to judge many rows, as [rowMatch] does: each condition's operator is
 * resolved, and its column read, once rather than for every row, and what its value needs parsed
 * ([RowCondition]) is parsed once.
 *
 * @param uuidColumns see [rowMatch].
 * @param listParser see [RowCondition].
 */
internal class RowFilter(
    filter: FilterDescriptor,
    uuidColumns: Set<String> = emptySet(),
    listParser: (String) -> List<String>? = ::inListMembersOf,
) {

    private val isAnd = filter.mode == FilterDescriptor.Mode.AND
    private val conditions =
        filter.conditions.map { condition -> RowCondition(condition, uuidColumns, listParser) }

    /**
     * Whether [match] can answer [RowMatch.MATCHES] for some row: not when every row needs a
     * condition the engine never evaluates.
     */
    val canMatch: Boolean =
        if (isAnd) conditions.all { condition -> condition.isEvaluated }
        else conditions.any { condition -> condition.isEvaluated }

    /** What the engine can tell about [row] against the filter. */
    fun match(row: JsonObject): RowMatch {
        var combined = if (isAnd) RowMatch.MATCHES else RowMatch.EXCLUDED
        for (condition in conditions) {
            val match = condition.match(row)
            combined = if (isAnd) combined and match else combined or match
        }
        return combined
    }
}

/**
 * One condition made ready to judge many rows; see [rowMatch]. What the comparisons derive from the
 * condition's value they derive once, on the first row that needs it, rather than for every row: an
 * `in` list's members ([InListMembers]), a literal's decimal value and boolean reading ([Literal]).
 *
 * @param uuidColumns see [rowMatch].
 * @param listParser reads an `in` list's members, `null` for a value that is not a list:
 *   [inListMembersOf], or a test's count of its calls.
 */
internal class RowCondition(
    condition: FilterDescriptor.Condition,
    uuidColumns: Set<String>,
    listParser: (String) -> List<String>? = ::inListMembersOf,
) {

    private val column = condition.column
    private val literal = Literal(condition.value)
    private val asUuid = column in uuidColumns

    /** The operator, or `null` when the engine does not evaluate this condition at all. */
    private val operator: FilterOperator? =
        condition.operator?.takeIf { it in EVALUATED_OPERATORS && isPlainColumn(column) }

    /**
     * An `in` list's members, read by the parser replay uses so the overlay and the replayed
     * request see the same members, and kept for every row after the first; `null` when the value
     * is not a list.
     */
    private val members: InListMembers? by lazy {
        listParser(literal.text)?.let { members -> InListMembers(members, asUuid) }
    }

    /** Whether the engine evaluates this condition for some row; otherwise it is always unknown. */
    val isEvaluated: Boolean
        get() = operator != null

    /** What the engine can tell about [row] against the condition. */
    fun match(row: JsonObject): RowMatch {
        val evaluated = operator ?: return RowMatch.UNKNOWN
        val rowValue = row[column] ?: return RowMatch.UNKNOWN
        return when (evaluated) {
            FilterOperator.IS -> isMatch(rowValue, literal.text)
            FilterOperator.EQ -> equalityOf(rowValue, literal, asUuid)
            FilterOperator.NEQ ->
                when (rowValue) {
                    JsonNull -> RowMatch.EXCLUDED
                    else -> equalityOf(rowValue, literal, asUuid).negated()
                }
            FilterOperator.IN -> membershipOf(rowValue)
            else -> RowMatch.UNKNOWN
        }
    }

    /** `in`: a member matches; a null column, or no member when none is unknown, excludes. */
    private fun membershipOf(value: JsonElement): RowMatch {
        if (value == JsonNull) return RowMatch.EXCLUDED
        return members?.membership(value) ?: RowMatch.UNKNOWN
    }
}

/**
 * A condition's value, or a member of its `in` list, with what [equalityOf] derives from it derived
 * once rather than for every row.
 */
private class Literal(val text: String) {

    /** [text] as a decimal, or `null` when it is not one; see [decimalOf]. */
    val decimal: DecimalValue? by lazy { decimalOf(text) }

    /**
     * [text] as Postgres reads a boolean, or `null` when it reads none; see
     * [postgresBooleanOrNull].
     */
    val boolean: Boolean? by lazy { postgresBooleanOrNull(text) }
}

/**
 * An `in` list's members, prepared once to judge many rows. A row's value is looked up among them
 * when `eq` between it and each member is decided by content ([equalityDecidedByContent]): the
 * members all of one kind, whole numbers, UUIDs of a `uuid` column or plain text with no two alike
 * but for letter case, and the value of that kind too. Otherwise the members are scanned through
 * [equalityOf], as every list was before the lookup, which answers as the scan would.
 */
internal class InListMembers(members: List<String>, private val asUuid: Boolean) {

    /** Every member but `null`, in list order, with what [equalityOf] derives from each. */
    private val compared: List<Literal>

    /**
     * Whether the list holds `null`, which may be SQL's NULL or the text "null": only the row's
     * value can say ([nullMemberMatch]).
     */
    private val hasNull: Boolean

    /** The kind of every compared member, when `eq` on them is decided by content; else `null`. */
    private val kind: EqualityKind?

    /** Each compared member by its [keyOf], when [kind] is set. */
    private val byKey: Map<String, String>

    init {
        val compared = ArrayList<Literal>(members.size)
        val byKey = HashMap<String, String>()
        var hasNull = false
        var kind: EqualityKind? = null
        var decided = true
        for (member in members) {
            if (member.equals(NULL_LITERAL, ignoreCase = true)) {
                hasNull = true
                continue
            }
            compared += Literal(member)
            if (!decided) continue
            val memberKind = equalityKindOf(JsonPrimitive(member), asUuid)
            if (memberKind == null || (kind != null && memberKind != kind)) {
                decided = false
                continue
            }
            kind = memberKind
            val alike = byKey.put(keyOf(member, memberKind), member)
            // Two texts alike but for letter case: `eq` on them is unknown, so no set can answer
            if (memberKind == EqualityKind.TEXT && alike != null && alike != member) decided = false
        }
        this.compared = compared
        this.hasNull = hasNull
        this.kind = kind.takeIf { decided }
        this.byKey = if (decided) byKey else emptyMap()
    }

    /** What the members tell about [value], a column that is not null: [lookup], else [scan]. */
    fun membership(value: JsonElement): RowMatch = lookup(value) ?: scan(value)

    /**
     * [value] looked up among the members, or `null` when the lookup cannot answer: the members are
     * not all of one kind decided by content, or [value] is not of that kind. [scan] answers then.
     */
    internal fun lookup(value: JsonElement): RowMatch? {
        val kind = kind ?: return null
        if (equalityKindOf(value, asUuid) != kind) return null
        val content = (value as JsonPrimitive).content
        val member = byKey[keyOf(content, kind)]
        val match =
            when {
                member == null -> RowMatch.EXCLUDED
                // One text but for letter case: a case-insensitive type reads the two as equal
                kind == EqualityKind.TEXT && member != content -> RowMatch.UNKNOWN
                else -> RowMatch.MATCHES
            }
        return withNullMember(value, match)
    }

    /** [value] against each member in turn, through [equalityOf]. */
    internal fun scan(value: JsonElement): RowMatch {
        var combined = RowMatch.EXCLUDED
        for (member in compared) {
            combined = combined or equalityOf(value, member, asUuid)
            if (combined == RowMatch.MATCHES) break
        }
        return withNullMember(value, combined)
    }

    /** [match], the compared members' answer, with the `null` member's when the list has one. */
    private fun withNullMember(value: JsonElement, match: RowMatch): RowMatch =
        if (hasNull && match != RowMatch.MATCHES) match or nullMemberMatch(value) else match

    /**
     * [content]'s key among members of [kind]: a UUID by its digits in lower case, text in lower
     * case, a whole number as written.
     */
    private fun keyOf(content: String, kind: EqualityKind): String =
        when (kind) {
            EqualityKind.WHOLE_NUMBER -> content
            EqualityKind.UUID -> uuidDigitsOf(content)
            EqualityKind.TEXT -> content.lowercase()
        }
}

/**
 * [condition] against [row], as Postgres would evaluate it: `eq`, `neq`, `in` and `is`, with null
 * semantics. Anything else is [RowMatch.UNKNOWN]: another operator, a column the row does not carry
 * (a server default, a projection, a JSON path or a cast), an array or object value, and a literal
 * that some column type could read as equal to a different-looking value.
 */
internal fun rowMatch(
    row: JsonObject,
    condition: FilterDescriptor.Condition,
    uuidColumns: Set<String> = emptySet(),
): RowMatch = RowCondition(condition, uuidColumns).match(row)

/**
 * A column named directly: not a referenced table's (`a.b`), a JSON path (`a->b`) or a cast
 * (`a::t`).
 */
internal fun isPlainColumn(column: String): Boolean =
    column.isNotEmpty() && '.' !in column && "->" !in column && "::" !in column && '(' !in column

/** `eq`: a null compares unknown, which excludes. [asUuid] reads UUIDs as a `uuid` column does. */
private fun equalityOf(value: JsonElement, literal: Literal, asUuid: Boolean): RowMatch =
    when {
        value == JsonNull -> RowMatch.EXCLUDED
        value !is JsonPrimitive -> RowMatch.UNKNOWN
        value.isString && asUuid && isUuid(value.content) && isUuid(literal.text) ->
            if (sameUuidDigits(value.content, literal.text)) RowMatch.MATCHES else RowMatch.EXCLUDED
        value.isString -> textEquality(value.content, literal.text)
        value.booleanOrNull != null -> booleanEquality(value.content.toBoolean(), literal)
        else -> numberEquality(value.content, literal)
    }

private fun RowMatch.negated(): RowMatch =
    when (this) {
        RowMatch.MATCHES -> RowMatch.EXCLUDED
        RowMatch.EXCLUDED -> RowMatch.MATCHES
        RowMatch.UNKNOWN -> RowMatch.UNKNOWN
    }

/**
 * A `null` member of an `in` list against a non-null [value]: never a match for a non-text value.
 */
private fun nullMemberMatch(value: JsonElement): RowMatch =
    if (
        value is JsonPrimitive &&
            value.isString &&
            value.content.equals(NULL_LITERAL, ignoreCase = true)
    )
        RowMatch.UNKNOWN
    else RowMatch.EXCLUDED

/**
 * `is`: `null`, `not_null`, `true`, `false` and `unknown`; any other value is not evaluated. A JSON
 * null is read as SQL's NULL: a `jsonb` column holding the JSON value `null` is not NULL to
 * Postgres, and the engine cannot tell the two apart.
 */
private fun isMatch(value: JsonElement, literal: String): RowMatch {
    val isNull = value == JsonNull
    val boolean = (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    return when (literal.lowercase()) {
        NULL_LITERAL -> if (isNull) RowMatch.MATCHES else RowMatch.EXCLUDED
        "not_null" -> if (isNull) RowMatch.EXCLUDED else RowMatch.MATCHES
        "true",
        "false" ->
            when {
                isNull -> RowMatch.EXCLUDED
                boolean == null -> RowMatch.UNKNOWN
                else ->
                    if (boolean == literal.equals("true", ignoreCase = true)) RowMatch.MATCHES
                    else RowMatch.EXCLUDED
            }
        "unknown" ->
            when {
                isNull -> RowMatch.MATCHES
                boolean == null -> RowMatch.UNKNOWN
                else -> RowMatch.EXCLUDED
            }
        else -> RowMatch.UNKNOWN
    }
}

/** A boolean column against [literal], read the way Postgres reads a boolean literal. */
private fun booleanEquality(value: Boolean, literal: Literal): RowMatch {
    val parsed = literal.boolean ?: return RowMatch.UNKNOWN
    return if (parsed == value) RowMatch.MATCHES else RowMatch.EXCLUDED
}

/**
 * Postgres's boolean input: `t`/`true`/`yes`/`on`/`1` and their opposites, or an unambiguous
 * prefix.
 */
private fun postgresBooleanOrNull(literal: String): Boolean? {
    // Every boolean word starts with one of these: anything else is told apart without allocating
    val first = literal.firstOrNull { !it.isWhitespace() }?.lowercaseChar() ?: return null
    if (BOOLEAN_FIRST_LETTERS.indexOf(first) < 0) return null
    val word = literal.trim().lowercase()
    if (word.isEmpty()) return null
    // The one reading of every word the input starts; none, or two, is no boolean
    var reading: Boolean? = null
    for ((candidate, meaning) in BOOLEAN_WORDS) {
        if (!candidate.startsWith(word)) continue
        if (reading != null && reading != meaning) return null
        reading = meaning
    }
    return reading
}

/**
 * A number column against [literal], compared by value: `5.0` equals `5`, `1e2` equals `100`. Two
 * fractions a `double precision` or `real` column reads as one value are unknown: `numeric` tells
 * them apart.
 */
private fun numberEquality(value: String, literal: Literal): RowMatch {
    val right = literal.decimal ?: return RowMatch.UNKNOWN
    val left = decimalOf(value) ?: return RowMatch.UNKNOWN
    return when {
        left == right -> RowMatch.MATCHES
        (left.exponent < 0 || right.exponent < 0) && equalAsFloatingPoint(value, literal.text) ->
            RowMatch.UNKNOWN
        else -> RowMatch.EXCLUDED
    }
}

/** Whether [value] and [literal] read as the same `double precision`, or the same `real`. */
private fun equalAsFloatingPoint(value: String, literal: String): Boolean {
    val left = value.trim().toDoubleOrNull() ?: return false
    val right = literal.trim().toDoubleOrNull() ?: return false
    return left == right || left.toFloat() == right.toFloat()
}

/**
 * A text value against [literal]. Equal strings match; ones some type reads as equal are unknown:
 * equal but for letter case or trailing spaces, digits but for leading zeros, boolean words, a
 * quoted literal (a `jsonb` string), one MAC address. Otherwise text-shaped output compares as
 * text, so a different ASCII literal excludes; two different UUIDs, digit strings or ISO dates
 * exclude; anything else is unknown.
 */
private fun textEquality(value: String, literal: String): RowMatch =
    // Cheapest checks first, and none that allocates unless its shape can apply: this runs for
    // every cached row against every pending UPDATE or DELETE
    when {
        value == literal -> RowMatch.MATCHES
        equalButForCaseOrTrailingSpaces(value, literal) -> RowMatch.UNKNOWN
        isQuotedForm(literal, value) -> RowMatch.UNKNOWN
        literal.length in UUID_LENGTHS && isUuid(value) && isUuid(literal) ->
            if (sameUuidDigits(value, literal)) RowMatch.UNKNOWN else RowMatch.EXCLUDED
        value.isAllDigits() && literal.isAllDigits() ->
            if (value.trimStart('0') == literal.trimStart('0')) RowMatch.UNKNOWN
            else RowMatch.EXCLUDED
        value.length <= LONGEST_BOOLEAN_WORD &&
            literal.length <= LONGEST_BOOLEAN_WORD &&
            sameBooleanWord(value, literal) -> RowMatch.UNKNOWN
        value.length in MAC_LENGTHS &&
            literal.length in MAC_LENGTHS &&
            sameMacAddress(value, literal) -> RowMatch.UNKNOWN
        isTextShaped(value) && literal.all { it in ' '..'~' } -> RowMatch.EXCLUDED
        value.length == ISO_DATE_LENGTH &&
            literal.length == ISO_DATE_LENGTH &&
            ISO_DATE.matches(value) &&
            ISO_DATE.matches(literal) -> RowMatch.EXCLUDED
        else -> RowMatch.UNKNOWN
    }

/**
 * Whether `eq` on a column, between any two of [values] (a row's value, or a literal written as
 * one), matches exactly when they are written alike — one UUID in any case, for a column read as
 * `uuid` when [asUuid] — and excludes otherwise, never unknown. So only when every value is of one
 * kind — whole numbers not below zero (JSON numbers or digit strings), UUIDs of a `uuid` column, or
 * plain text — and no two texts differ in letter case alone.
 */
internal fun equalityDecidedByContent(values: Sequence<JsonElement?>, asUuid: Boolean): Boolean {
    var kind: EqualityKind? = null
    val folded = HashMap<String, String>()
    for (value in values) {
        val next = equalityKindOf(value, asUuid) ?: return false
        if (kind != null && kind != next) return false
        kind = next
        if (next == EqualityKind.TEXT) {
            val content = (value as JsonPrimitive).content
            if (folded.getOrPut(content.lowercase()) { content } != content) return false
        }
    }
    return true
}

/** The kinds of value [equalityDecidedByContent] compares by content. */
private enum class EqualityKind {
    WHOLE_NUMBER,
    UUID,
    TEXT,
}

/**
 * [value]'s [EqualityKind], or `null` for one `eq` may judge unknown against a value of its kind: a
 * missing or null value, an array, object or boolean, a fraction, or text that some type reads
 * otherwise (see [textEquality]).
 */
private fun equalityKindOf(value: JsonElement?, asUuid: Boolean): EqualityKind? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    val content = primitive.content
    if (!primitive.isString) {
        if (primitive.booleanOrNull != null) return null
        return if (isWholeNumber(content)) EqualityKind.WHOLE_NUMBER else null
    }
    return when {
        isWholeNumber(content) -> EqualityKind.WHOLE_NUMBER
        isUuid(content) -> if (asUuid) EqualityKind.UUID else null
        isTextShaped(content) &&
            content.trimmedLength() == content.length &&
            postgresBooleanOrNull(content) == null &&
            macDigitsOrNull(content) == null -> EqualityKind.TEXT
        else -> null
    }
}

/** Whether [text] is a whole number not below zero, written without leading zeros. */
private fun isWholeNumber(text: String): Boolean =
    text.isAllDigits() && (text.length == 1 || text[0] != '0')

/**
 * Whether [value] and [literal] are equal but for letter case or trailing spaces; allocates
 * nothing.
 */
private fun equalButForCaseOrTrailingSpaces(value: String, literal: String): Boolean {
    val valueEnd = value.trimmedLength()
    val literalEnd = literal.trimmedLength()
    return valueEnd == literalEnd && value.regionMatches(0, literal, 0, valueEnd, ignoreCase = true)
}

/** The length of this string without its trailing whitespace. */
private fun String.trimmedLength(): Int {
    var end = length
    while (end > 0 && this[end - 1].isWhitespace()) end--
    return end
}

/**
 * Whether [literal] is [value] in double quotes, as a `jsonb` string is written; allocates nothing.
 */
private fun isQuotedForm(literal: String, value: String): Boolean =
    literal.length == value.length + 2 &&
        literal.first() == '"' &&
        literal.last() == '"' &&
        literal.regionMatches(1, value, 0, value.length)

/**
 * Whether [value] and [literal] are both boolean input with one meaning, as a boolean reads them.
 */
private fun sameBooleanWord(value: String, literal: String): Boolean {
    val left = postgresBooleanOrNull(value) ?: return false
    return left == postgresBooleanOrNull(literal)
}

/** Whether [value] and [literal] are one MAC address written with different separators. */
private fun sameMacAddress(value: String, literal: String): Boolean {
    val left = macDigitsOrNull(value) ?: return false
    return left == macDigitsOrNull(literal)
}

/** The hex digits of a MAC address in [text], lower case, or `null` when it is not one. */
private fun macDigitsOrNull(text: String): String? {
    // Fewer characters than a MAC address has digits: told apart without allocating
    if (text.length < MAC_HEX_DIGITS) return null
    val digits = text.trim().filterNot { it == ':' || it == '-' || it == '.' }.lowercase()
    if (digits.length != MAC_HEX_DIGITS && digits.length != MAC8_HEX_DIGITS) return null
    return digits.takeIf { candidate -> candidate.all { it in '0'..'9' || it in 'a'..'f' } }
}

/**
 * Whether [value] can only be the output of a type that compares as text: empty, or printable ASCII
 * starting with a letter or `_`, with no `:`, and neither a special input Postgres gives a meaning
 * to (`today`, `infinity`, …) nor an ISO 8601 interval (`P1D`).
 */
private fun isTextShaped(value: String): Boolean {
    if (value.isEmpty()) return true
    if (
        !(value[0].isAsciiLetter() || value[0] == '_') ||
            ':' in value ||
            value.any { it !in ' '..'~' }
    )
        return false
    val special =
        value.length <= LONGEST_SPECIAL_INPUT &&
            SPECIAL_INPUT_FIRST_LETTERS.indexOf(value[0].lowercaseChar()) >= 0 &&
            value.lowercase() in SPECIAL_INPUTS
    val interval = (value[0] == 'P' || value[0] == 'p') && ISO_INTERVAL.containsMatchIn(value)
    return !special && !interval
}

private fun String.isAllDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/**
 * Whether [text] is a UUID as Postgres accepts one: 32 hex digits, hyphens and braces aside.
 * Allocates nothing.
 */
internal fun isUuid(text: String): Boolean {
    if (text.length !in UUID_LENGTHS) return false
    var digits = 0
    for (character in text) {
        when {
            isHexDigit(character) -> digits++
            character == '-' || character == '{' || character == '}' || character.isWhitespace() ->
                Unit
            else -> return false
        }
    }
    return digits == UUID_HEX_DIGITS
}

/** Whether the UUIDs [first] and [second] ([isUuid] both) have the same digits, in any case. */
internal fun sameUuidDigits(first: String, second: String): Boolean {
    var left = 0
    var right = 0
    while (true) {
        while (left < first.length && !isHexDigit(first[left])) left++
        while (right < second.length && !isHexDigit(second[right])) right++
        if (left == first.length || right == second.length) {
            return left == first.length && right == second.length
        }
        if (first[left].lowercaseChar() != second[right].lowercaseChar()) return false
        left++
        right++
    }
}

/** The digits of [text], a UUID ([isUuid]), in lower case: one value however it is written. */
private fun uuidDigitsOf(text: String): String {
    val digits = StringBuilder(UUID_HEX_DIGITS)
    for (character in text) if (isHexDigit(character)) digits.append(character.lowercaseChar())
    return digits.toString()
}

private fun isHexDigit(character: Char): Boolean =
    character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'

/** A decimal number reduced to its value: sign, significant digits and exponent. */
private data class DecimalValue(val isNegative: Boolean, val digits: String, val exponent: Long)

/** [text] as a [DecimalValue], or `null` when it is not a plain decimal or exponent literal. */
private fun decimalOf(text: String): DecimalValue? {
    val match = DECIMAL.matchEntire(text.trim()) ?: return null
    val (sign, whole, fraction, exponentText) = match.destructured
    if (whole.isEmpty() && fraction.isEmpty()) return null
    val exponent = (exponentText.ifEmpty { "0" }.toLongOrNull() ?: return null) - fraction.length
    var digits = (whole + fraction).trimStart('0')
    if (digits.isEmpty()) return DecimalValue(isNegative = false, digits = "0", exponent = 0)
    val trailingZeros = digits.length - digits.trimEnd('0').length
    digits = digits.dropLast(trailingZeros)
    return DecimalValue(
        isNegative = sign == "-",
        digits = digits,
        exponent = exponent + trailingZeros,
    )
}

private const val NULL_LITERAL = "null"
private const val UUID_HEX_DIGITS = 32
private const val MAC_HEX_DIGITS = 12
private const val MAC8_HEX_DIGITS = 16
private const val ISO_DATE_LENGTH = 10
private const val LONGEST_BOOLEAN_WORD = 5
private const val LONGEST_SPECIAL_INPUT = 9

/**
 * The lengths a UUID can be written in: its 32 digits, with hyphens (one per four at most) and
 * braces.
 */
private val UUID_LENGTHS: IntRange = 32..48

/** The lengths a MAC address can be written in: 12 or 16 digits, with or without separators. */
private val MAC_LENGTHS: IntRange = 12..23

private val DECIMAL = Regex("""([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?""")
private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/** The start of an ISO 8601 interval, as an `iso_8601` interval style prints one. */
private val ISO_INTERVAL = Regex("""^P[0-9T]""")

private val BOOLEAN_WORDS: Map<String, Boolean> =
    mapOf(
        "true" to true,
        "yes" to true,
        "on" to true,
        "1" to true,
        "false" to false,
        "no" to false,
        "off" to false,
        "0" to false,
    )

/** The first letters of [BOOLEAN_WORDS]. */
private const val BOOLEAN_FIRST_LETTERS = "tfyno01"

/** The first letters of [SPECIAL_INPUTS]. */
private const val SPECIAL_INPUT_FIRST_LETTERS = "inetya"

/** Words Postgres reads as a value of a date, time or number type rather than as text. */
private val SPECIAL_INPUTS: Set<String> =
    setOf(
        "infinity",
        "inf",
        "nan",
        "epoch",
        "now",
        "today",
        "tomorrow",
        "yesterday",
        "allballs",
    )
