// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.PayloadWriteCountingDao
import io.github.nesfeder.supabase.offline.internal.db.isReservedTable
import io.github.nesfeder.supabase.offline.internal.db.knownOperation
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
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

/**
 * Reads served with the mutations a cached payload does not hold laid over it: the current
 * identity's pending ones, and replayed ones newer than the payload. Rows other queries hold, read
 * with the same projection, stand in for rows a mutation brings in, found through each table's key
 * index ([TableKeyIndexes]). A live reader keeps its overlay in a [LiveOverlay]; a patch builds its
 * overlays with [PatchOverlays]. Owns the [TableSnapshotLocks].
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

    /** Each table's cached rows by key, through which rows other queries hold are found. */
    private val keyIndexes = TableKeyIndexes(queryCacheDao, queryCache)

    /**
     * Runs [change], which changes [table]'s cached payloads and its queued mutations, as one step
     * for the table's readers: each reads the table before it or after it, never in between.
     */
    suspend fun <T> holdingReadersOf(table: String, change: suspend () -> T): T =
        // Lock order: send lock, snapshot lock, table write lock, queue lock, clear lock
        snapshotLocks.of(table).withLock { change() }

    /**
     * [table]'s queued mutations and the payload cached for [cacheKey], read as one step: no change
     * made under [holdingReadersOf] lands between them. The mutations first, so a payload write
     * that retires a replayed mutation after it is never read with the mutation already gone.
     */
    private suspend fun readTogether(table: String, cacheKey: String): TableSnapshot =
        // Lock order: send lock, snapshot lock, table write lock, queue lock, clear lock. Under it,
        // only the queue's per-table read lock, a leaf
        snapshotLocks.of(table).withLock {
            val unconfirmedSends = mutationQueue.unconfirmedSends()
            val mutations = mutationQueue.tableMutationsNow(table)
            TableSnapshot(unconfirmedSends, mutations, queryCacheDao.get(table, cacheKey))
        }

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
     * a row it shows, and the keys of the rows those changed. Both are read from the pass that
     * built the payload, so they describe the rows it holds.
     */
    private fun OverlaidPayload.served(mutations: List<MutationRow>): ServedOverlay {
        if (shownChangedBy.isEmpty()) return ServedOverlay(payload, hasPendingMutations = false)
        val pendingIds =
            mutations.mapNotNullTo(HashSet()) { mutation ->
                mutation.id.takeIf { mutation.syncedAtMs == null }
            }
        val keyColumns = primaryKeys.of(mutations.first().table)
        var hasPending = false
        val keys = LinkedHashSet<JsonObject>()
        shownChangedBy.forEach { (id, rows) ->
            if (id !in pendingIds) return@forEach
            hasPending = true
            rows.forEach { row -> primaryKeyOf(row, keyColumns)?.let(keys::add) }
        }
        return ServedOverlay(payload, hasPending, keys)
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
            val reader =
                LiveOverlay(
                    freshness = freshness,
                    ownership = ownership,
                    mutationQueue = mutationQueue,
                    identityProvider = identityProvider,
                    readTogether = { readTogether(table, cacheKey) },
                    overlaid = { inputs, bodyOf, readTick ->
                        overlaidOrNull(table, cacheKey, shape, inputs, bodyOf, readTick)
                    },
                )
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
            val rows =
                keyIndexes.borrowedByKey(table, cacheKey, projection, shapes, keys, keyColumns)
            if (rows != null) return rows
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
        return keyIndexes.newestRowHolding(table, key, column, primaryKeys.of(table))
    }

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
     * Logs a mutation the overlay skipped, once per mutation: the overlay is recomputed on every
     * emission, and a mutation that fails once fails every time. Never the body or filter values. A
     * patch's overlays ([PatchOverlays]) report through it too.
     */
    fun logSkippedOverlay(mutation: MutationRow, failure: Exception) {
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
        keyIndexes.forget()
    }
}

/**
 * What [QueryOverlay] reads of a table in one step, under the table's snapshot lock.
 *
 * @property unconfirmedSends the mutations whose send may have reached the server.
 * @property mutations the table's mutation rows.
 * @property row the payload cached for the query, or `null` for none.
 */
internal class TableSnapshot(
    val unconfirmedSends: Set<Long>,
    val mutations: MutationQueue.TableMutations,
    val row: CachedQueryRow?,
)
