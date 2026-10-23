// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.PayloadWriteCountingDao
import io.github.nesfeder.supabase.offline.internal.db.isStaleStamp
import io.github.nesfeder.supabase.offline.internal.db.knownOperation
import io.github.nesfeder.supabase.offline.internal.db.requireUserTable
import io.github.nesfeder.supabase.offline.internal.db.staleStampOf
import io.github.nesfeder.supabase.offline.internal.db.storedName
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.postgrest.RowFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.RowMatch
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Patches an online write into the cached queries of its table: the rows the server accepted or
 * returned are laid into every query that admits them, a live query with no payload gets one, and a
 * query the patch may leave wrong is marked stale and fetched again. Holds no lock of its own: it
 * writes under the table's write lock, every payload of one patch in one transaction under the
 * clear lock, and over the row it read, so a list eviction deleted meanwhile (eviction holds no
 * table lock) stays deleted.
 *
 * @param liveCacheKeysOf a table's queries with a live reader, which a patch may give a payload.
 * @param onQueriesMarkedStale told of the queries a write marked stale, so their live readers fetch
 *   again.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CachePatcher(
    private val queryCacheDao: PayloadWriteCountingDao,
    private val json: Json,
    private val nowMillis: () -> Long,
    private val freshness: CacheFreshness,
    private val ownership: CacheOwnership,
    private val queryCache: QueryCache,
    private val queryOverlay: QueryOverlay,
    private val primaryKeys: PrimaryKeys,
    private val liveCacheKeysOf: (table: String) -> Set<String>,
    private val onQueriesMarkedStale: (table: String, cacheKeys: Set<String>) -> Unit,
) {

    /** Ids for the writes a patch overlays, one per write, below any queued mutation's. */
    private val patchIds = AtomicLong(0L)

    /** A new id for a write a patch overlays, so a failure is logged once per write. */
    private fun nextPatchId(): Long = patchIds.decrementAndFetch()

    /**
     * Patches a write the server accepted, or a row it sent, into every cached query of [table]
     * that admits its rows. A live query with no payload gets one; one the patch may leave wrong is
     * marked stale. Runs under the table's lock after moving its watermark.
     *
     * @param bodyJson rows for INSERT and UPSERT, the changed columns for UPDATE, `"{}"` for
     *   DELETE.
     * @param filterJson an UPDATE's or DELETE's encoded [FilterDescriptor]; with none, it patches
     *   nothing.
     * @param onConflict an UPSERT's conflict columns; `null` is the table's primary key.
     * @param liveCacheKeys the table's live queries; by default those [liveCacheKeysOf] gives.
     * @param startedAtTick the tick the write started at; a clear since then voids it. `null` is
     *   now.
     */
    suspend fun patchCachedQueries(
        table: String,
        operation: MutationOperation,
        bodyJson: String,
        filterJson: String?,
        defaultToNull: Boolean? = null,
        onConflict: String? = null,
        ignoreDuplicates: Boolean? = null,
        liveCacheKeys: Set<String>? = null,
        startedAtTick: Long? = null,
    ) {
        requireUserTable(table)
        // A clear after this point voids the patch: each write below checks it under the clear
        // lock
        val guardTick = startedAtTick ?: freshness.tickNow()
        val write =
            MutationRow(
                id = nextPatchId(),
                table = table,
                operation = operation.storedName,
                bodyJson = bodyJson,
                filterJson = filterJson,
                createdAtMs = nowMillis(),
                syncedAtMs = null,
                defaultToNull = defaultToNull,
                onConflict = onConflict,
                ignoreDuplicates = ignoreDuplicates,
            )
        patchWithWrites(table, listOf(write), liveCacheKeys, guardTick)
    }

    /**
     * Patches the rows an UPDATE returned into the cached queries of [table], as
     * [patchCachedQueries] does: each returned row that carries the table's primary key sets, in
     * the row with that key, [body]'s columns and then its own. They are what the server wrote,
     * columns it set itself included. Rows without the key, or a response without rows, patch
     * nothing.
     *
     * @param responseData the UPDATE's response body: a JSON array of rows, one object, or empty.
     * @param startedAtTick [CacheFreshness.tickNow] as read when the write started.
     */
    suspend fun patchReturnedRows(
        table: String,
        body: JsonElement,
        responseData: String,
        startedAtTick: Long,
    ) {
        requireUserTable(table)
        val keyColumns = primaryKeys.of(table)
        val bodyColumns: Map<String, JsonElement> = body as? JsonObject ?: emptyMap()
        val writes =
            returnedRowsOf(json, responseData).mapNotNull { row ->
                val key = primaryKeyOf(row, keyColumns) ?: return@mapNotNull null
                val byKey =
                    FilterDescriptor(
                        conditions =
                            key.map { (column, value) ->
                                FilterDescriptor.Condition(
                                    column,
                                    operatorId = "eq",
                                    value = value.jsonPrimitive.content,
                                )
                            }
                    )
                MutationRow(
                    id = nextPatchId(),
                    table = table,
                    operation = MutationOperation.UPDATE.storedName,
                    bodyJson = JsonObject(bodyColumns + row).toString(),
                    filterJson = encodeFilter(byKey, json),
                    createdAtMs = nowMillis(),
                    syncedAtMs = null,
                )
            }
        if (writes.isNotEmpty())
            patchWithWrites(table, writes, liveCacheKeys = null, guardTick = startedAtTick)
    }

    /**
     * Patches the rows the server reported changed into the cached queries of [table], as
     * [patchCachedQueries] does for each: a saved row as an UPSERT on [keyColumns] that places it
     * by its key, a deleted one as a DELETE by its key; all of [changes], in order, in one pass
     * over the table's payloads under one hold of its lock. A burst of changes costs one patch, and
     * a row's last change is what the cache shows. The table's watermark moves as for any patch.
     *
     * @param keyColumns the table's primary key, which every change's filter names.
     */
    suspend fun patchServerChanges(
        table: String,
        keyColumns: List<String>,
        changes: List<ServerRowChange>,
        applies: suspend () -> Boolean = { true },
    ) {
        requireUserTable(table)
        if (changes.isEmpty()) return
        val guardTick = freshness.tickNow()
        val onConflict = keyColumns.joinToString(separator = ",")
        val writes = changes.map { change ->
            if (change.deleted) {
                MutationRow(
                    id = nextPatchId(),
                    table = table,
                    operation = MutationOperation.DELETE.storedName,
                    bodyJson = "{}",
                    filterJson = change.keyFilterJson,
                    createdAtMs = nowMillis(),
                    syncedAtMs = null,
                )
            } else {
                MutationRow(
                    id = nextPatchId(),
                    table = table,
                    operation = MutationOperation.UPSERT.storedName,
                    bodyJson = JsonArray(listOf(change.record)).toString(),
                    filterJson = change.keyFilterJson,
                    createdAtMs = nowMillis(),
                    syncedAtMs = null,
                    onConflict = onConflict,
                )
            }
        }
        patchWithWrites(table, writes, liveCacheKeys = null, guardTick, applies)
    }

    /**
     * The patch itself, over writes already built: [patchCachedQueries] builds one from a write,
     * [patchReturnedRows] one per returned row, [patchServerChanges] one per change the server
     * reported. Overlays [writes], in order, on every cached query of [table], creates the payloads
     * live queries without one now admit, and marks stale a query it may leave wrong
     * ([patchMayLeaveWrong]); every payload it changes or creates is written in one transaction. A
     * cache not the current identity's is left alone, and so is one a patch no longer [applies] to,
     * asked under the table's lock before anything is written: a server change from a channel left
     * for another user does not.
     */
    private suspend fun patchWithWrites(
        table: String,
        writes: List<MutationRow>,
        liveCacheKeys: Set<String>?,
        guardTick: Long,
        applies: suspend () -> Boolean = { true },
    ) {
        if (!ownership.cacheServesCurrentIdentity()) return
        val toFetchAgain = mutableSetOf<String>()
        queryCache.tableWriteLock(table).withLock {
            freshness.advanceLocalWriteWatermark(table)
            val cached = queryCacheDao.allForTable(table)
            val uncachedLive =
                (liveCacheKeys ?: liveCacheKeysOf(table)) -
                    cached.mapTo(mutableSetOf()) { row -> row.cacheKey }
            // Only a patch that may write claims an unowned cache for the current identity
            if (cached.isEmpty() && uncachedLive.isEmpty()) return@withLock
            if (!applies()) return@withLock
            if (!ownership.cacheTakesWritesOfCurrentIdentity()) return@withLock
            val parsed = cached.map { row -> row to queryCache.parsedPayloadOf(row) }
            val shapes = queryCache.queryShapes.shapesOf(table)
            val keyColumns = primaryKeys.of(table)
            val batch = PatchWrites(json)
            // UPDATEs naming one key each, over keys `eq` decides by content: each reaches exactly
            // the rows with its key, found by it
            val keyed = queryOverlay.keyedUpdatesOf(writes, keyColumns, parsed, batch)
            val mayMatter =
                (if (keyed != null) queryOverlay.keyedUpdatesReach(keyed)
                else rowsThatMayMatter(table, writes, primaryKeys, json, unknownMatters = true))
                    ?: { _, _ ->
                        false
                    }
            // Indexed only for a query that may draw on the rows other queries hold
            val known = lazy { KnownRows(parsed, shapes, keyColumns, mayMatter) }
            val patches = mutableListOf<PayloadPatch>()
            for ((row, payload) in parsed) {
                val rows = payload.rows ?: continue
                val shape = shapes[row.cacheKey]
                val target =
                    if (writes.any { write -> mayJoinKnownRows(write, shape, batch) }) {
                        known.value.targetFor(
                            row.cacheKey,
                            shape,
                            rows,
                            rowKeys = payload.keysFor(keyColumns),
                        )
                    } else {
                        PatchTarget(rows, shape, emptyList(), emptyList(), keyColumns.toSet())
                    }
                val overlay = queryOverlay.patchOverlayFor(target.admission, batch, keyed)
                val patched = overlay.overlayRows(rows, writes, knownRows = target.sameSelectRows)
                val wrong = patchMayLeaveWrong(writes, target, json, keyed, ::conflictColumnsOf)
                if (patched.visible == rows && (!wrong || isStaleStamp(row.updatedAt))) continue
                // The payload stays as old as what the server last said: a local write is not fresh
                // data, and one it may leave wrong is stale
                val stamp = if (wrong) staleStampOf(row.updatedAt) else row.updatedAt
                val text = overlay.encode(patched.visible)
                patches +=
                    PayloadPatch(
                        row = row.copy(payload = text, updatedAt = stamp),
                        replacedLength = row.payload.length,
                        // What the next patch reads, kept as parsed here so it parses nothing
                        parsed = ParsedPayload(text, patched.visible),
                        wrong = wrong,
                    )
            }
            val created = livePayloadsFor(table, uncachedLive, shapes, writes, known, batch, keyed)
            val changed = writePatches(guardTick, patches, created.payloads) ?: return@withLock
            // Once the patch landed: a clear that voided it has its live readers fetch anyway
            toFetchAgain += created.toFetchAgain
            for ((patch, landed) in patches.zip(changed)) {
                // A row not changed was evicted since the snapshot: a patch never brings a deleted
                // list back, and its reader has nothing to fetch again for
                if (landed && patch.wrong) toFetchAgain += patch.row.cacheKey
            }
        }
        if (toFetchAgain.isNotEmpty()) onQueriesMarkedStale(table, toFetchAgain)
    }

    /**
     * Whether a patch of [write] reads rows other queries hold for a query of [shape]: an UPSERT
     * matches them by key, and an UPDATE may bring one in only when it sets a column the query's
     * filter reads. INSERT and DELETE never read them.
     */
    private fun mayJoinKnownRows(
        write: MutationRow,
        shape: QueryShape?,
        batch: PatchWrites,
    ): Boolean =
        when (write.knownOperation) {
            MutationOperation.UPSERT -> true
            MutationOperation.UPDATE ->
                batch.patchOf(write)?.let { patch -> mayGainRowsBy(patch, shape) } ?: true
            else -> false
        }

    /** The upsert-rule columns of [mutation]; see [conflictColumnsOf]. */
    private fun conflictColumnsOf(mutation: MutationRow): List<String> =
        conflictColumnsOf(mutation, primaryKeys)

    /**
     * A payload for each of [cacheKeys], queries a live reader observes that have none, of the rows
     * [writes] wrote that the query admits; see [LivePayloads]. None for a query that would stay
     * empty. A query the rows known here may not show right gets no payload; it is to be fetched
     * again.
     *
     * @param shapes the table's known query shapes, by cache key.
     * @param known the rows the table's other queries hold, indexed on first use.
     * @param batch the writes' bodies and filters, read once.
     * @param keyed see [patchMayLeaveWrong].
     */
    private fun livePayloadsFor(
        table: String,
        cacheKeys: Set<String>,
        shapes: Map<String, QueryShape?>,
        writes: List<MutationRow>,
        known: Lazy<KnownRows>,
        batch: PatchWrites,
        keyed: KeyedUpdates?,
    ): LivePayloads {
        val payloads = mutableListOf<CreatedPayload>()
        val toFetchAgain = mutableSetOf<String>()
        for (cacheKey in cacheKeys) {
            // A live query's shape is known while it is read; unknown conditions admit
            val shape = shapes[cacheKey]
            val target = known.value.targetFor(cacheKey, shape, rows = emptyList())
            if (patchMayLeaveWrong(writes, target, json, keyed, ::conflictColumnsOf)) {
                toFetchAgain += cacheKey
                continue
            }
            val admission = target.admission
            val overlay = queryOverlay.patchOverlayFor(admission, batch, keyed)
            val created =
                overlay
                    .overlayRows(emptyList(), writes, knownRows = target.sameSelectRows)
                    .written
                    .filter(admission::admits)
            if (created.isEmpty()) continue
            // Local rows alone are not the query's answer: stale, so a read asks the server when it
            // can. A created payload is not a network response: it holds no synced mutation
            val row =
                CachedQueryRow(
                    table = table,
                    cacheKey = cacheKey,
                    payload = overlay.encode(created),
                    updatedAt = staleStampOf(nowMillis()),
                )
            payloads += CreatedPayload(row, shape)
        }
        return LivePayloads(payloads, toFetchAgain)
    }

    /**
     * Marks every cached query of [table] stale, and has every live one fetch again: a write the
     * cache cannot follow reached the table.
     *
     * @param startedAtTick see [markQueriesAWriteMayReach].
     */
    suspend fun markEveryQueryStale(table: String, startedAtTick: Long) {
        // An UPDATE with no readable part reaches every query, and may add rows to a live one
        markQueriesAWriteMayReach(
            table,
            MutationOperation.UPDATE,
            "{}",
            narrowing = null,
            startedAtTick,
        )
    }

    /**
     * Marks stale the cached queries of [table] an UPDATE or DELETE the cache cannot patch may have
     * reached, and has their live readers fetch again: a query holding a row [narrowing] does not
     * exclude, and, for an UPDATE, one whose filter may admit a row the write changes. With no
     * [narrowing], every query of the table. The payloads stay, so an offline read still has them;
     * their rows are read as [QueryCache.parsedPayloadOf] keeps them, and the stale stamps are
     * written in one transaction.
     *
     * @param narrowing conditions the write's filter includes, all of which a row it reaches meets.
     * @param startedAtTick [CacheFreshness.tickNow] as read when the write started; nothing is
     *   written if the cache was cleared or dropped since, or is not the current identity's.
     */
    suspend fun markQueriesAWriteMayReach(
        table: String,
        operation: MutationOperation,
        bodyJson: String,
        narrowing: FilterDescriptor?,
        startedAtTick: Long,
    ) {
        requireUserTable(table)
        if (!ownership.cacheTakesWritesOfCurrentIdentity()) return
        val patch =
            (if (operation == MutationOperation.UPDATE) json.parseToJsonElement(bodyJson) else null)
                as? JsonObject
        val toFetchAgain = mutableSetOf<String>()
        queryCache.tableWriteLock(table).withLock {
            freshness.advanceLocalWriteWatermark(table)
            val shapes = queryCache.queryShapes.shapesOf(table)
            val cached = queryCacheDao.allForTable(table)
            val toMark = mutableListOf<PayloadPatch>()
            val narrowed = narrowing?.let { filter -> RowFilter(filter) }
            for (row in cached) {
                val reached =
                    narrowed == null ||
                        queryCache.parsedPayloadOf(row).rows.orEmpty().any { element ->
                            element !is JsonObject || narrowed.match(element) != RowMatch.EXCLUDED
                        } ||
                        (patch != null && mayGainRowsBy(patch, shapes[row.cacheKey]))
                if (!reached) continue
                toFetchAgain += row.cacheKey
                if (isStaleStamp(row.updatedAt)) continue
                toMark +=
                    PayloadPatch(
                        row = row.copy(updatedAt = staleStampOf(row.updatedAt)),
                        replacedLength = row.payload.length,
                        // The text is as it was, so what is kept parsed for it stays right
                        parsed = null,
                        wrong = true,
                    )
            }
            if (writePatches(startedAtTick, toMark) == null) return@withLock
            // A live query with nothing cached may gain the rows an UPDATE changes
            if (patch != null) {
                toFetchAgain +=
                    liveCacheKeysOf(table) - cached.mapTo(mutableSetOf()) { row -> row.cacheKey }
            }
        }
        if (toFetchAgain.isNotEmpty()) onQueriesMarkedStale(table, toFetchAgain)
    }

    /**
     * A payload a patch writes over the row it read.
     *
     * @property row the row as it is written: the payload and stamp over the key it was read under.
     * @property replacedLength the length of the payload the row held when it was read.
     * @property parsed the written payload as its rows, to keep parsed once it lands; `null` when
     *   the text is as it was.
     * @property wrong whether the patch may leave the query wrong, so its live reader fetches again
     *   once the stale stamp lands.
     */
    private class PayloadPatch(
        val row: CachedQueryRow,
        val replacedLength: Int,
        val parsed: ParsedPayload?,
        val wrong: Boolean,
    )

    /** A payload a patch writes for a live query that had none, with the shape recorded for it. */
    private class CreatedPayload(val row: CachedQueryRow, val shape: QueryShape?)

    /**
     * What [livePayloadsFor] makes of the live queries without a payload.
     *
     * @property payloads the payloads to write.
     * @property toFetchAgain the queries that get none, because the rows known may not show them
     *   right.
     */
    private class LivePayloads(val payloads: List<CreatedPayload>, val toFetchAgain: Set<String>)

    /**
     * Writes [patches] over the rows cached for their queries, and [created] as new rows, in one
     * transaction under the clear lock: all of them land, or none, and none once the cache was
     * cleared or dropped since [guardTick]. A patch whose row is gone changes nothing: eviction,
     * which holds no table lock, took the list between the patch's read and its write, and a patch
     * never brings a deleted list back. Each created payload's shape record is written after the
     * transaction commits, under the same hold of the clear lock, so no clear parts the two:
     * [QueryShapeRecords.remember] writes a record only beside a payload, and takes its own lock,
     * which no transaction may wait on.
     *
     * @return whether each of [patches] changed its row, in their order, or `null` when a clear
     *   voided the write.
     */
    private suspend fun writePatches(
        guardTick: Long,
        patches: List<PayloadPatch>,
        created: List<CreatedPayload> = emptyList(),
    ): List<Boolean>? {
        if (patches.isEmpty() && created.isEmpty()) return emptyList()
        val changed = BooleanArray(patches.size)
        val written =
            freshness.writeUnlessCleared(guardTick) {
                queryCacheDao.runInTransaction {
                    patches.forEachIndexed { index, patch ->
                        val row = patch.row
                        changed[index] =
                            queryCacheDao.updatePayload(
                                row.table,
                                row.cacheKey,
                                row.payload,
                                row.updatedAt,
                            ) > 0
                    }
                    created.forEach { payload -> queryCacheDao.upsert(payload.row) }
                }
                created.forEach { payload ->
                    val shape = payload.shape ?: return@forEach
                    val row = payload.row
                    queryCache.queryShapes.remember(row.table, row.cacheKey, shape, persist = true)
                }
            }
        if (!written) return null
        var bytes = created.sumOf { payload -> payload.row.payload.length.toLong() }
        for ((patch, landed) in patches.zip(changed.asList())) {
            if (!landed) continue
            val row = patch.row
            bytes += row.payload.length - patch.replacedLength
            patch.parsed?.let { parsed -> queryCache.keepParsed(row.table, row.cacheKey, parsed) }
        }
        queryCache.payloadBytesWritten(bytes)
        return changed.asList()
    }
}

/**
 * A row the server reported changed, located by its primary key, for
 * [CachePatcher.patchServerChanges].
 *
 * @property record the row as the server sent it: the whole row for one saved, at least its key for
 *   one deleted.
 * @property keyFilterJson the encoded [FilterDescriptor] that finds the row by its key.
 * @property deleted whether the row was deleted; otherwise it was inserted or updated.
 */
internal class ServerRowChange(
    val record: JsonObject,
    val keyFilterJson: String,
    val deleted: Boolean,
)
