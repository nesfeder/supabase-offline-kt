// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.REJECTED_MUTATIONS_CACHE_KEY
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How many rejected mutations the list keeps; the oldest go first. */
internal const val MAX_REJECTED_MUTATIONS: Int = 100

/**
 * The longest body a rejected entry keeps, in characters. A longer body is not stored — its length
 * is — so a few large refused writes cannot make every later rejection read and rewrite megabytes.
 */
internal const val MAX_REJECTED_BODY_CHARS: Int = 16 * 1024

/** The longest server message, details or hint a rejected entry keeps, in characters. */
internal const val MAX_REJECTED_TEXT_CHARS: Int = 4 * 1024

/** The most characters the whole rejected list may take; the oldest entries go to stay under it. */
internal const val MAX_REJECTED_LIST_CHARS: Int = 512 * 1024

/** Version written into every reserved row, so a later format can recognize this one. */
private const val REJECTED_MUTATION_LIST_FORMAT_VERSION: Int = 1

/**
 * What the server said when it refused a write, as persisted.
 *
 * @property status The HTTP status.
 * @property code The SQLSTATE or PostgREST code.
 * @property message The server's message.
 * @property details The server's details.
 * @property hint The server's hint.
 */
@Serializable
internal data class StoredServerError(
    val status: Int,
    val code: String? = null,
    val message: String,
    val details: String? = null,
    val hint: String? = null,
)

/**
 * One rejected mutation as persisted: everything its mutation row held, so it can be re-queued
 * exactly, plus why it was refused.
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
 * @property baseVersion [MutationRow.baseVersion]; `null` when the write was not guarded, or an
 *   earlier build wrote the entry.
 * @property baseJson [MutationRow.baseJson]; `null` when there is none, or it was too long to keep.
 * @property rejectedAtMs When it was refused, by the device clock.
 * @property message A human-readable reason.
 * @property serverError The server's refusal, or `null` when the engine refused it locally.
 * @property bodyOmittedChars The body's length when it was too long to keep
 *   ([MAX_REJECTED_BODY_CHARS]); such an entry cannot be retried.
 */
@Serializable
internal data class StoredRejection(
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
    val rejectedAtMs: Long,
    val message: String,
    val serverError: StoredServerError? = null,
    val bodyOmittedChars: Int? = null,
) : ReservedListEntry {
    /**
     * The row this rejection re-queues as, at the tail: its stored fields, its base included, id
     * `0` for the queue to assign, a new creation time and [owner]. With its base, a guarded write
     * replays as guarded as it was first sent.
     *
     * @param createdAtMs When it is re-queued.
     * @param owner The identity it is re-queued for, or `null` to record none.
     * @return A pending row, or `null` when the body was not kept.
     */
    fun toRequeuedRow(createdAtMs: Long, owner: String?): MutationRow? {
        if (bodyOmittedChars != null) return null
        return MutationRow(
            table = table,
            operation = operation,
            bodyJson = bodyJson,
            filterJson = filterJson,
            createdAtMs = createdAtMs,
            syncedAtMs = null,
            defaultToNull = defaultToNull,
            onConflict = onConflict,
            ignoreDuplicates = ignoreDuplicates,
            owner = owner,
            baseVersion = baseVersion,
            baseJson = baseJson,
        )
    }
}

/**
 * The persisted rejected-mutation list, oldest first.
 *
 * @property droppedCount How many entries left the list to stay within its bounds since the count
 *   was last reset.
 */
@Serializable
internal data class StoredRejectionList(
    @EncodeDefault val version: Int = REJECTED_MUTATION_LIST_FORMAT_VERSION,
    override val entries: List<StoredRejection> = emptyList(),
    override val droppedCount: Long = 0,
) : StoredReservedList<StoredRejection>

/** The rejected list as held in memory, each entry's size beside it. */
internal typealias MeasuredRejectionList = MeasuredList<StoredRejection, StoredRejectionList>

/**
 * The mutation queue's rejected list, kept in a reserved `object_cache` row (see
 * [RESERVED_TABLE_NAME]) as [BoundedReservedList] keeps it. A rejected entry keeps the server's
 * texts and its body and base row within their own bounds.
 */
internal class RejectedMutationList(
    queryCacheDao: QueryCacheDao,
    json: Json,
    nowMillis: () -> Long,
    logger: EngineLogger,
    entrySerializer: KSerializer<StoredRejection> = StoredRejection.serializer(),
) :
    BoundedReservedList<StoredRejection, StoredRejectionList>(
        queryCacheDao = queryCacheDao,
        json = json,
        nowMillis = nowMillis,
        logger = logger,
        cacheKey = REJECTED_MUTATIONS_CACHE_KEY,
        listSerializer = StoredRejectionList.serializer(),
        entrySerializer = entrySerializer,
        kind = "rejected",
    ) {

    /**
     * Every rejected mutation, oldest first.
     *
     * @return The list; empty when nothing was ever rejected. An unreadable persisted list is moved
     *   aside and reads as a fresh, empty list.
     */
    suspend fun rejections(): List<StoredRejection> = entries()

    /**
     * [rejections], re-emitted whenever the list changes.
     *
     * @return A flow of the list, oldest first.
     */
    fun rejectionsFlow(): Flow<List<StoredRejection>> = entriesFlow()

    /**
     * Removes the rejection with [id], if present, and writes the list.
     *
     * @param id The rejected mutation's id.
     * @return The removed entry, or `null` when the list has no entry with [id].
     */
    suspend fun removeRejection(id: Long): StoredRejection? = removeEntry(id)

    /**
     * Whether the list holds an entry with [id]. Answered from memory once the list is loaded.
     *
     * @param id A mutation id.
     * @return `true` when it is on the list.
     */
    suspend fun hasRejection(id: Long): Boolean = hasEntry(id)

    /**
     * The rejected-list row with [add] added and the entries with [removeId] and [removeIds]
     * removed, bounded; see [BoundedReservedList.rowWith]. Write the row in the caller's
     * transaction, then commit it with [rejectedWritten]. Caller holds the queue lock.
     *
     * @return The row and the new list, measured.
     */
    suspend fun rejectedRowWith(
        add: StoredRejection? = null,
        removeId: Long? = null,
        removeIds: Set<Long> = emptySet(),
    ): Pair<CachedQueryRow, MeasuredRejectionList> = rowWith(add, removeId, removeIds)

    /**
     * Records that [list] was written.
     *
     * @param list The list the committed row holds, as [rejectedRowWith] measured it.
     */
    fun rejectedWritten(list: MeasuredRejectionList) {
        written(list)
    }

    override fun listOf(entries: List<StoredRejection>, droppedCount: Long): StoredRejectionList =
        StoredRejectionList(entries = entries, droppedCount = droppedCount)

    override fun withDroppedCount(
        list: StoredRejectionList,
        droppedCount: Long,
    ): StoredRejectionList = list.copy(droppedCount = droppedCount)

    /**
     * [entry] within the per-entry bounds: the message and the server's texts trimmed, and a base
     * row or a body too long to keep dropped, each with a warning.
     */
    override fun bounded(entry: StoredRejection): StoredRejection {
        var bounded =
            entry.copy(
                message = entry.message.take(MAX_REJECTED_TEXT_CHARS),
                serverError =
                    entry.serverError?.let { error ->
                        error.copy(
                            message = error.message.take(MAX_REJECTED_TEXT_CHARS),
                            details = error.details?.take(MAX_REJECTED_TEXT_CHARS),
                            hint = error.hint?.take(MAX_REJECTED_TEXT_CHARS),
                        )
                    },
            )
        // Without its base row the retry still carries its base version, so it is still guarded,
        // though every column it sets then reads as moved when the row has
        if ((entry.baseJson?.length ?: 0) > MAX_REJECTED_BODY_CHARS) {
            logger.warning {
                "Rejected ${entry.operation} ${entry.id} on ${entry.table}: its base row of " +
                    "${entry.baseJson?.length} characters is too long to keep; a retry is guarded " +
                    "by its version alone, so any move of the row is a conflict for the resolver"
            }
            bounded = bounded.copy(baseJson = null)
        }
        if (entry.bodyJson.length > MAX_REJECTED_BODY_CHARS) {
            logger.warning {
                "Rejected ${entry.operation} ${entry.id} on ${entry.table}: its body of " +
                    "${entry.bodyJson.length} characters is too long to keep, so it cannot be retried"
            }
            bounded = bounded.copy(bodyJson = "", bodyOmittedChars = entry.bodyJson.length)
        }
        return bounded
    }
}
