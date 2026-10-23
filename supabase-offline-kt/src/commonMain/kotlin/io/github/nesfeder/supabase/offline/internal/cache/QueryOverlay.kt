// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.PayloadWriteCountingDao
import io.github.nesfeder.supabase.offline.internal.db.fetchedAtOf
import io.github.nesfeder.supabase.offline.internal.db.isReservedTable
import io.github.nesfeder.supabase.offline.internal.db.isStaleStamp
import io.github.nesfeder.supabase.offline.internal.db.knownOperation
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.postgrest.decodeFilterOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.equalityDecidedByContent
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.withLock
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
 * Reads served with the mutations a cached payload does not hold laid over it: the current
 * identity's pending ones, and replayed ones newer than the payload. Rows other queries hold, read
 * with the same projection, stand in for rows a mutation brings in, found through each table's key
 * index. Builds the overlay a patch runs, too. Owns the [TableSnapshotLocks].
 *
 * @param queryCacheDao the object cache, whose write counts say when a key index is stale.
 * @param freshness the clear lock, under which a read's record of its query's shape is written.
 * @param identityProvider the current identity's stored value, or `null` when it cannot be told.
 * @param identityChanges emits when the session may have changed, so a reader's overlay is laid
 *   again for the identity.
 * @param engineLogger where a skipped mutation is reported, once.
 */
internal class QueryOverlay(
    private val queryCacheDao: PayloadWriteCountingDao,
    private val json: Json,
    private val queryCache: QueryCache,
    private val freshness: CacheFreshness,
    private val mutationQueue: MutationQueue,
    private val ownership: CacheOwnership,
    private val primaryKeys: PrimaryKeys,
    private val identityProvider: suspend () -> String?,
    private val identityChanges: Flow<Any?>,
    private val engineLogger: EngineLogger,
) {

    /** Make a read of a table's payload and its mutations one step; see [holdingReadersOf]. */
    private val snapshotLocks = TableSnapshotLocks()

    /**
     * Runs [change], which changes [table]'s cached payloads and its queued mutations, as one step
     * for the table's readers: each reads the table before it or after it, never in between.
     */
    suspend fun <T> holdingReadersOf(table: String, change: suspend () -> T): T =
        snapshotLocks.of(table).withLock { change() }

    /**
     * [table]'s queued mutations and the payload cached for [cacheKey], read as one step: no change
     * made under [holdingReadersOf] lands between them. The mutations first, so a payload write
     * that retires a replayed mutation after it is never read with the mutation already gone.
     */
    private suspend fun readTogether(table: String, cacheKey: String): TableSnapshot =
        snapshotLocks.of(table).withLock {
            val unconfirmedSends = mutationQueue.unconfirmedSends()
            val mutations = mutationQueue.tableMutationsNow(table)
            TableSnapshot(unconfirmedSends, mutations, queryCacheDao.get(table, cacheKey))
        }

    /**
     * What [readTogether] read of a table.
     *
     * @property unconfirmedSends the mutations whose send may have reached the server.
     * @property mutations the table's mutation rows.
     * @property row the payload cached for the query, or `null` for none.
     */
    private class TableSnapshot(
        val unconfirmedSends: Set<Long>,
        val mutations: MutationQueue.TableMutations,
        val row: CachedQueryRow?,
    )

    /**
     * Overlays the current identity's pending mutations of [table] onto a fresh network
     * [serverJson] for the query [cacheKey], saying whether one changed a row it shows. Synced ones
     * are left out: the server already reflects them.
     *
     * @param shape the query's shape; `null` looks up the one recorded for [cacheKey].
     */
    suspend fun overlayPendingOnResponseReporting(
        table: String,
        cacheKey: String,
        serverJson: String,
        shape: QueryShape? = null,
    ): ServedOverlay {
        if (isReservedTable(table)) return ServedOverlay(serverJson, hasPendingMutations = false)
        val pending =
            mutationQueue.visibleTo(
                identityProvider(),
                mutationQueue.pendingMutationsForTable(table),
            )
        if (pending.isEmpty()) return ServedOverlay(serverJson, hasPendingMutations = false)

        return overlayMutations(
                serverJson,
                pending,
                admissionOf(table, cacheKey, shape),
                mutationQueue.unconfirmedSends(),
                sameSelectRowsFor(table, cacheKey, shape, pending, ownJson = serverJson),
            )
            .served(pending)
    }

    /**
     * The payload cached for [table]/[cacheKey] with the identity's mutations it does not hold laid
     * over it, read in one step with them; `null` when none is cached, the cache is not the current
     * identity's, or [serves] refuses the payload's fetch stamp.
     */
    suspend fun cachedPayloadOverlaid(
        table: String,
        cacheKey: String,
        shape: QueryShape? = null,
        serves: (updatedAt: Long) -> Boolean = { true },
    ): OverlaidCachedPayload? {
        if (isReservedTable(table) || !ownership.cacheServesCurrentIdentity()) return null
        // Read before the snapshot: a clear landing after it voids the shape record written below
        val readTick = freshness.tickNow()
        val snapshot = readTogether(table, cacheKey)
        val stored = snapshot.row?.takeIf { cached -> serves(cached.updatedAt) } ?: return null
        val overlaid =
            overlayCachedPayloadReporting(
                table = table,
                cacheKey = cacheKey,
                cachedJson = stored.payload,
                shape = shape,
                readWithIt = snapshot,
                readTick = readTick,
            )
        return OverlaidCachedPayload(overlaid, stored.updatedAt)
    }

    /**
     * Overlays the identity's pending mutations, and synced ones newer than [cachedJson], onto that
     * cached payload; says whether a pending one changed a row it shows.
     *
     * @param cacheKey The payload's query, or `null` when not known: every synced mutation applies.
     * @param shape the query's shape, recorded with the payload; `null` looks up the one recorded.
     */
    suspend fun overlayCachedPayloadReporting(
        table: String,
        cacheKey: String?,
        cachedJson: String,
        shape: QueryShape? = null,
    ): ServedOverlay =
        overlayCachedPayloadReporting(
            table,
            cacheKey,
            cachedJson,
            shape,
            readWithIt = null,
            readTick = freshness.tickNow(),
        )

    /**
     * [overlayCachedPayloadReporting], with the queue's records as [readWithIt] read them with the
     * payload, or as they are now when it is `null`.
     *
     * @param readTick the tick read before the payload was; a clear since voids the shape record.
     */
    private suspend fun overlayCachedPayloadReporting(
        table: String,
        cacheKey: String?,
        cachedJson: String,
        shape: QueryShape?,
        readWithIt: TableSnapshot?,
        readTick: Long,
    ): ServedOverlay {
        if (isReservedTable(table)) return ServedOverlay(cachedJson, hasPendingMutations = false)
        if (cacheKey != null && shape != null)
            rememberUnlessCleared(table, cacheKey, shape, readTick)
        val visible =
            mutationQueue.visibleTo(
                identityProvider(),
                readWithIt?.mutations?.rows ?: mutationQueue.mutationsOfTable(table),
            )
        // The stored payload's coverage, read only when a synced mutation may be held by it
        val syncedThrough =
            if (cacheKey == null || visible.all { mutation -> mutation.syncedAtMs == null }) 0L
            else if (readWithIt != null) readWithIt.row?.syncedThrough ?: 0L
            else queryCacheDao.get(table, cacheKey)?.syncedThrough ?: 0L
        val all = visible.filter { mutation -> belongsOnPayload(mutation, syncedThrough) }
        if (all.isEmpty()) return ServedOverlay(cachedJson, hasPendingMutations = false)

        val admission = cacheKey?.let { admissionOf(table, it, shape) } ?: QueryAdmission.EVERY_ROW
        return overlayFor(
                admission,
                readWithIt?.unconfirmedSends ?: mutationQueue.unconfirmedSends(),
            )
            .overlayReportingChanges(
                cachedJson,
                all,
                sameSelectRowsFor(table, cacheKey, shape, all, ownJson = cachedJson),
            )
            .served(all)
    }

    /**
     * This overlay as served: whether one of [mutations] that is still pending, not synced, changed
     * a row it shows.
     */
    private fun OverlaidPayload.served(mutations: List<MutationRow>): ServedOverlay {
        val pendingIds =
            mutations.mapNotNullTo(HashSet()) { mutation ->
                mutation.id.takeIf { mutation.syncedAtMs == null }
            }
        return ServedOverlay(payload, shownChangedBy.any { id -> id in pendingIds })
    }

    /**
     * The cached payload of [table]/[cacheKey] with the current identity's mutations overlaid, as
     * it changes, each paired with the value [alongside] held when it was read; `null` while
     * nothing is known. Each carries its fetch time, staleness and whether a pending mutation
     * changed a shown row; with no payload, mutations are overlaid onto an empty list, and another
     * identity's cache counts as none.
     *
     * Every write to the table's cached payloads, change of its mutations, of the session or of
     * [alongside] reads all of them again, as one view: [alongside] first, then the cache's owner,
     * then the mutations and the payload together ([readTogether]). So an emission never pairs a
     * payload with mutations, an owner or a value of [alongside] it was not current with, whatever
     * order the changes arrive in.
     *
     * @param alongside a value its writer publishes after writing the payload it describes, such as
     *   a refresh's progress.
     */
    fun <T> servedPayloadFlow(
        table: String,
        cacheKey: String,
        shape: QueryShape?,
        alongside: StateFlow<T>,
    ): Flow<Pair<ServedPayload?, T>> {
        if (isReservedTable(table)) return alongside.map { value -> null to value }
        return flow {
            val reader = LiveOverlay(table, cacheKey, shape)
            // The view as the read starts; each source then emits once on subscription, so a
            // change in between is read too
            emit(reader.read(alongside))
            merge(
                    // A write to this table's payloads, once committed; another table's, or the
                    // engine's reserved rows', costs a comparison and no read
                    queryCacheDao.writeChangesOf(table),
                    mutationQueue.mutationChangesOf(table),
                    identityChanges.map {},
                    alongside.map {},
                )
                .conflate()
                .collect { emit(reader.read(alongside)) }
        }
            .distinctUntilChanged()
    }

    /**
     * One live reader's overlay of [table]/[cacheKey]: the view it last read, and the overlay it
     * built for it, built again only when the view changed.
     */
    private inner class LiveOverlay(
        private val table: String,
        private val cacheKey: String,
        private val shape: QueryShape?,
    ) {
        private var last: OverlaidInputs? = null
        private var lastOverlaid: ServedOverlay? = null

        /** Reads the view now, in the order [servedPayloadFlow] states, and serves it. */
        suspend fun <T> read(alongside: StateFlow<T>): Pair<ServedPayload?, T> {
            val value = alongside.value
            // Read before the snapshot: a clear landing after it voids the shape record written
            val readTick = freshness.tickNow()
            val identity = identityProvider()
            val served = ownership.cacheServesCurrentIdentity()
            val snapshot = readTogether(table, cacheKey)
            val servedRow = snapshot.row?.takeIf { served }?.let(::StoredRow)
            val inputs =
                OverlaidInputs(
                    servedRow?.content,
                    mutationQueue.visibleTo(identity, snapshot.mutations.rows),
                    snapshot.unconfirmedSends,
                )
            if (inputs != last) {
                lastOverlaid =
                    overlaidOrNull(
                        table,
                        cacheKey,
                        shape,
                        inputs,
                        snapshot.mutations::bodyOf,
                        readTick,
                    )
                last = inputs
            }
            val payload = lastOverlaid?.let { overlay ->
                ServedPayload(
                    payload = overlay.payload,
                    fetchedAtMillis = servedRow?.let { stored -> fetchedAtOf(stored.updatedAt) },
                    isStale = servedRow == null || isStaleStamp(servedRow.updatedAt),
                    hasPendingMutations = overlay.hasPendingMutations,
                )
            }
            return payload to value
        }
    }

    /** A cached payload as stored: its [content] and its fetch stamp. */
    private data class StoredRow(val content: StoredPayload, val updatedAt: Long) {
        constructor(row: CachedQueryRow) : this(StoredPayload(row), row.updatedAt)
    }

    /** A cached payload's text and what it holds of the synced mutations. */
    private data class StoredPayload(val payload: String, val syncedThrough: Long) {
        constructor(row: CachedQueryRow) : this(row.payload, row.syncedThrough)
    }

    /**
     * What a reader's overlay is a function of: the payload it is served, `null` for none, the
     * mutations of its table visible to the identity, in order, and the sends that may have reached
     * the server.
     */
    private data class OverlaidInputs(
        val stored: StoredPayload?,
        val mutations: List<MutationRow>,
        val unconfirmedSends: Set<Long>,
    )

    /**
     * [servedPayloadFlow]'s overlay of [inputs]: the payload the current identity is served (`null`
     * for none), with those of its mutations the payload does not hold.
     *
     * @param readTick the tick read before [inputs] were; a clear since voids the shape record.
     */
    private suspend fun overlaidOrNull(
        table: String,
        cacheKey: String,
        shape: QueryShape?,
        inputs: OverlaidInputs,
        bodyOf: (MutationRow) -> JsonElement,
        readTick: Long,
    ): ServedOverlay? {
        val stored = inputs.stored
        // A live query's shape is known for as long as it is read; it is written once a payload
        // exists
        if (shape != null) {
            if (stored != null) rememberUnlessCleared(table, cacheKey, shape, readTick)
            else queryCache.queryShapes.remember(table, cacheKey, shape, persist = false)
        }
        val cachedJson = stored?.payload
        val syncedThrough = stored?.syncedThrough ?: 0L
        val applicable =
            inputs.mutations.filter { mutation -> belongsOnPayload(mutation, syncedThrough) }
        if (applicable.isEmpty())
            return cachedJson?.let { json -> ServedOverlay(json, hasPendingMutations = false) }
        val overlaid =
            overlayMutations(
                    cachedJson ?: EMPTY_JSON_ARRAY,
                    applicable,
                    admissionOf(table, cacheKey, shape),
                    inputs.unconfirmedSends,
                    sameSelectRowsFor(table, cacheKey, shape, applicable, ownJson = cachedJson),
                    bodyOf,
                )
                .served(applicable)
        return if (cachedJson == null && isEmptyJsonArrayPayload(overlaid.payload)) null
        else overlaid
    }

    /**
     * Records that the query cached under [cacheKey] has [shape] and writes the record, as one step
     * with a clear: not once local data was cleared, or the cache dropped, since [readTick], when
     * the payload the record would sit beside is gone and the record, with the query's filter
     * values, would outlive it. The shape is then still known in memory, as a live query's is with
     * no payload. A read whose record is already written takes no lock but the records' own.
     */
    private suspend fun rememberUnlessCleared(
        table: String,
        cacheKey: String,
        shape: QueryShape,
        readTick: Long,
    ) {
        if (queryCache.queryShapes.isRecorded(table, cacheKey, shape)) return
        val written =
            freshness.writeUnlessCleared(readTick) {
                queryCache.queryShapes.remember(table, cacheKey, shape, persist = true)
            }
        if (!written) queryCache.queryShapes.remember(table, cacheKey, shape, persist = false)
    }

    /**
     * Overlays [mutations] onto [serverJson] for a query with [admission], showing only the rows it
     * admits.
     *
     * @param unconfirmedSends mutations whose send may have reached the server; see
     *   [isSyncedOrUnconfirmed].
     * @param knownRows rows the table's other queries hold, read with the same columns: those a
     *   mutation makes start matching join.
     * @param bodyOf a mutation's body, parsed.
     */
    private fun overlayMutations(
        serverJson: String,
        mutations: List<MutationRow>,
        admission: QueryAdmission,
        unconfirmedSends: Set<Long>,
        knownRows: List<JsonObject> = emptyList(),
        bodyOf: (MutationRow) -> JsonElement = { mutation ->
            json.parseToJsonElement(mutation.bodyJson)
        },
    ): OverlaidPayload =
        overlayFor(admission, unconfirmedSends, bodyOf)
            .overlayReportingChanges(serverJson, mutations, knownRows)

    /**
     * The rows the table's other cached queries hold, read with the same [Projection] as the query
     * cached under [cacheKey], when one of [mutations] may bring such a row into it; see
     * [mayBringRowsIn]. Empty otherwise, and when the query's shape is unknown: then the other
     * payloads are not read at all.
     *
     * @param ownJson the rows the query shows before [mutations], when known.
     */
    private suspend fun sameSelectRowsFor(
        table: String,
        cacheKey: String?,
        shape: QueryShape?,
        mutations: List<MutationRow>,
        ownJson: String?,
    ): List<JsonObject> {
        if (cacheKey == null) return emptyList()
        val known = shape ?: queryCache.queryShapes.shapeOf(table, cacheKey) ?: return emptyList()
        // The filter's column names as a JSON body writes them, once for every mutation
        val filterColumns = known.conditions.map { condition -> jsonNameOf(condition.column) }
        val keyColumns = primaryKeys.of(table)
        val ownKeys by lazy {
            ownJson?.let { text ->
                queryCache
                    .parsedTextOf(table, cacheKey, text)
                    .keysFor(keyColumns)
                    .filterNotNullTo(HashSet())
            } ?: emptySet()
        }
        val bringing = mutations.filter { mutation ->
            mayBringRowsIn(mutation, known, filterColumns, keyColumns) { ownKeys }
        }
        if (bringing.isEmpty()) return emptyList()
        val reaching = mutationsThatReach(bringing, mutations, primaryKeys, json)
        val mayMatter =
            rowsThatMayMatter(table, reaching, primaryKeys, json, unknownMatters = false)
                ?: return emptyList()
        val projection = projectionOf(known)
        val shapes = queryCache.queryShapes.shapesOf(table)
        // Upserts on the key need only the rows with their keys: found by key when the index holds
        upsertedKeysOf(reaching, keyColumns)?.let { keys ->
            borrowedByKey(table, cacheKey, projection, shapes, keys, keyColumns)?.let { rows ->
                return rows
            }
        }
        val sameSelect =
            queryCacheDao.allForTable(table).filter { row ->
                row.cacheKey != cacheKey && shapes[row.cacheKey]?.let(::projectionOf) == projection
            }
        if (sameSelect.isEmpty()) return emptyList()
        val parsed = sameSelect.map { row -> row to queryCache.parsedPayloadOf(row) }
        return KnownRows(parsed, shapes, keyColumns, mayMatter)
            .targetFor(cacheKey, known, rows = emptyList())
            .sameSelectRows
    }

    /**
     * The keys [mutations] upsert, when each is an UPSERT matching rows on [keyColumns]: the only
     * rows held elsewhere they read. `null` when one is another write.
     */
    private fun upsertedKeysOf(
        mutations: List<MutationRow>,
        keyColumns: List<String>,
    ): Set<List<String>>? {
        val keys = LinkedHashSet<List<String>>()
        for (mutation in mutations) {
            if (mutation.knownOperation != MutationOperation.UPSERT) return null
            if (conflictColumnsOf(mutation) != keyColumns) return null
            upsertRowsOf(mutation, json).forEach { row -> keyOf(row, keyColumns)?.let(keys::add) }
        }
        return keys
    }

    /**
     * For each of [keys], the row with it from the most recently fetched other query of [table]
     * read with [projection], as [KnownRows] gives it; `null` when [tableKeyIndexOf] cannot say.
     * Reads only the payloads that hold one of [keys].
     *
     * @param cacheKey the reading query's cache key; its own payload is never drawn on.
     * @param shapes the table's query shapes, by cache key.
     */
    private suspend fun borrowedByKey(
        table: String,
        cacheKey: String,
        projection: Projection,
        shapes: Map<String, QueryShape?>,
        keys: Set<List<String>>,
        keyColumns: List<String>,
    ): List<JsonObject>? {
        val index = tableKeyIndexOf(table, keyColumns) ?: return null
        val borrowed = mutableListOf<JsonObject>()
        for (key in keys) {
            val holder =
                index.holders[key]?.firstOrNull { held ->
                    held.cacheKey != cacheKey &&
                        shapes[held.cacheKey]?.let(::projectionOf) == projection
                } ?: continue
            // The index is read with the payload writes it counted; this checks the row is there
            val row = queryCacheDao.get(table, holder.cacheKey) ?: return null
            val payload = queryCache.parsedPayloadOf(row)
            if (payload.keysFor(keyColumns).getOrNull(holder.position) != key) return null
            borrowed += payload.rows?.getOrNull(holder.position) as? JsonObject ?: return null
        }
        return borrowed
    }

    /**
     * The newest cached row of [table] with [key] (as [keyOf] gives it) that holds [column], from
     * the payloads newest fetch first. `null` when none does, the cache is not [identity]'s, or the
     * table is too large for its key index. May rebuild the key index: no lock may be held.
     */
    suspend fun cachedRowHolding(
        table: String,
        key: List<String>,
        column: String,
        identity: String?,
    ): JsonObject? {
        if (isReservedTable(table) || !ownership.cacheServes(identity)) return null
        val keyColumns = primaryKeys.of(table)
        val index = tableKeyIndexOf(table, keyColumns) ?: return null
        for (holder in index.holders[key].orEmpty()) {
            val row = queryCacheDao.get(table, holder.cacheKey) ?: continue
            val payload = queryCache.parsedPayloadOf(row)
            // The index may predate a write to this payload: the row must still be the key's
            if (payload.keysFor(keyColumns).getOrNull(holder.position) != key) continue
            val candidate = payload.rows?.getOrNull(holder.position) as? JsonObject ?: continue
            if (column in candidate) return candidate
        }
        return null
    }

    /**
     * [table]'s cached rows by key, as last read, or read now when a payload of the table was
     * written since; `null` when the table holds more rows than [MAX_KEY_INDEXED_ROWS]. Kept only
     * when no payload write landed while it was read.
     */
    private suspend fun tableKeyIndexOf(table: String, keyColumns: List<String>): TableKeyIndex? {
        val writes = queryCacheDao.writesTo(table)
        tableKeyIndexes.load()[table]?.let { index ->
            if (index.writes == writes && index.keyColumns == keyColumns) return index
        }
        // Most recently fetched first, as KnownRows orders the copies of one row
        val newestFirst =
            queryCacheDao
                .allForTable(table)
                .map { row -> row to queryCache.parsedPayloadOf(row) }
                .sortedByDescending { (row, _) -> fetchedAtOf(row.updatedAt) }
        val holders = HashMap<List<String>, MutableList<KeyHolder>>()
        var indexed = 0
        for ((row, payload) in newestFirst) {
            payload.keysFor(keyColumns).forEachIndexed { position, key ->
                if (key == null) return@forEachIndexed
                if (++indexed > MAX_KEY_INDEXED_ROWS) return null
                holders.getOrPut(key) { mutableListOf() } += KeyHolder(row.cacheKey, position)
            }
        }
        val index = TableKeyIndex(writes, keyColumns, holders)
        if (queryCacheDao.writesTo(table) == writes) {
            tableKeyIndexes.update { indexes -> indexes + (table to index) }
        }
        return index
    }

    /**
     * A table's cached rows by key: for each key, the payloads holding a row with it, most recently
     * fetched first.
     *
     * @property writes the table's count of payload writes when it was read.
     * @property keyColumns the key columns it is read under.
     */
    private class TableKeyIndex(
        val writes: Pair<Long, Long>,
        val keyColumns: List<String>,
        val holders: Map<List<String>, List<KeyHolder>>,
    )

    /**
     * A payload holding a row with some key.
     *
     * @property cacheKey the payload's cache key.
     * @property position the row's position in the payload.
     */
    private class KeyHolder(val cacheKey: String, val position: Int)

    /** Each table's [TableKeyIndex], as last read. */
    private val tableKeyIndexes = AtomicReference<Map<String, TableKeyIndex>>(emptyMap())

    /**
     * Whether [mutation] may bring a row another query holds into a query of [shape]: an UPSERT of
     * a row the query does not hold and does not exclude on the columns the upsert sets, or an
     * UPDATE setting a column the query's filter names (read without parsing the body) to a value
     * it does not exclude.
     *
     * @param filterColumns the columns [shape]'s filter names, as a JSON body writes them as keys.
     * @param ownKeys the keys of the rows the query holds, under [keyColumns].
     */
    private fun mayBringRowsIn(
        mutation: MutationRow,
        shape: QueryShape,
        filterColumns: List<String>,
        keyColumns: List<String>,
        ownKeys: () -> Set<List<String>>,
    ): Boolean =
        when (mutation.knownOperation) {
            // A row the query holds merges there; one it excludes on what the upsert sets stays
            // out, whatever row it merges with
            MutationOperation.UPSERT -> {
                val columns = conflictColumnsOf(mutation)
                upsertRowsOf(mutation, json).any { row ->
                    val key = keyOf(row, columns) ?: return@any false
                    shape.admission.admits(row) && (columns != keyColumns || key !in ownKeys())
                }
            }
            MutationOperation.UPDATE ->
                (shape.hasOtherFilters ||
                    filterColumns.any { column -> column in mutation.bodyJson }) &&
                    (patchOf(mutation)?.let(shape.admission::admits) != false)
            else -> false
        }

    /** An UPDATE's body; `null` when it is not a JSON object. */
    private fun patchOf(mutation: MutationRow): JsonObject? =
        try {
            json.parseToJsonElement(mutation.bodyJson) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * The admission of the query cached under [cacheKey]: [shape]'s, or the recorded one's, or
     * every row when neither is known, since unknown conditions admit: so for a payload an earlier
     * build cached, until it is read.
     */
    private suspend fun admissionOf(
        table: String,
        cacheKey: String,
        shape: QueryShape?,
    ): QueryAdmission =
        (shape ?: queryCache.queryShapes.shapeOf(table, cacheKey))?.admission
            ?: QueryAdmission.EVERY_ROW

    /**
     * The overlay a read runs for a query with [admission], de-duplicating by key the rows the
     * server may already hold.
     */
    private fun overlayFor(
        admission: QueryAdmission,
        unconfirmedSends: Set<Long>,
        bodyOf: (MutationRow) -> JsonElement = { mutation ->
            json.parseToJsonElement(mutation.bodyJson)
        },
    ): MutationOverlay =
        MutationOverlay(
            json = json,
            admission = admission,
            primaryKeys = primaryKeys,
            mayAlreadyHold = { mutation -> isSyncedOrUnconfirmed(mutation, unconfirmedSends) },
            onMutationFailure = ::logSkippedOverlay,
            bodyOf = bodyOf,
        )

    /**
     * The overlay a patch runs for a query with [admission]: the server holds every row the write
     * made, so a row a refresh already holds is not added twice, and an upsert row with no key to
     * place it by is left out, the query marked stale instead.
     *
     * @param batch the writes' bodies and filters, read once for every query.
     * @param keyed see [MutationOverlay]; only from [keyedUpdatesOf].
     */
    fun patchOverlayFor(
        admission: QueryAdmission,
        batch: PatchWrites? = null,
        keyed: KeyedUpdates? = null,
    ): MutationOverlay =
        if (batch == null) {
            MutationOverlay(
                json = json,
                admission = admission,
                primaryKeys = primaryKeys,
                mayAlreadyHold = { true },
                placesKeylessUpsertRows = false,
                onMutationFailure = ::logSkippedOverlay,
            )
        } else {
            MutationOverlay(
                json = json,
                admission = admission,
                primaryKeys = primaryKeys,
                mayAlreadyHold = { true },
                placesKeylessUpsertRows = false,
                onMutationFailure = ::logSkippedOverlay,
                bodyOf = batch::bodyOf,
                filterFor = batch::filterOf,
                keyed = keyed,
            )
        }

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

    /**
     * Logs a mutation the overlay skipped, once per mutation: the overlay is recomputed on every
     * emission, and a mutation that fails once fails every time. Never the body or filter values.
     */
    private fun logSkippedOverlay(mutation: MutationRow, failure: Exception) {
        if (!mutationQueue.claimOverlaySkipReport(mutation.id)) return
        engineLogger.warning {
            if (mutation.id < 0) {
                "Cache patch skipped an accepted ${mutation.operation} on ${mutation.table}: " +
                    "${failure::class.simpleName}"
            } else {
                "Overlay skipped pending ${mutation.operation} ${mutation.id} on ${mutation.table}: " +
                    "${failure::class.simpleName}"
            }
        }
    }

    /** The upsert-rule columns of [mutation]; see [conflictColumnsOf]. */
    private fun conflictColumnsOf(mutation: MutationRow): List<String> =
        conflictColumnsOf(mutation, primaryKeys)

    /**
     * Whether the server may already hold [mutation]'s rows — it replayed, or its send may have
     * reached the server — so a payload's row with the same key may be the mutation's own.
     */
    private fun isSyncedOrUnconfirmed(mutation: MutationRow, unconfirmedSends: Set<Long>): Boolean =
        mutation.syncedAtMs != null || mutation.id in unconfirmedSends

    /** Forgets every table's key index, after the cache was emptied. */
    fun forgetKeyIndexes() {
        tableKeyIndexes.store(emptyMap())
    }

    private companion object {
        /**
         * The most rows a table's key index reads, beyond which a read finds rows held elsewhere by
         * reading every payload instead.
         */
        const val MAX_KEY_INDEXED_ROWS = 100_000
    }
}
