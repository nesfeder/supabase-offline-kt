package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.SignatureFilters
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
 * If parsing fails at any point, or the payload isn't an array, we simply
 * return [serverJson] unchanged to avoid surprising the caller.
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
     * @param pending       Mutations queued for the same table (typically ordered
     *                      by `createdAtMs` ascending).
     * @param targetFilters Parsed filters of the cached query [serverJson] came from. When the
     *                      signature is [SignatureFilters.exhaustive], INSERT/UPSERT-appended rows
     *                      that the query's WHERE clause excludes are dropped instead of appended.
     *                      `null` (the default) preserves the legacy always-append behavior, as do
     *                      non-exhaustive signatures — we never under-apply a row the server would
     *                      return. UPDATE/DELETE targeting is unaffected.
     *
     * @return A JSON string representing the original payload **plus** any
     *         applied local mutations, or [serverJson] unchanged if something
     *         goes wrong.
     */
    fun applyPendingMutations(
        json: Json,
        serverJson: String,
        pending: List<MutationRow>,
        targetFilters: SignatureFilters? = null
    ): String {
        if (pending.isEmpty()) return serverJson

        return runCatching {
            // 1) Parse server payload as JSON array; if not an array, bail.
            val parsed = json.parseToJsonElement(serverJson)
            val array = parsed as? JsonArray ?: return serverJson

            // Copy rows into a mutable buffer we can modify.
            val rows = array.toMutableList()

            // 2) Apply each mutation in creation order (oldest → newest).
            pending.forEach { mutation ->

                val descriptor = FilterEngine.decodeOrNull(mutation.filterJson, json)

                when (mutation.operation) {
                    MutationOperation.INSERT ->
                        applyInsertMutation(json, rows, mutation, targetFilters)

                    MutationOperation.UPSERT ->
                        applyUpsertMutation(json, rows, mutation, descriptor, targetFilters)

                    MutationOperation.UPDATE ->
                        applyUpdateMutation(json, rows, mutation, descriptor)

                    MutationOperation.DELETE ->
                        applyDeleteMutation(rows, descriptor)
                }
            }

            // 3) Serialize back to a JSON string.
            json.encodeToString(ListSerializer(JsonElement.serializer()), rows)
        }.getOrElse {
            // On any failure, fall back to the original payload.
            serverJson
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
     * excludes never lands in that cached payload. We intentionally do **not** try to
     * de-duplicate here; if you need "upsert-like" semantics offline, use the UPSERT path with
     * filters.
     */
    private fun applyInsertMutation(
        json: Json,
        rows: MutableList<JsonElement>,
        mutation: MutationRow,
        targetFilters: SignatureFilters?
    ) {
        when (val bodyElement = json.parseToJsonElement(mutation.bodyJson)) {
            is JsonArray -> bodyElement.forEach { element -> appendIfAdmitted(rows, element, targetFilters) }
            is JsonObject -> appendIfAdmitted(rows, bodyElement, targetFilters)
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
     *  - If [descriptor] is null: treat as a pure INSERT and append the body.
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
        targetFilters: SignatureFilters?
    ) {
        val bodyElement = json.parseToJsonElement(mutation.bodyJson)

        // No target filter → treat as INSERT offline.
        if (descriptor == null) {
            applyInsertMutation(json, rows, mutation, targetFilters)
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
            appendIfAdmitted(rows, patchObj, targetFilters)
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
     */
    private fun appendIfAdmitted(
        rows: MutableList<JsonElement>,
        element: JsonElement,
        targetFilters: SignatureFilters?
    ) {
        val rowObject = element as? JsonObject
        if (rowObject == null || targetFilters == null || targetFilters.admits(rowObject)) {
            rows.add(element)
        }
    }

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