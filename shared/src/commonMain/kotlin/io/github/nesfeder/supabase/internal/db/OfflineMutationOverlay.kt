package io.github.nesfeder.supabase.internal.db

import io.github.nesfeder.supabase.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.internal.postgrest.FilterEngine
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
 */
internal object OfflineMutationOverlay {

    /**
     * Overlay [pending] mutations for a given table onto the SELECT response
     * [serverJson].
     *
     * @param json       The shared [Json] instance used for (de)serialization.
     * @param serverJson Raw JSON string returned by PostgREST `select(...)`.
     *                   We expect a JSON array (`[...]`).
     * @param pending    Mutations queued for the same table (typically ordered
     *                   by `createdAtMs` ascending).
     *
     * @return A JSON string representing the original payload **plus** any
     *         applied local mutations, or [serverJson] unchanged if something
     *         goes wrong.
     */
    fun applyPendingMutations(
        json: Json,
        serverJson: String,
        pending: List<MutationRow>
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
                        applyInsertMutation(json, rows, mutation)

                    MutationOperation.UPSERT ->
                        applyUpsertMutation(json, rows, mutation, descriptor)

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
     * - If `body` is a JSON array: append all elements.
     * - If `body` is a JSON object: append that object.
     * - Otherwise: ignore.
     *
     * We intentionally do **not** try to de-duplicate here; if you need
     * "upsert-like" semantics offline, use the UPSERT path with filters.
     */
    private fun applyInsertMutation(
        json: Json,
        rows: MutableList<JsonElement>,
        mutation: MutationRow
    ) {
        when (val bodyElement = json.parseToJsonElement(mutation.bodyJson)) {
            is JsonArray -> rows.addAll(bodyElement)
            is JsonObject -> rows.add(bodyElement)
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
     *      - shallow-merge the UPSERT body into each matching row
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
        descriptor: FilterDescriptor?
    ) {
        val bodyElement = json.parseToJsonElement(mutation.bodyJson)

        // No target filter → treat as INSERT offline.
        if (descriptor == null) {
            applyInsertMutation(json, rows, mutation)
            return
        }

        val patchObj: JsonObject = when (bodyElement) {
            is JsonObject -> bodyElement
            is JsonArray -> bodyElement.firstOrNull() as? JsonObject ?: return
            else -> return
        }

        val indices = matchingIndices(rows, descriptor)
        if (indices.isEmpty()) {
            // No match → append as a new row.
            rows.add(patchObj)
            return
        }

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