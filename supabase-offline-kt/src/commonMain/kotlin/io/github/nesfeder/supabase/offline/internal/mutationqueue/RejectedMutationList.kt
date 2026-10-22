// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.cache.LocalData
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.REJECTED_MUTATIONS_CACHE_KEY
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
    val id: Long,
    val table: String,
    val operation: String,
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
) {
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
    val entries: List<StoredRejection> = emptyList(),
    val droppedCount: Long = 0,
)

/**
 * The mutation queue's rejected list, kept in a reserved `object_cache` row (see
 * [RESERVED_TABLE_NAME]): one bounded value read and written whole, rarely. It is cached in memory
 * after the first read.
 *
 * Callers hold the queue lock ([MutationQueue.holdingQueueLock]), and build the row here before
 * opening a transaction, so no transaction waits on a coroutine lock. An unreadable row is kept
 * aside, logged, and started afresh.
 */
internal class RejectedMutationList(
    private val queryCacheDao: QueryCacheDao,
    private val json: Json,
    private val nowMillis: () -> Long,
    private val logger: EngineLogger,
) {

    private val rejectedCache = MutableStateFlow<StoredRejectionList?>(null)

    /* ─────────────────────────── Rejected list ─────────────────────────── */

    /**
     * Every rejected mutation, oldest first.
     *
     * @return The list; empty when nothing was ever rejected. An unreadable persisted list is moved
     *   aside and reads as a fresh, empty list.
     */
    suspend fun rejections(): List<StoredRejection> = rejectedList().entries

    /**
     * [rejections], re-emitted whenever the list changes.
     *
     * @return A flow of the list, oldest first.
     */
    fun rejectionsFlow(): Flow<List<StoredRejection>> = flow {
        rejectedList()
        emitAll(rejectedCache.filterNotNull().map { list -> list.entries })
    }

    /**
     * How many entries left the list to stay within its bounds since [resetDroppedCount].
     *
     * @return The count.
     */
    suspend fun droppedCount(): Long = rejectedList().droppedCount

    /** Resets [droppedCount] to zero. Caller holds the queue lock. */
    suspend fun resetDroppedCount() {
        val current = rejectedList()
        writeRejected(current.copy(droppedCount = 0))
    }

    /**
     * Removes the rejection with [id], if present, and writes the list.
     *
     * @param id The rejected mutation's id.
     * @return The removed entry, or `null` when the list has no entry with [id].
     */
    suspend fun removeRejection(id: Long): StoredRejection? {
        val removed = rejectedList().entries.firstOrNull { entry -> entry.id == id } ?: return null
        val (row, list) = rejectedRowWith(removeId = id)
        queryCacheDao.upsert(row)
        rejectedWritten(list)
        return removed
    }

    /**
     * Whether the list holds an entry with [id]. Answered from memory once the list is loaded.
     *
     * @param id A mutation id.
     * @return `true` when it is on the list.
     */
    suspend fun hasRejection(id: Long): Boolean =
        rejectedList().entries.any { entry -> entry.id == id }

    /**
     * The rejected-list row with [add] added as the newest entry (replacing an earlier entry with
     * the same id) and the entry with [removeId] removed, bounded to [MAX_REJECTED_MUTATIONS]
     * entries and [MAX_REJECTED_LIST_CHARS] characters. Oversized bodies and server texts are
     * trimmed first; each entry that has to go is logged and counted. Write the row in the caller's
     * transaction, then commit it with [rejectedWritten]. Caller holds the store's mutation-queue
     * lock.
     *
     * @param add An entry to add, or `null`.
     * @param removeId An entry to remove, or `null`.
     * @return The row and the new list.
     */
    suspend fun rejectedRowWith(
        add: StoredRejection? = null,
        removeId: Long? = null,
        removeIds: Set<Long> = emptySet(),
    ): Pair<CachedQueryRow, StoredRejectionList> {
        val current = rejectedList()
        var entries =
            current.entries.filterNot { entry ->
                entry.id == removeId || entry.id in removeIds || entry.id == add?.id
            }
        if (add != null) entries = entries + bounded(add)
        // Each entry is measured once; the oldest leave while the list is over either bound
        val sizes = entries.map(::charactersOf)
        var total = sizes.sum()
        var first = 0
        var droppedCount = current.droppedCount
        while (entries.size - first > MAX_REJECTED_MUTATIONS || total > MAX_REJECTED_LIST_CHARS) {
            val dropped = entries[first]
            logger.warning {
                "Rejected list full: dropped rejected ${dropped.operation} ${dropped.id} on ${dropped.table}"
            }
            total -= sizes[first]
            first++
            droppedCount++
        }
        val list = StoredRejectionList(entries = entries.drop(first), droppedCount = droppedCount)
        return rejectedRow(list) to list
    }

    /**
     * Records that [list] was written.
     *
     * @param list The list the committed row holds.
     */
    fun rejectedWritten(list: StoredRejectionList) {
        rejectedCache.value = list
    }

    /**
     * Records that every row is gone and the queue empty; called once [LocalData.clearLocalData]
     * has deleted them. The records then read empty without a row, so no reader writes one back.
     */
    fun resetCaches() {
        rejectedCache.value = StoredRejectionList()
    }

    private suspend fun rejectedList(): StoredRejectionList {
        rejectedCache.value?.let {
            return it
        }
        val payload = queryCacheDao.get(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY)?.payload
        val loaded =
            if (payload == null) {
                StoredRejectionList()
            } else {
                decodeOrNull(StoredRejectionList.serializer(), payload)
                    ?: moveAsideUnreadable(payload)
            }
        rejectedCache.compareAndSet(null, loaded)
        return rejectedCache.value ?: loaded
    }

    /** Keeps an unreadable rejected-list payload aside and starts a new list. */
    private suspend fun moveAsideUnreadable(payload: String): StoredRejectionList {
        val fresh = StoredRejectionList()
        keepAsideUnreadable(REJECTED_MUTATIONS_CACHE_KEY, payload)
        queryCacheDao.upsert(rejectedRow(fresh))
        return fresh
    }

    /**
     * Keeps an unreadable reserved payload under `<cacheKey>.unreadable.<ms>` and logs it at ERROR.
     */
    private suspend fun keepAsideUnreadable(cacheKey: String, payload: String) {
        val asideCacheKey = "$cacheKey.unreadable.${nowMillis()}"
        queryCacheDao.upsert(reservedRow(asideCacheKey, payload))
        logger.error {
            "The mutation queue's $cacheKey record could not be read; kept it aside as $asideCacheKey and started a new one"
        }
    }

    private fun reservedRow(cacheKey: String, payload: String): CachedQueryRow =
        CachedQueryRow(
            table = RESERVED_TABLE_NAME,
            cacheKey = cacheKey,
            payload = payload,
            updatedAt = nowMillis(),
        )

    private suspend fun writeRejected(list: StoredRejectionList) {
        queryCacheDao.upsert(rejectedRow(list))
        rejectedWritten(list)
    }

    private fun rejectedRow(list: StoredRejectionList): CachedQueryRow =
        CachedQueryRow(
            table = RESERVED_TABLE_NAME,
            cacheKey = REJECTED_MUTATIONS_CACHE_KEY,
            payload = json.encodeToString(StoredRejectionList.serializer(), list),
            updatedAt = nowMillis(),
        )

    private fun bounded(entry: StoredRejection): StoredRejection {
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

    /** How many characters [entry] takes in the persisted list. */
    private fun charactersOf(entry: StoredRejection): Int =
        json.encodeToString(StoredRejection.serializer(), entry).length

    private fun <T> decodeOrNull(serializer: KSerializer<T>, payload: String): T? =
        try {
            json.decodeFromString(serializer, payload)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException. The payload is never logged.
            null
        }
}
