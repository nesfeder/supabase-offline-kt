@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine.applyTo
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine.decodeOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine.encode
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import io.github.jan.supabase.postgrest.query.filter.TextSearchType
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Serializable description of a PostgREST filter expression.
 *
 * This is the compact form we store alongside offline mutations so we can:
 * - apply them to local JSON rows (offline overlay)
 * - replay them into a real PostgrestRequestBuilder when syncing UPDATE/DELETE
 */
@Serializable
internal data class FilterDescriptor(
    /** How conditions are combined. AND is the common case; OR uses PostgREST logical expressions. */
    val mode: Mode = Mode.AND,
    val conditions: List<Condition>
) {
    @Serializable
    enum class Mode { AND, OR }

    /**
     * A single condition like: `eq("id", "123")` or `ilike("name", "%foo%")`.
     */
    @Serializable
    data class Condition(
        val column: String,
        /** FilterOperator.identifier, e.g. "eq", "lt", "ilike" */
        val operatorId: String,
        /** Raw value as string (we’ll coerce heuristically when comparing / replaying) */
        val value: String
    ) {
        val operator: FilterOperator?
            get() = offlineFilterOperatorOrNull(operatorId)
    }
}

/**
 * Resolves a PostgREST operator [identifier] to the [FilterOperator] it names, or `null` when it
 * names none.
 *
 * Every capture path and the stored-condition lookup resolve through here. Resolving an operator
 * does not mean the engine can evaluate it locally: [rowMatch] evaluates only a few, while
 * [FilterEngine.applyTo] replays every one of them faithfully.
 *
 * @param identifier The operator prefix of a PostgREST filter value, e.g. `"eq"`.
 * @return The operator, or `null` when the identifier is unknown (including a `not.` negation).
 */
internal fun offlineFilterOperatorOrNull(identifier: String): FilterOperator? =
    FilterOperator.entries.firstOrNull { operator -> operator.identifier == identifier }

/**
 * Extract a best-effort [FilterDescriptor] from a [PostgrestRequestBuilder].
 *
 * We only handle the simple, common case:
 *
 *   column -> ["eq.value"]
 *
 * via PostgrestFilterBuilder’s `eq("column", value)` etc.  Complex DSL usage
 * (`and(...)`, `or(...)`, foreign-table filters) is intentionally ignored so we
 * don’t risk wrong offline behaviour.
 */
internal fun buildDescriptorFromRequest(
    defaultSchema: String,
    conversionMethod: PropertyConversionMethod,
    request: PostgrestRequestBuilder.() -> Unit
): FilterDescriptor? {
    // PostgrestRequestBuilder is abstract since supabase-kt 3.6.0; SelectRequestBuilder is the
    // concrete variant whose construction only populates `params`, which is all the descriptor
    // extraction reads.
    val builder = SelectRequestBuilder(defaultSchema, conversionMethod).apply(request)
    return buildDescriptorFromBuilder(builder)
}

internal fun buildDescriptorFromBuilder(
    builder: PostgrestRequestBuilder
): FilterDescriptor? {
    val conditions = mutableListOf<FilterDescriptor.Condition>()

    builder.params.forEach { (key, values) ->
        // Skip special / nested keys (e.g. "and(...)", "or(...)", "table.column").
        if (key.contains("(") || key.contains(")") || key.contains(".")) return@forEach

        val first = values.firstOrNull() ?: return@forEach
        // Expect something like "eq.value" or "eq.123"
        val parts = first.split('.', limit = 2)
        if (parts.size < 2) return@forEach

        val opIdentifier = parts[0]
        val rawValue = parts[1]

        val operator = offlineFilterOperatorOrNull(opIdentifier)
            ?: return@forEach

        // Stored exactly as encoded: trimming whitespace or quotes would replay (and overlay) a
        // different value than the caller wrote.
        conditions += FilterDescriptor.Condition(
            column = key,
            operatorId = operator.identifier,
            value = rawValue
        )
    }

    if (conditions.isEmpty()) return null
    return FilterDescriptor(
        mode = FilterDescriptor.Mode.AND,
        conditions = conditions
    )
}

/**
 * Thrown by [buildDescriptorStrict] when a queued UPDATE/DELETE carries a filter expression the
 * offline descriptor format cannot represent faithfully — a logical group (`or(...)` / `and(...)`),
 * a foreign-table dotted key, or a negated/unrecognized operator prefix such as `not.`.
 *
 * The offline-queue branches of [io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilder] catch this and
 * fail the caller's original write instead of enqueuing a mutation that would later replay against a
 * broader row set than the caller targeted.
 */
internal class UnsupportedOfflineFilterException(message: String) : IllegalArgumentException(message)

/**
 * PostgREST query parameters that [buildDescriptorStrict] must not read as filter conditions.
 *
 * Exactly one qualifies. `select` shapes the *representation a mutation returns* and nothing else:
 * on a PATCH or DELETE it neither chooses rows nor changes what is written, so a descriptor that
 * omits it replays the identical mutation.
 *
 * Everything else stays fail-closed, and for two distinct reasons — neither of which is "it is not
 * a filter":
 *  - `limit` / `offset` / `order` narrow which **rows** the mutation reaches. Dropping one replays
 *    against more rows than the caller wrote.
 *  - `columns` narrows which **columns** of the body the server applies. The descriptor cannot
 *    carry it, so dropping it would replay a wider write than the caller made — the same "wrote
 *    more than was asked for" failure, one axis over. (Today it is unreachable here: supabase-kt's
 *    `PostgrestRequestBuilder` only exposes it on inserts, which never take this path. Listing it
 *    as a skip would be a fail-open exemption waiting for that to change.)
 */
private val NON_FILTER_PARAMS: Set<String> = setOf("select")

/**
 * Strict counterpart to [buildDescriptorFromBuilder], used on the **replay** path (queued
 * UPDATE / DELETE) where silently dropping an un-capturable condition would replay the mutation
 * against more rows than the caller intended.
 *
 * Captures the simple `column -> "op.value"` shape exactly like [buildDescriptorFromBuilder], but
 * instead of skipping anything it cannot represent it throws [UnsupportedOfflineFilterException]
 * when [PostgrestRequestBuilder.params] contains:
 *  - a key with `(`, `)` or `.` — a logical group (`or(...)` / `and(...)`) or a foreign-table
 *    dotted key; neither is representable in the flat offline descriptor
 *  - a first value whose operator prefix is not a known [FilterOperator] identifier — this covers
 *    `not.`-negated operators and any group expression encoded into the value
 *
 * Returns `null` only for a genuinely filter-free request (no capturable params), mirroring
 * [buildDescriptorFromBuilder]'s empty-conditions contract.
 *
 * Only the **first** value per column is inspected, mirroring upstream supabase-kt 3.6.0, which
 * transmits only the first value per query key (`params.mapToFirstValue()`). Two filters on the
 * same column added via separate calls therefore capture the first value only — this matches live
 * request semantics and is intentional, not a lossy case.
 *
 * @param builder request builder whose [PostgrestRequestBuilder.params] describe the target rows.
 * @return a faithful [FilterDescriptor], or `null` when no filter conditions are present.
 * @throws UnsupportedOfflineFilterException when a condition cannot be captured without broadening.
 */
internal fun buildDescriptorStrict(
    builder: PostgrestRequestBuilder
): FilterDescriptor? {
    val conditions = mutableListOf<FilterDescriptor.Condition>()

    builder.params.forEach { (key, values) ->
        // `select` only shapes the representation the mutation returns, so a descriptor that omits
        // it replays the identical write. It has to be recognised explicitly because its value is
        // not `op.value` shaped (it carries "*"), and the operator check below would otherwise
        // reject an ordinary `update { filter { … }; select() }` — refusing to queue it, which
        // fails the user's offline write outright. Every other parameter keeps falling through to
        // the throw; see [NON_FILTER_PARAMS] for why that list has exactly one member.
        if (key in NON_FILTER_PARAMS) return@forEach

        if (key.contains('(') || key.contains(')') || key.contains('.')) {
            throw UnsupportedOfflineFilterException(
                "cannot faithfully capture offline filter for key '$key': logical groups and " +
                    "foreign-table keys are not representable in the offline descriptor"
            )
        }

        val first = values.firstOrNull() ?: return@forEach
        val parts = first.split('.', limit = 2)
        val operatorId = parts.firstOrNull()
        val operator = operatorId?.let { candidate ->
            offlineFilterOperatorOrNull(candidate)
        }
        if (operator == null || parts.size < 2) {
            throw UnsupportedOfflineFilterException(
                "cannot faithfully capture offline filter for key '$key': unrecognized operator " +
                    "in '$first' (negated 'not.' operators and group expressions are not supported)"
            )
        }

        // Stored exactly as encoded, so replay sends the very value the caller's request did.
        conditions += FilterDescriptor.Condition(
            column = key,
            operatorId = operator.identifier,
            value = parts[1]
        )
    }

    if (conditions.isEmpty()) return null
    return FilterDescriptor(
        mode = FilterDescriptor.Mode.AND,
        conditions = conditions
    )
}

/**
 * Convenience wrapper mirroring [buildDescriptorFromRequest] but delegating to
 * [buildDescriptorStrict], so offline-queue callers can build the request the same way they do
 * online and receive strict (throwing) capture semantics.
 *
 * @param defaultSchema schema used to construct the concrete request builder.
 * @param conversionMethod property-name conversion mirrored from the live PostgREST config.
 * @param request the caller's request-configuration block (typically a `filter { … }`).
 * @return a faithful [FilterDescriptor], or `null` when no filter conditions are present.
 * @throws UnsupportedOfflineFilterException when a condition cannot be captured without broadening.
 */
internal fun buildDescriptorStrictFromRequest(
    defaultSchema: String,
    conversionMethod: PropertyConversionMethod,
    request: PostgrestRequestBuilder.() -> Unit
): FilterDescriptor? {
    val builder = SelectRequestBuilder(defaultSchema, conversionMethod).apply(request)
    return buildDescriptorStrict(builder)
}

/**
 * Encodes / decodes filter descriptors and replays them on demand.
 *
 * - [encode] / [decodeOrNull] : store & restore conditions for UPDATE/DELETE mutations
 * - [applyTo]           : replay a FilterDescriptor onto PostgrestRequestBuilder
 * - [inListMembersOf]   : read an `in` list the way replay does, for [rowMatch]
 */
internal object FilterEngine {

    private val json: Json = OfflineJson

    /**
     * Schema for the throwaway builder [replaysFully] replays onto. Never sent anywhere — the
     * builder exists only so the real [applyCondition] branches can report what they applied.
     */
    private const val SCRATCH_SCHEMA = "public"

    /**
     * Property-conversion method for that same throwaway builder. `CAMEL_CASE_TO_SNAKE_CASE`
     * rather than `SERIAL_NAME`, which is JVM/Android-only and would not link on the iOS target.
     * The value is inert either way: replay addresses columns by their captured `String` names,
     * which no conversion method touches.
     */
    private val SCRATCH_CONVERSION = PropertyConversionMethod.CAMEL_CASE_TO_SNAKE_CASE

    /* ─────────────────────── Encoding / Decoding ─────────────────────── */

    fun encode(descriptor: FilterDescriptor, json: Json): String =
        json.encodeToString(FilterDescriptor.serializer(), descriptor)

    fun decodeOrNull(filterJson: String?, json: Json): FilterDescriptor? {
        if (filterJson.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(FilterDescriptor.serializer(), filterJson)
        }.getOrNull()
    }

    /* ───────────────────── Replay onto PostgREST ───────────────────── */

    /**
     * Replays filters onto a live [PostgrestRequestBuilder].
     *
     * Used by MutationQueueReplayer when syncing UPDATE / DELETE operations.
     *
     * - For [FilterDescriptor.Mode.AND], we just call each condition on the filter DSL:
     *   `filter { eq(...); lt(...); ilike(...) }`
     *
     * - For [FilterDescriptor.Mode.OR], we wrap the conditions in a logical OR block:
     *   `filter { or { eq(...); lt(...); } }`
     *
     * The underlying `PostgrestFilterBuilder` takes care of building the correct
     * `or=(...)` / `and=(...)` query params.
     *
     * @return how many of [FilterDescriptor.conditions] actually landed on [builder]. A count
     *   below `descriptor.conditions.size` means the replayed request targets a broader row set
     *   than the caller captured — see [replaysFully], which is how callers check that.
     */
    fun applyTo(
        builder: PostgrestRequestBuilder,
        descriptor: FilterDescriptor
    ): Int {
        var applied = 0
        builder.filter {
            when (descriptor.mode) {
                FilterDescriptor.Mode.AND -> {
                    descriptor.conditions.forEach { cond ->
                        if (applyCondition(cond)) applied++
                    }
                }

                FilterDescriptor.Mode.OR -> {
                    or {
                        descriptor.conditions.forEach { cond ->
                            if (applyCondition(cond)) applied++
                        }
                    }
                }
            }
        }
        return applied
    }

    /**
     * Reports whether every condition in [descriptor] survives a replay.
     *
     * A condition whose stored value cannot be parsed back into the shape its operator needs is
     * skipped by [applyCondition]. On the queued UPDATE / DELETE path that silence is dangerous:
     * the mutation still executes, now scoped only by whatever conditions did land (and, in the
     * worst case, by RLS alone). The syncer calls this **before** issuing the request so a short
     * replay can be dropped instead of broadened.
     *
     * The check replays onto a throwaway builder rather than re-deriving the parse rules, so it
     * cannot drift from [applyTo] when a new operator branch is added. The scratch builder's
     * schema and property-conversion method are arbitrary: replay addresses columns by the
     * `String` names captured in the descriptor, so neither value can change whether a condition
     * lands.
     *
     * A descriptor carrying **no** conditions is reported as not replayable, even though it
     * vacuously loses nothing. [buildDescriptorStrict] returns `null` rather than an empty
     * descriptor, so an empty one can only come from a corrupted or hand-written stored row —
     * and replaying it produces exactly the unfiltered request the null-descriptor guard already
     * refuses. Treating it as replayable would let it through the one gate meant to catch it.
     *
     * @param descriptor the stored filter about to be replayed.
     * @return true when the descriptor has at least one condition and every one of them applied;
     *   false when it is empty or at least one condition was dropped.
     */
    fun replaysFully(descriptor: FilterDescriptor): Boolean {
        if (descriptor.conditions.isEmpty()) return false
        val scratch = SelectRequestBuilder(SCRATCH_SCHEMA, SCRATCH_CONVERSION)
        return applyTo(scratch, descriptor) == descriptor.conditions.size
    }

    /**
     * Apply a single condition to the [PostgrestFilterBuilder] using the same
     * DSL that callers would use online.
     *
     * @return true when the condition landed on the builder; false when its stored value could
     *   not be parsed back into the shape the operator needs and the condition was skipped.
     */
    private fun PostgrestFilterBuilder.applyCondition(cond: FilterDescriptor.Condition): Boolean {
        val operator = cond.operator ?: return false
        val rawValue = cond.value

        when (operator) {
            // Basic comparison
            FilterOperator.EQ -> eq(cond.column, rawValue)
            FilterOperator.NEQ -> neq(cond.column, rawValue)
            FilterOperator.GT -> gt(cond.column, rawValue)
            FilterOperator.GTE -> gte(cond.column, rawValue)
            FilterOperator.LT -> lt(cond.column, rawValue)
            FilterOperator.LTE -> lte(cond.column, rawValue)

            // Patterns
            FilterOperator.LIKE -> like(cond.column, rawValue)
            FilterOperator.ILIKE -> ilike(cond.column, rawValue)
            FilterOperator.MATCH -> regexMatch(cond.column, rawValue)
            FilterOperator.IMATCH -> regexIMatch(cond.column, rawValue)

            // As captured, so `not_null` and `unknown` reach the same rows as the caller's filter
            FilterOperator.IS -> filter(cond.column, FilterOperator.IS, rawValue)

            // IN
            FilterOperator.IN -> {
                // A parsed-but-empty list still applies: it re-encodes as the caller's original
                // match-nothing filter (`in.()`). Dropping the condition instead would replay the
                // mutation against every row.
                val list = parseArray(rawValue) ?: return false
                isIn(cond.column, list)
            }

            // Array / JSON operators. Each accepts two encodings — a `{a,b}` element list and an
            // `[a,b]` range — so both must be recognised; see [applyArrayOrRangeCondition].
            FilterOperator.CS -> return applyArrayOrRangeCondition(cond, FilterOperator.CS) { list ->
                cs(cond.column, list)
            }

            FilterOperator.CD -> return applyArrayOrRangeCondition(cond, FilterOperator.CD) { list ->
                cd(cond.column, list)
            }

            // Ranges
            FilterOperator.SL -> {
                val range = parseRange(rawValue) ?: return false
                sl(cond.column, range)
            }

            FilterOperator.SR -> {
                val range = parseRange(rawValue) ?: return false
                sr(cond.column, range)
            }

            FilterOperator.NXL -> {
                val range = parseRange(rawValue) ?: return false
                nxl(cond.column, range)
            }

            FilterOperator.NXR -> {
                val range = parseRange(rawValue) ?: return false
                nxr(cond.column, range)
            }

            FilterOperator.ADJ -> {
                val range = parseRange(rawValue) ?: return false
                adjacent(cond.column, range)
            }

            FilterOperator.OV -> return applyArrayOrRangeCondition(cond, FilterOperator.OV) { list ->
                ov(cond.column, list)
            }

            // Full text search: map onto textSearch + TextSearchType
            FilterOperator.FTS -> textSearch(cond.column, rawValue, TextSearchType.NONE)
            FilterOperator.PLFTS -> textSearch(cond.column, rawValue, TextSearchType.PLAINTO)
            FilterOperator.PHFTS -> textSearch(cond.column, rawValue, TextSearchType.PHRASETO)
            FilterOperator.WFTS -> textSearch(cond.column, rawValue, TextSearchType.WEBSEARCH)

            // No typed DSL entry point; the generic call encodes it exactly as the caller's
            // `filter(column, ISDISTINCT, value)` did.
            FilterOperator.ISDISTINCT -> filter(cond.column, FilterOperator.ISDISTINCT, rawValue)
        }
        return true
    }

    /**
     * Applies one of the array operators (`cs` / `cd` / `ov`), which supabase-kt encodes in two
     * shapes depending on what the caller passed:
     *
     * - a `List` becomes an element list — `cs.{a,b}` (`encodeOverlapAsArray`)
     * - a `Pair` becomes a **range** — `cs.[a,b]` (`encodeOverlapAsRange`)
     *
     * The two are different filters, so the range form must be replayed through the generic
     * `filter(column, operator, Pair)` entry point rather than re-encoded as a list. Before this
     * split the range form fell into [parseArray]'s JSON branch, failed to decode (its tokens are
     * unquoted, so it is not a valid JSON string array), yielded `null`, and the condition was
     * silently dropped — broadening the replayed mutation's target set.
     *
     * @param cond The captured condition.
     * @param operator The operator to re-encode a range form under.
     * @param applyList Applies the element-list form via the operator's typed DSL entry point.
     * @return true when either encoding was recognised and applied; false when the stored value
     *   matched neither, leaving the condition off the request.
     */
    private fun PostgrestFilterBuilder.applyArrayOrRangeCondition(
        cond: FilterDescriptor.Condition,
        operator: FilterOperator,
        applyList: (List<Any>) -> Unit,
    ): Boolean {
        val range = parsePostgrestRangeLiteral(cond.value)
        if (range != null) {
            filter(cond.column, operator, range)
            return true
        }
        val list = parseArray(cond.value) ?: return false
        applyList(list)
        return true
    }

    /**
     * Recognises supabase-kt's `[min,max]` range encoding for the array operators.
     *
     * Returns `null` for anything else — including a genuine JSON string array (`["a","b"]`),
     * which stays a list so the older capture form keeps decoding as it always has. That check
     * is what makes the two bracket forms separable: only the JSON one decodes.
     */
    private fun parsePostgrestRangeLiteral(raw: String?): Pair<Any, Any>? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.length < 2 || !trimmed.startsWith('[') || !trimmed.endsWith(']')) return null
        if (decodeJsonStringList(trimmed) != null) return null

        // `encodeOverlapAsRange` interpolates both bounds verbatim, so tokenize them verbatim.
        val parts = splitVerbatimElements(trimmed.substring(1, trimmed.length - 1))
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    /**
     * The members of an `in` value — `(a,"b,c")`, or a JSON array of strings — read exactly as replay
     * reads them, or `null` when it is neither.
     */
    fun inListMembersOf(value: String): List<String>? = parseArray(value)?.map { member -> member.toString() }

    /**
     * Parse Condition.value into a list of strings for array-like operators:
     *
     * - If the string looks like JSON array (`[ "a", "b" ]`), we parse via kotlinx.serialization.
     * - Otherwise, we treat it as a comma-separated list, stripping the PostgREST wrapper the
     *   strict capture preserves from supabase-kt's param encoding: `(a,b,c)` for `in` and
     *   `{a,b,c}` for `cs` / `cd` / `ov`. The two wrappers are tokenized differently because
     *   supabase-kt encodes them differently — see [splitPostgrestListValue].
     *
     * A wrapped-but-empty value (`()` / `{}`) returns an empty list — distinct from `null` — so
     * replay can reproduce the caller's original empty filter instead of dropping the condition
     * and broadening the mutation's target set. `null` means "unparseable"; callers skip the
     * condition only in that case.
     *
     * [rowMatch]'s `in` evaluation parses through here too, so the overlay and the replayed
     * request cannot end up with different element lists for the same stored value.
     */
    private fun parseArray(raw: String?): List<Any>? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // JSON array form
        if (trimmed.startsWith("[")) {
            return decodeJsonStringList(trimmed)
        }

        // Comma-separated form, with or without the PostgREST list wrapper
        val unwrapped = trimmed.stripPostgrestListWrapper()
        val elements = splitPostgrestListValue(trimmed).filter { it.isNotEmpty() }
        return when {
            elements.isNotEmpty() -> elements
            unwrapped != trimmed -> emptyList()
            else -> null
        }
    }

    /** Decodes a JSON array of strings, or `null` when [raw] is not one. */
    private fun decodeJsonStringList(raw: String): List<String>? = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), raw)
    }.getOrNull()

    /**
     * Tokenizes a captured list value, choosing the tokenizer from **how supabase-kt encoded the
     * body** rather than from the comma alone.
     *
     * supabase-kt has exactly one list encoder that escapes its elements — `encodeAsList`, which
     * emits the `in` operator's `(a,b)` and runs every element through `escapeValue`. Every other
     * list or range body is interpolated verbatim: `encodeOverlapAsArray` (`cs`/`cd`/`ov`'s
     * `{a,b}`) joins plain `toString()` output, and `encodeAsRange` / `encodeOverlapAsRange`
     * build `"(${first},${second})"` / `"[${first},${second}]"` by raw string interpolation.
     * Applying the escaped tokenizer to one of those verbatim bodies deletes the element's real
     * `"` and `\` characters and replays a filter that targets different rows — the exact failure
     * class this parser exists to prevent.
     *
     * @param value The captured value, wrapper included.
     * @return the value's elements, wrapper stripped.
     */
    private fun splitPostgrestListValue(value: String): List<String> {
        val body = value.stripPostgrestListWrapper()
        return if (value.hasEscapedListWrapper()) {
            splitEscapedElements(body)
        } else {
            splitVerbatimElements(body)
        }
    }

    /**
     * True when [this] carries the `(…)` wrapper `encodeAsList` emits for `in` values — the one
     * list encoding whose elements went through `escapeValue`.
     *
     * The range operators (`sl` / `sr` / `nxl` / `nxr` / `adj`) also emit `(…)`, but via
     * `encodeAsRange`'s raw interpolation, so they are **not** escaped and must not consult this;
     * [parseRange] tokenizes verbatim unconditionally.
     */
    private fun String.hasEscapedListWrapper(): Boolean =
        length >= 2 && startsWith('(') && endsWith(')')

    /**
     * Splits a body supabase-kt interpolated verbatim — `{a,b}` (`encodeOverlapAsArray`),
     * `[a,b]` (`encodeOverlapAsRange`) and the range operators' `(a,b)` (`encodeAsRange`).
     *
     * None of those encoders calls `escapeValue`, so every byte between the commas is literal
     * element content: unescaping a `\` or stripping a `"` here would replay a *different* value.
     * The flip side is that an element containing a `,` is unrecoverable — the encoder emitted no
     * marker separating it from a real delimiter, so the loss happens before capture and the
     * parser cannot undo it.
     */
    private fun splitVerbatimElements(body: String): List<String> = body.split(',')

    /**
     * Splits an unwrapped `in` list body on its element separators, honouring the quoting
     * `escapeValue` applies. This is the **only** body that gets escape handling; see
     * [splitPostgrestListValue] for why.
     *
     * `escapeValue` backslash-escapes `\` and `"`, then wraps the element in double quotes when
     * it contains any of `,` `.` `:` `(` `)`. A naive `split(',')` therefore tears
     * `isIn(col, listOf("a,b"))` — encoded `in.("a,b")` — into `a` and `b` and replays
     * `in.(a,b)`, which matches a different set of rows than the caller asked for. (Before the
     * wrapper strip landed the same value produced inert garbage; correcting the wrapper is what
     * made this leg reachable.)
     *
     * A quoted element is taken verbatim; an unquoted one is trimmed, preserving the previous
     * behaviour for the ordinary case. An unterminated quote — which `escapeValue` cannot emit —
     * is closed implicitly at the end of the input rather than rejected, since returning "no
     * elements" would drop the condition and broaden the mutation.
     */
    private fun splitEscapedElements(body: String): List<String> {
        val elements = mutableListOf<String>()
        val current = StringBuilder()
        var sawQuotes = false
        var inQuotes = false
        var index = 0

        fun flush() {
            elements += if (sawQuotes) current.toString() else current.toString().trim()
            current.clear()
            sawQuotes = false
        }

        while (index < body.length) {
            val char = body[index]
            when {
                char == '\\' && index + 1 < body.length -> {
                    current.append(body[index + 1])
                    index += 2
                }

                char == '"' -> {
                    inQuotes = !inQuotes
                    sawQuotes = true
                    index++
                }

                char == ',' && !inQuotes -> {
                    flush()
                    index++
                }

                else -> {
                    current.append(char)
                    index++
                }
            }
        }
        flush()
        return elements
    }

    /**
     * Parse Condition.value into a range represented as Pair<Any, Any>.
     *
     * Supported string forms:
     * - JSON array: `["min","max"]`
     * - Comma-separated, with or without the `(min,max)` wrapper supabase-kt encodes for the
     *   range operators (`sl` / `sr` / `nxl` / `nxr` / `adj`) — the strict capture preserves it.
     *
     * Bounds are tokenized verbatim ([splitVerbatimElements]). `encodeAsRange` builds the value as
     * `"(${range.first},${range.second})"` — raw interpolation, never `escapeValue` — so a bound's
     * `"` and `\` are literal content and have to survive untouched.
     *
     * The unavoidable consequence is that a bound containing a comma is **lossy, and this parser
     * cannot recover it**: `sl("d", "a,b" to "c")` encodes `sl.(a,b,c)`, which is byte-identical to
     * a three-token body, so replay takes the first two tokens and targets the different range
     * `sl.(a,b)`. That loss happens in the encoder, before anything is captured.
     */
    private fun parseRange(raw: String?): Pair<Any, Any>? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // JSON array form
        if (trimmed.startsWith("[")) {
            val list = decodeJsonStringList(trimmed)
            if (list != null && list.size >= 2) {
                return list[0] to list[1]
            }
            // Not JSON — fall through to the bracketed PostgREST range form (`[min,max]`).
        }

        // Comma-separated, stripping the PostgREST range or bracket wrapper if present.
        // Exactly two bounds, never "the first two of however many": `encodeAsRange` emits
        // `(${first},${second})` and nothing else, so a three-token body means a bound itself
        // contained a comma — `sl("d", "a,b" to "c")` encodes as `sl.(a,b,c)`, indistinguishable
        // from a bound of "a" and a bound of "b". There is no way to recover which, and guessing
        // the first two replays a filter the caller never wrote. Returning null instead makes the
        // condition un-replayable, which [replaysFully] turns into a refusal upstream.
        val parts = splitVerbatimElements(trimmed.stripPostgrestListWrapper())
            .filter { it.isNotEmpty() }
        if (parts.size == 2) {
            return parts[0] to parts[1]
        }

        return null
    }

    /**
     * Strips one matching pair of PostgREST value delimiters: the `(...)` wrapper supabase-kt
     * emits for `in` and range operator values, or the `{...}` wrapper it emits for `cs` / `cd` /
     * `ov` array values. The strict capture stores condition values exactly as encoded, so without
     * this the comma-split leaves a stray delimiter on the first and last element (`"(a"`, `"c)"`)
     * and the replayed filter targets corrupted values. Unwrapped values pass through unchanged.
     */
    private fun String.stripPostgrestListWrapper(): String = when {
        length >= 2 && startsWith('(') && endsWith(')') -> substring(1, length - 1)
        length >= 2 && startsWith('{') && endsWith('}') -> substring(1, length - 1)
        // `[...]` is `encodeOverlapAsRange`'s wrapper, and [parseRange] is the only caller that
        // reaches it — but only because every other path returns before getting here, not because
        // a `[`-shaped value is impossible. [parseArray] returns at its JSON branch for any
        // `[`-prefixed value (`null` when it is not decodable JSON), the array operators route the
        // range form through [applyArrayOrRangeCondition] first, and [rowMatch]'s `in`
        // evaluation parses via [parseArray] rather than stripping wrappers itself — which is what
        // keeps the overlay from matching the bounds of a value the replay path drops.
        length >= 2 && startsWith('[') && endsWith(']') -> substring(1, length - 1)
        else -> this
    }
}
