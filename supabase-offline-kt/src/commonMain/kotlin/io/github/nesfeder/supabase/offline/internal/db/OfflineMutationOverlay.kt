package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.mutationqueue.DEFAULT_PRIMARY_KEY
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.SignatureFilters
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Utilities for overlaying **pending offline mutations** on top of a SELECT
 * response payload.
 *
 * This is where we make the local cache appear "eventually consistent" with
 * mutations that have been queued while the device is offline.
 *
 * High-level behaviour
 * --------------------
 * Given:
 *  - [serverJson]: a raw JSON payload from PostgREST `select()` (we assume
 *    it is a JSON array of row objects).
 *  - [pending]: a list of [MutationRow] representing INSERT / UPSERT /
 *    UPDATE / DELETE operations that were queued while offline.
 *
 * We:
 *  1. Parse the server payload into a mutable list of JSON elements.
 *  2. For each mutation:
 *     - decode its filter descriptor (if any) via [FilterEngine.decodeOrNull]
 *     - apply an insert / upsert / patch / delete in memory
 *  3. Serialize the modified array back into a JSON string.
 *
 * If the payload does not parse, or isn't an array, we return [serverJson] unchanged. A mutation
 * that cannot be applied — its stored body no longer parses, or its filter cannot be evaluated
 * against a row — is skipped **on its own**: the other mutations still apply, and the skip is
 * reported through `onMutationFailure`. One bad mutation used to discard the whole batch, hiding
 * every pending write of the table from every screen until the next sync.
 *
 * Note on filters
 * ---------------
 * [MutationRow.filterJson] (when present) is a serialized
 * [FilterDescriptor]. We use [FilterEngine.matches] to decide which rows a
 * mutation applies to. For complex filters we can't faithfully model
 * offline, we fall back to "no matches" so that we **under-apply** rather
 * than silently diverging from server behaviour.
 *
 * Note on query visibility
 * ------------------------
 * INSERT/UPSERT-appended rows are additionally gated by the cached query's own filters
 * ([targetFilters], a [SignatureFilters]). When the query's signature is exhaustive, a row its
 * WHERE clause excludes is dropped rather than appended — so, e.g., an online INSERT of a `done`
 * row never lands in a cached `status=eq.open` payload. This gate is conservative: a `null` or
 * non-exhaustive [targetFilters] preserves the legacy always-append behavior, so we never hide
 * a row the server would have returned.
 */
internal object OfflineMutationOverlay {

    /**
     * Overlay [pending] mutations for a given table onto the SELECT response
     * [serverJson].
     *
     * @param json          The shared [Json] instance used for (de)serialization.
     * @param serverJson    Raw JSON string returned by PostgREST `select(...)`.
     *                      We expect a JSON array (`[...]`).
     * @param pending       Mutations queued for the same table, in queue order.
     * @param targetFilters Parsed filters of the cached query [serverJson] came from. When the
     *                      signature is [SignatureFilters.exhaustive], INSERT/UPSERT-appended rows
     *                      that the query's WHERE clause excludes are dropped instead of appended.
     *                      `null` (the default) preserves the legacy always-append behavior, as do
     *                      non-exhaustive signatures — we never under-apply a row the server would
     *                      return. UPDATE/DELETE targeting is unaffected.
     * @param keyColumns    The table's primary-key columns. An INSERT row that carries the key is
     *                      not appended when a row with the same key is already there — the server
     *                      would not add a second one either — so a write the payload already holds
     *                      is never shown twice. An UPSERT row with no filter merges into the row with
     *                      its key (left alone with `ignoreDuplicates`), as the server's upsert on
     *                      the primary key does.
     * @param onMutationFailure Told about each mutation that was skipped because applying it threw,
     *                      so the caller can log it. Never given the mutation's body to log.
     *
     * @return A JSON string representing the original payload **plus** every
     *         local mutation that could be applied, or [serverJson] unchanged when
     *         it is not a JSON array.
     */
    fun applyPendingMutations(
        json: Json,
        serverJson: String,
        pending: List<MutationRow>,
        targetFilters: SignatureFilters? = null,
        keyColumns: List<String> = DEFAULT_PRIMARY_KEY,
        onMutationFailure: (MutationRow, Exception) -> Unit = { _, _ -> },
    ): String {
        if (pending.isEmpty()) return serverJson

        // 1) Parse server payload as JSON array; if it does not parse or is not an array, bail.
        val array = try {
            json.parseToJsonElement(serverJson) as? JsonArray
        } catch (_: IllegalArgumentException) {
            null
        } ?: return serverJson

        // Copy rows into a mutable buffer we can modify.
        var rows = array.toMutableList()

        // 2) Apply each mutation in queue order (oldest → newest), each to its own working copy so
        // a mutation that throws half-way leaves no partial change behind.
        pending.forEach { mutation ->
            val working = rows.toMutableList()
            try {
                applyMutation(json, working, mutation, targetFilters, keyColumns)
                rows = working
            } catch (failure: Exception) {
                onMutationFailure(mutation, failure)
            }
        }

        // 3) Serialize back to a JSON string.
        return json.encodeToString(ListSerializer(JsonElement.serializer()), rows)
    }

    private fun applyMutation(
        json: Json,
        rows: MutableList<JsonElement>,
        mutation: MutationRow,
        targetFilters: SignatureFilters?,
        keyColumns: List<String>,
    ) {
        val descriptor = FilterEngine.decodeOrNull(mutation.filterJson, json)

        when (mutation.operation) {
            MutationOperation.INSERT ->
                applyInsertMutation(json, rows, mutation, targetFilters, keyColumns)

            MutationOperation.UPSERT ->
                applyUpsertMutation(json, rows, mutation, descriptor, targetFilters, keyColumns)

            MutationOperation.UPDATE ->
                applyUpdateMutation(json, rows, mutation, descriptor)

            MutationOperation.DELETE ->
                applyDeleteMutation(rows, descriptor)
        }
    }

    // -------------------------------------------------------------------------
    //  INSERT
    // -------------------------------------------------------------------------

    /**
     * Apply an INSERT mutation:
     *
     * - If `body` is a JSON array: append every element the query admits.
     * - If `body` is a JSON object: append it if the query admits it.
     * - Otherwise: ignore.
     *
     * Each candidate row is gated by [appendIfAdmitted] so a row an exhaustive-filter query
     * excludes never lands in that cached payload. A row that carries the table's primary key is
     * not appended when the payload already holds that key — the server keeps one row per key — so
     * a write the payload already reflects is never shown twice. Keyless rows are always appended.
     */
    private fun applyInsertMutation(
        json: Json,
        rows: MutableList<JsonElement>,
        mutation: MutationRow,
        targetFilters: SignatureFilters?,
        keyColumns: List<String>,
    ) {
        when (val bodyElement = json.parseToJsonElement(mutation.bodyJson)) {
            is JsonArray -> bodyElement.forEach { element -> appendIfAdmitted(rows, element, targetFilters, keyColumns) }
            is JsonObject -> appendIfAdmitted(rows, bodyElement, targetFilters, keyColumns)
            else -> Unit
        }
    }

    // -------------------------------------------------------------------------
    //  UPSERT
    // -------------------------------------------------------------------------

    /**
     * Apply an UPSERT mutation.
     *
     * Offline we approximate PostgREST behaviour as:
     *  - If [descriptor] is null: each body row that carries the table's primary key and matches a
     *    row already in the payload by that key is merged into it (left untouched with
     *    `ignoreDuplicates`); every other row is appended, as for an INSERT.
     *  - If [descriptor] matches existing rows:
     *      - with `ignoreDuplicates` set: leave them untouched (ON CONFLICT DO NOTHING)
     *      - otherwise: shallow-merge the UPSERT body into each matching row
     *  - If [descriptor] matches no rows:
     *      - append the UPSERT body (insert new row)
     *
     * We only operate on a single row object from the body:
     *  - if body is an object: use it directly
     *  - if body is an array: use the first element if it is an object
     */
    private fun applyUpsertMutation(
        json: Json,
        rows: MutableList<JsonElement>,
        mutation: MutationRow,
        descriptor: FilterDescriptor?,
        targetFilters: SignatureFilters?,
        keyColumns: List<String>,
    ) {
        val bodyElement = json.parseToJsonElement(mutation.bodyJson)

        // No target filter → the conflict target is the primary key: merge by key, else append.
        if (descriptor == null) {
            val bodyRows = when (bodyElement) {
                is JsonArray -> bodyElement
                is JsonObject -> listOf(bodyElement)
                else -> return
            }
            bodyRows.forEach { element ->
                val index = indexOfKey(rows, element, keyColumns)
                if (index == null) {
                    appendIfAdmitted(rows, element, targetFilters, keyColumns)
                    return@forEach
                }
                if (mutation.ignoreDuplicates == true) return@forEach
                val current = rows[index] as? JsonObject ?: return@forEach
                val patch = element as? JsonObject ?: return@forEach
                rows[index] = mergeObjects(current, patch)
            }
            return
        }

        val patchObj: JsonObject = when (bodyElement) {
            is JsonObject -> bodyElement
            is JsonArray -> bodyElement.firstOrNull() as? JsonObject ?: return
            else -> return
        }

        val indices = matchingIndices(rows, descriptor)
        if (indices.isEmpty()) {
            // No match → append as a new row, subject to the cached query's own visibility filter.
            appendIfAdmitted(rows, patchObj, targetFilters, keyColumns)
            return
        }

        // ON CONFLICT DO NOTHING: with ignoreDuplicates the server preserves an existing row
        // untouched, so the overlay must too. Merging the request body here would make the
        // cache adopt a write the server rejected — an insert-if-missing that raced a
        // pre-existing canonical row (e.g. one another device already wrote) was visibly
        // replacing the canonical value locally until the next network refresh.
        if (mutation.ignoreDuplicates == true) return

        // Merge into each matching row.
        indices.forEach { idx ->
            val current = rows[idx] as? JsonObject ?: return@forEach
            rows[idx] = mergeObjects(current, patchObj)
        }
    }

    // -------------------------------------------------------------------------
    //  UPDATE
    // -------------------------------------------------------------------------

    /**
     * Apply an UPDATE mutation.
     *
     * - If [descriptor] is null: we cannot safely know which rows to touch,
     *   so we skip this mutation offline.
     * - Otherwise:
     *   - decode `body` as a JSON object
     *   - shallow-merge body into every row that matches [descriptor]
     */
    private fun applyUpdateMutation(
        json: Json,
        rows: MutableList<JsonElement>,
        mutation: MutationRow,
        descriptor: FilterDescriptor?
    ) {
        if (descriptor == null) return

        val patchElement = json.parseToJsonElement(mutation.bodyJson)
        val patchObj = patchElement as? JsonObject ?: return

        val indices = matchingIndices(rows, descriptor)
        indices.forEach { idx ->
            val current = rows[idx] as? JsonObject ?: return@forEach
            rows[idx] = mergeObjects(current, patchObj)
        }
    }

    // -------------------------------------------------------------------------
    //  DELETE
    // -------------------------------------------------------------------------

    /**
     * Apply a DELETE mutation.
     *
     * - If [descriptor] is null: we don't know which rows to remove, so we
     *   do nothing offline.
     * - Otherwise: remove all rows that match [descriptor].
     */
    private fun applyDeleteMutation(
        rows: MutableList<JsonElement>,
        descriptor: FilterDescriptor?
    ) {
        if (descriptor == null) return
        val indices = matchingIndices(rows, descriptor)

        // Remove from the end so indices stay valid.
        indices.sortedDescending().forEach { idx ->
            rows.removeAt(idx)
        }
    }

    // -------------------------------------------------------------------------
    //  Shared helpers
    // -------------------------------------------------------------------------

    /**
     * Append [element] to [rows] only if [targetFilters] admits it into the cached query.
     *
     * Gating is applied per row so a batch insert lands each of its rows in exactly the cached
     * queries that would return it. A non-object [element] (which [FilterEngine.matchesRow] can't
     * evaluate) is always appended, matching the legacy behavior — we drop a row only when we can
     * prove it doesn't belong.
     *
     * @param rows          The mutable buffer of cached rows.
     * @param element       The candidate row to append.
     * @param targetFilters Parsed filters of the cached query, or `null` for legacy always-append.
     * @param keyColumns    The table's primary key; a row whose key is already present is not added.
     */
    private fun appendIfAdmitted(
        rows: MutableList<JsonElement>,
        element: JsonElement,
        targetFilters: SignatureFilters?,
        keyColumns: List<String>,
    ) {
        val rowObject = element as? JsonObject
        if (rowObject != null && targetFilters != null && !targetFilters.admits(rowObject)) return
        if (indexOfKey(rows, element, keyColumns) != null) return
        rows.add(element)
    }

    /**
     * The index of the row in [rows] with [element]'s primary key, or `null` when [element] carries
     * no whole key or no row has it.
     */
    private fun indexOfKey(rows: List<JsonElement>, element: JsonElement, keyColumns: List<String>): Int? {
        val key = primaryKeyOf(element, keyColumns) ?: return null
        val index = rows.indexOfFirst { existing ->
            val existingKey = primaryKeyOf(existing, keyColumns)
            existingKey != null && sameKey(key, existingKey)
        }
        return index.takeIf { it >= 0 }
    }

    /** Whether two primary keys name the same row, comparing values by their JSON content. */
    private fun sameKey(first: JsonObject, second: JsonObject): Boolean =
        first.keys == second.keys &&
            first.all { (column, value) -> (value as? JsonPrimitive)?.content == (second[column] as? JsonPrimitive)?.content }

    /**
     * Returns the indices of all rows that satisfy [descriptor] according to
     * [FilterEngine.matchesRow].
     */
    private fun matchingIndices(
        rows: List<JsonElement>,
        descriptor: FilterDescriptor
    ): List<Int> {
        val result = mutableListOf<Int>()
        rows.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            if (FilterEngine.matchesRow(obj, descriptor)) {
                result += index
            }
        }
        return result
    }

    /**
     * Simple shallow merge:
     *
     * - keys from [patch] overwrite keys in [base]
     * - other keys from [base] are preserved
     */
    private fun mergeObjects(
        base: JsonObject,
        patch: JsonObject
    ): JsonObject {
        val combined = base.toMutableMap()
        for ((k, v) in patch) {
            combined[k] = v
        }
        return JsonObject(combined)
    }
}