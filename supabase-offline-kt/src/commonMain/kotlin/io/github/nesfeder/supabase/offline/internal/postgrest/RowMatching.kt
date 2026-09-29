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
    UNKNOWN,
    ;

    /** Both this and [other] must match: SQL's `AND`. */
    infix fun and(other: RowMatch): RowMatch = when {
        this == EXCLUDED || other == EXCLUDED -> EXCLUDED
        this == UNKNOWN || other == UNKNOWN -> UNKNOWN
        else -> MATCHES
    }

    /** Either this or [other] must match: SQL's `OR`. */
    infix fun or(other: RowMatch): RowMatch = when {
        this == MATCHES || other == MATCHES -> MATCHES
        this == UNKNOWN || other == UNKNOWN -> UNKNOWN
        else -> EXCLUDED
    }
}

/** The operators [rowMatch] evaluates; every other one is [RowMatch.UNKNOWN]. */
internal val EVALUATED_OPERATORS: Set<FilterOperator> = setOf(FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.IN, FilterOperator.IS)

/** [filter] against [row]: its conditions combined by its mode. */
internal fun rowMatch(row: JsonObject, filter: FilterDescriptor): RowMatch {
    val matches = filter.conditions.map { condition -> rowMatch(row, condition) }
    return when (filter.mode) {
        FilterDescriptor.Mode.AND -> matches.fold(RowMatch.MATCHES) { combined, match -> combined and match }
        FilterDescriptor.Mode.OR -> matches.fold(RowMatch.EXCLUDED) { combined, match -> combined or match }
    }
}

/**
 * [condition] against [row], as Postgres would evaluate it: `eq`, `neq`, `in` and `is`, with null
 * semantics. Anything else is [RowMatch.UNKNOWN]: another operator, a column the row does not carry
 * (a server default, a projection, a JSON path or a cast), an array or object value, and a literal
 * that some column type could read as equal to a different-looking value.
 */
internal fun rowMatch(row: JsonObject, condition: FilterDescriptor.Condition): RowMatch {
    val operator = condition.operator
    if (operator == null || operator !in EVALUATED_OPERATORS || !isPlainColumn(condition.column)) return RowMatch.UNKNOWN
    val value = row[condition.column] ?: return RowMatch.UNKNOWN
    return when (operator) {
        FilterOperator.IS -> isMatch(value, condition.value)
        FilterOperator.EQ -> equalityOf(value, condition.value)
        FilterOperator.NEQ -> when (value) {
            JsonNull -> RowMatch.EXCLUDED
            else -> equalityOf(value, condition.value).negated()
        }
        FilterOperator.IN -> membershipOf(value, condition.value)
        else -> RowMatch.UNKNOWN
    }
}

/** A column named directly: not a referenced table's (`a.b`), a JSON path (`a->b`) or a cast (`a::t`). */
internal fun isPlainColumn(column: String): Boolean =
    column.isNotEmpty() && '.' !in column && "->" !in column && "::" !in column && '(' !in column

/** `eq`: a null compares unknown, which excludes. */
private fun equalityOf(value: JsonElement, literal: String): RowMatch = when {
    value == JsonNull -> RowMatch.EXCLUDED
    value !is JsonPrimitive -> RowMatch.UNKNOWN
    value.isString -> textEquality(value.content, literal)
    value.booleanOrNull != null -> booleanEquality(value.content.toBoolean(), literal)
    else -> numberEquality(value.content, literal)
}

private fun RowMatch.negated(): RowMatch = when (this) {
    RowMatch.MATCHES -> RowMatch.EXCLUDED
    RowMatch.EXCLUDED -> RowMatch.MATCHES
    RowMatch.UNKNOWN -> RowMatch.UNKNOWN
}

/**
 * `in`: a member matches; a null column, or no member when none is unknown, excludes. The list is
 * read by the parser replay uses, so the overlay and the replayed request see the same members.
 */
private fun membershipOf(value: JsonElement, literal: String): RowMatch {
    if (value == JsonNull) return RowMatch.EXCLUDED
    val members = FilterEngine.inListMembersOf(literal) ?: return RowMatch.UNKNOWN
    return members.fold(RowMatch.EXCLUDED) { combined, member ->
        // An unquoted null may be SQL's NULL or the text "null"; only the column's value can say
        val match = if (member.equals(NULL_LITERAL, ignoreCase = true)) nullMemberMatch(value) else equalityOf(value, member)
        combined or match
    }
}

/** A `null` member of an `in` list against a non-null [value]: never a match for a non-text value. */
private fun nullMemberMatch(value: JsonElement): RowMatch =
    if (value is JsonPrimitive && value.isString && value.content.equals(NULL_LITERAL, ignoreCase = true)) RowMatch.UNKNOWN else RowMatch.EXCLUDED

/** `is`: `null`, `not_null`, `true`, `false` and `unknown`; any other value is not evaluated. */
private fun isMatch(value: JsonElement, literal: String): RowMatch {
    val isNull = value == JsonNull
    val boolean = (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    return when (literal.lowercase()) {
        NULL_LITERAL -> if (isNull) RowMatch.MATCHES else RowMatch.EXCLUDED
        "not_null" -> if (isNull) RowMatch.EXCLUDED else RowMatch.MATCHES
        "true", "false" -> when {
            isNull -> RowMatch.EXCLUDED
            boolean == null -> RowMatch.UNKNOWN
            else -> if (boolean == literal.equals("true", ignoreCase = true)) RowMatch.MATCHES else RowMatch.EXCLUDED
        }
        "unknown" -> when {
            isNull -> RowMatch.MATCHES
            boolean == null -> RowMatch.UNKNOWN
            else -> RowMatch.EXCLUDED
        }
        else -> RowMatch.UNKNOWN
    }
}

/** A boolean column against [literal], read the way Postgres reads a boolean literal. */
private fun booleanEquality(value: Boolean, literal: String): RowMatch {
    val parsed = postgresBooleanOrNull(literal) ?: return RowMatch.UNKNOWN
    return if (parsed == value) RowMatch.MATCHES else RowMatch.EXCLUDED
}

/** Postgres's boolean input: `t`/`true`/`yes`/`on`/`1` and their opposites, or an unambiguous prefix. */
private fun postgresBooleanOrNull(literal: String): Boolean? {
    val word = literal.trim().lowercase()
    if (word.isEmpty()) return null
    val readings = BOOLEAN_WORDS.filterKeys { candidate -> candidate.startsWith(word) }.values.toSet()
    return readings.singleOrNull()
}

/** A number column against [literal], compared by value: `5.0` equals `5`, `1e2` equals `100`. */
private fun numberEquality(value: String, literal: String): RowMatch {
    val left = decimalOf(value) ?: return RowMatch.UNKNOWN
    val right = decimalOf(literal) ?: return RowMatch.UNKNOWN
    return if (left == right) RowMatch.MATCHES else RowMatch.EXCLUDED
}

/**
 * A text value against [literal]. Equal strings match, and ones equal but for letter case or trailing
 * spaces are unknown (a case-insensitive or padded type). Otherwise the row value, which is its
 * column type's output, decides: text-shaped output compares as text, so a different ASCII literal
 * excludes; two different UUIDs, digit strings or ISO dates exclude; anything else is unknown.
 */
private fun textEquality(value: String, literal: String): RowMatch = when {
    value == literal -> RowMatch.MATCHES
    value.trimEnd().equals(literal.trimEnd(), ignoreCase = true) -> RowMatch.UNKNOWN
    isUuid(value) && isUuid(literal) -> if (uuidDigits(value) == uuidDigits(literal)) RowMatch.UNKNOWN else RowMatch.EXCLUDED
    isTextShaped(value) && literal.all { it in ' '..'~' } -> RowMatch.EXCLUDED
    value.isAllDigits() && literal.isAllDigits() -> RowMatch.EXCLUDED
    ISO_DATE.matches(value) && ISO_DATE.matches(literal) -> RowMatch.EXCLUDED
    else -> RowMatch.UNKNOWN
}

/**
 * Whether [value] can only be the output of a type that compares as text: empty, or printable ASCII
 * starting with a letter or `_`, with no `:`, and neither a special input Postgres gives a meaning
 * to (`today`, `infinity`, …) nor an ISO 8601 interval (`P1D`).
 */
private fun isTextShaped(value: String): Boolean {
    if (value.isEmpty()) return true
    if (!(value[0].isAsciiLetter() || value[0] == '_') || ':' in value || value.any { it !in ' '..'~' }) return false
    return value.lowercase() !in SPECIAL_INPUTS && !ISO_INTERVAL.containsMatchIn(value)
}

private fun String.isAllDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/** Whether [text] is a UUID as Postgres accepts one: 32 hex digits, hyphens and braces aside. */
private fun isUuid(text: String): Boolean {
    val digits = uuidDigits(text)
    return digits.length == UUID_HEX_DIGITS && digits.all { it in '0'..'9' || it in 'a'..'f' }
}

private fun uuidDigits(text: String): String = text.trim().removePrefix("{").removeSuffix("}").replace("-", "").lowercase()

/** A decimal number reduced to its value: sign, significant digits and exponent. */
private data class DecimalValue(val negative: Boolean, val digits: String, val exponent: Long)

/** [text] as a [DecimalValue], or `null` when it is not a plain decimal or exponent literal. */
private fun decimalOf(text: String): DecimalValue? {
    val match = DECIMAL.matchEntire(text.trim()) ?: return null
    val (sign, whole, fraction, exponentText) = match.destructured
    if (whole.isEmpty() && fraction.isEmpty()) return null
    val exponent = (exponentText.ifEmpty { "0" }.toLongOrNull() ?: return null) - fraction.length
    var digits = (whole + fraction).trimStart('0')
    if (digits.isEmpty()) return DecimalValue(negative = false, digits = "0", exponent = 0)
    val trailingZeros = digits.length - digits.trimEnd('0').length
    digits = digits.dropLast(trailingZeros)
    return DecimalValue(negative = sign == "-", digits = digits, exponent = exponent + trailingZeros)
}

private const val NULL_LITERAL = "null"
private const val UUID_HEX_DIGITS = 32

private val DECIMAL = Regex("""([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?""")
private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/** The start of an ISO 8601 interval, as an `iso_8601` interval style prints one. */
private val ISO_INTERVAL = Regex("""^P[0-9T]""")

private val BOOLEAN_WORDS: Map<String, Boolean> = mapOf(
    "true" to true, "yes" to true, "on" to true, "1" to true,
    "false" to false, "no" to false, "off" to false, "0" to false,
)

/** Words Postgres reads as a value of a date, time or number type rather than as text. */
private val SPECIAL_INPUTS: Set<String> = setOf("infinity", "nan", "epoch", "now", "today", "tomorrow", "yesterday", "allballs")
