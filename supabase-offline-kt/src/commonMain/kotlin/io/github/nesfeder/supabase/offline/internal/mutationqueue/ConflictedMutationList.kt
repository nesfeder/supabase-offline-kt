// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.db.CONFLICTED_MUTATIONS_CACHE_KEY
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Version written into the conflicted list's reserved row, so a later format can tell this one. */
private const val CONFLICTED_MUTATION_LIST_FORMAT_VERSION: Int = 1

/**
 * One conflicted mutation as persisted: what its mutation row held, its bases included, so it can
 * be re-queued exactly, and what the server's row was when the replay found it moved.
 *
 * @property id The id it had in the queue.
 * @property table [MutationRow.table].
 * @property operation [MutationRow.operation], in its stored form.
 * @property bodyJson [MutationRow.bodyJson], or empty when [bodyOmittedChars] is set.
 * @property filterJson [MutationRow.filterJson].
 * @property createdAtMs [MutationRow.createdAtMs].
 * @property defaultToNull [MutationRow.defaultToNull].
 * @property onConflict [MutationRow.onConflict].
 * @property ignoreDuplicates [MutationRow.ignoreDuplicates].
 * @property owner The identity that made the write, or `null` when none was recorded.
 * @property baseVersion [MutationRow.baseVersion].
 * @property baseJson [MutationRow.baseJson].
 * @property serverRowJson The server's row as the replay read it, as JSON; `null` when it was gone
 *   or too long to keep.
 * @property serverVersion The server row's version; `null` when the row was gone.
 * @property changedOnServer The write's columns whose server value differs from the base.
 * @property conflictingColumns Of those, the ones the write also sets to another value.
 * @property message Why it was parked.
 * @property conflictedAtMs When it was parked, by the device clock.
 * @property bodyOmittedChars The body's length when it was too long to keep; such an entry cannot
 *   be re-queued.
 */
@Serializable
internal data class StoredConflict(
    override val id: Long,
    override val table: String,
    override val operation: String,
    val bodyJson: String,
    val filterJson: String? = null,
    val createdAtMs: Long,
    val defaultToNull: Boolean? = null,
    val onConflict: String? = null,
    val ignoreDuplicates: Boolean? = null,
    val owner: String? = null,
    val baseVersion: String? = null,
    val baseJson: String? = null,
    val serverRowJson: String? = null,
    val serverVersion: String? = null,
    val changedOnServer: List<String> = emptyList(),
    val conflictingColumns: List<String> = emptyList(),
    val message: String,
    val conflictedAtMs: Long,
    val bodyOmittedChars: Int? = null,
) : ReservedListEntry

/**
 * The persisted conflicted-mutation list, oldest first.
 *
 * @property droppedCount How many entries left the list to stay within its bounds.
 */
@Serializable
internal data class StoredConflictList(
    @EncodeDefault val version: Int = CONFLICTED_MUTATION_LIST_FORMAT_VERSION,
    override val entries: List<StoredConflict> = emptyList(),
    override val droppedCount: Long = 0,
) : StoredReservedList<StoredConflict>

/** The conflicted list as held in memory, each entry's size beside it. */
internal typealias MeasuredConflictList = MeasuredList<StoredConflict, StoredConflictList>

/**
 * The mutation queue's conflicted list, kept in a reserved `object_cache` row within the rejected
 * list's bounds, as [BoundedReservedList] keeps it. Callers hold the queue lock and write the row
 * this builds in their own transaction.
 */
internal class ConflictedMutationList(
    queryCacheDao: QueryCacheDao,
    json: Json,
    nowMillis: () -> Long,
    logger: EngineLogger,
    entrySerializer: KSerializer<StoredConflict> = StoredConflict.serializer(),
) :
    BoundedReservedList<StoredConflict, StoredConflictList>(
        queryCacheDao = queryCacheDao,
        json = json,
        nowMillis = nowMillis,
        logger = logger,
        cacheKey = CONFLICTED_MUTATIONS_CACHE_KEY,
        listSerializer = StoredConflictList.serializer(),
        entrySerializer = entrySerializer,
        kind = "conflicted",
    ) {

    /** Every conflicted mutation, oldest first; an unreadable row is kept aside and reads empty. */
    suspend fun conflicts(): List<StoredConflict> = entries()

    /** [conflicts], re-emitted whenever the list changes. */
    fun conflictsFlow(): Flow<List<StoredConflict>> = entriesFlow()

    /** Whether the list holds an entry with [id]; answered from memory once loaded. */
    suspend fun hasConflict(id: Long): Boolean = hasEntry(id)

    /**
     * Removes the entry with [id], if present, and writes the list. Caller holds the queue lock.
     *
     * @return The removed entry, or `null` when there is none.
     */
    suspend fun removeConflict(id: Long): StoredConflict? = removeEntry(id)

    /**
     * The list's row with [add] added as the newest entry and the entry with [removeId] removed,
     * bounded as the rejected list is; see [BoundedReservedList.rowWith]. Write it in the caller's
     * transaction, then [conflictWritten].
     *
     * @return The row and the new list, measured.
     */
    suspend fun conflictedRowWith(
        add: StoredConflict? = null,
        removeId: Long? = null,
    ): Pair<CachedQueryRow, MeasuredConflictList> = rowWith(add, removeId)

    /** Records that the row holding [list] was committed. */
    fun conflictWritten(list: MeasuredConflictList) {
        written(list)
    }

    override fun listOf(entries: List<StoredConflict>, droppedCount: Long): StoredConflictList =
        StoredConflictList(entries = entries, droppedCount = droppedCount)

    override fun withDroppedCount(
        list: StoredConflictList,
        droppedCount: Long,
    ): StoredConflictList = list.copy(droppedCount = droppedCount)

    /** [entry] within the per-entry bounds: texts trimmed, an oversized body or row dropped. */
    override fun bounded(entry: StoredConflict): StoredConflict {
        var bounded = entry.copy(message = entry.message.take(MAX_REJECTED_TEXT_CHARS))
        if ((entry.serverRowJson?.length ?: 0) > MAX_REJECTED_BODY_CHARS)
            bounded = bounded.copy(serverRowJson = null)
        if ((entry.baseJson?.length ?: 0) > MAX_REJECTED_BODY_CHARS)
            bounded = bounded.copy(baseJson = null)
        if (entry.bodyJson.length > MAX_REJECTED_BODY_CHARS) {
            logger.warning {
                "Conflicted ${entry.operation} ${entry.id} on ${entry.table}: its body of " +
                    "${entry.bodyJson.length} characters is too long to keep, so it cannot be re-queued"
            }
            bounded = bounded.copy(bodyJson = "", bodyOmittedChars = entry.bodyJson.length)
        }
        return bounded
    }
}
