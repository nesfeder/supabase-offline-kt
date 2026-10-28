// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.knownOperation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.decodeFilterOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.equalityDecidedByContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One patch's writes, each body parsed and each filter decoded once for all the queries it is
 * overlaid on. Keyed by the write's id, which a patch draws fresh for every write.
 */
internal class PatchWrites(private val json: Json) {
    private val bodies = HashMap<Long, JsonElement>()
    private val filters = HashMap<Long, FilterDescriptor?>()

    /** [write]'s body, parsed; throws as parsing it does. */
    fun bodyOf(write: MutationRow): JsonElement =
        bodies.getOrPut(write.id) { json.parseToJsonElement(write.bodyJson) }

    /** [write]'s filter, or `null` when it has none that reads. */
    fun filterOf(write: MutationRow): FilterDescriptor? {
        if (write.id in filters) return filters[write.id]
        return decodeFilterOrNull(write.filterJson, json).also { filters[write.id] = it }
    }

    /** An UPDATE's body; `null` when it is not a JSON object. */
    fun patchOf(write: MutationRow): JsonObject? =
        try {
            bodyOf(write) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
}

/**
 * The overlays a patch runs ([CachePatcher]), and what they are built from: which writes are
 * UPDATEs by key. Takes no lock: the patch holds its table's write lock while it overlays.
 *
 * @param onMutationFailure told of a write an overlay skipped: [QueryOverlay.logSkippedOverlay],
 *   which reports it once, as it does for a read's overlay.
 */
internal class PatchOverlays(
    private val json: Json,
    private val primaryKeys: PrimaryKeys,
    private val onMutationFailure: (MutationRow, Exception) -> Unit,
) {

    /**
     * The overlay a patch runs for a query with [admission]: the server holds every row the write
     * made, so a row a refresh already holds is not added twice, and an upsert row with no key to
     * place it by is left out, the query marked stale instead.
     *
     * @param batch the writes' bodies and filters, read once for every query.
     * @param keyed see [MutationOverlay]; only from [keyedUpdatesOf].
     */
    fun overlayFor(
        admission: QueryAdmission,
        batch: PatchWrites,
        keyed: KeyedUpdates?,
    ): MutationOverlay =
        MutationOverlay(
            json = json,
            admission = admission,
            primaryKeys = primaryKeys,
            mayAlreadyHold = { true },
            placesKeylessUpsertRows = false,
            onMutationFailure = onMutationFailure,
            bodyOf = batch::bodyOf,
            filterFor = batch::filterOf,
            keyed = keyed,
        )

    /**
     * [writes] as [KeyedUpdates] when each is an UPDATE naming one key under [keyColumns]
     * ([keyNamedBy]) and `eq` on the key is decided by content ([equalityDecidedByContent]) for
     * every value involved: the keys they name or set, and every row [parsed] holds. `null`
     * otherwise.
     */
    fun keyedUpdatesOf(
        writes: List<MutationRow>,
        keyColumns: List<String>,
        parsed: List<Pair<CachedQueryRow, ParsedPayload>>,
        batch: PatchWrites,
    ): KeyedUpdates? {
        if (keyColumns.isEmpty()) return null
        val keys = HashMap<Long, List<String>>()
        val named = writes.map { write ->
            if (write.knownOperation != MutationOperation.UPDATE) return null
            val filter = batch.filterOf(write) ?: return null
            keys[write.id] = keyNamedBy(filter, keyColumns) ?: return null
            filter.conditions.associate { condition -> condition.column to condition.value }
        }
        val patches = writes.map { write -> batch.patchOf(write) ?: return null }
        val decided = keyColumns.all { column ->
            val values = sequence {
                named.forEach { values -> yield(JsonPrimitive(values.getValue(column))) }
                patches.forEach { patch -> patch[column]?.let { value -> yield(value) } }
                parsed.forEach { (_, payload) ->
                    payload.rows?.forEach { element ->
                        if (element is JsonObject) yield(element[column])
                    }
                }
            }
            equalityDecidedByContent(values, asUuid = true)
        }
        return if (decided) KeyedUpdates(keyColumns, keys) else null
    }

    /** Whether a row, with its key, may matter to [keyed]: it has one of the keys they name. */
    fun keyedUpdatesReach(keyed: KeyedUpdates): (JsonObject, List<String>) -> Boolean {
        val keys = keyed.keys.values.toHashSet()
        return { _, key -> key in keys }
    }
}
