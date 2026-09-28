package io.github.nesfeder.supabase.offline.internal.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How many rejected mutations the list keeps; the oldest go first. */
internal const val MAX_REJECTED_MUTATIONS: Int = 100

/** Version written into every reserved row, so a later format can recognise this one. */
private const val OUTBOX_STATE_FORMAT_VERSION: Int = 1

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
 * One rejected mutation as persisted: everything its queue row held, so it can be re-queued exactly,
 * plus why it was refused.
 *
 * @property id The id it had in the queue.
 * @property table [MutationRow.table].
 * @property operation [MutationRow.operation], in its stored form.
 * @property bodyJson [MutationRow.bodyJson].
 * @property filterJson [MutationRow.filterJson].
 * @property createdAtMs [MutationRow.createdAtMs].
 * @property defaultToNull [MutationRow.defaultToNull].
 * @property onConflict [MutationRow.onConflict].
 * @property ignoreDuplicates [MutationRow.ignoreDuplicates].
 * @property deltaJson [MutationRow.deltaJson].
 * @property owner The identity the queue belonged to when the write was refused, or `null` when
 *   none was recorded.
 * @property rejectedAtMs When it was refused, by the device clock.
 * @property message A human-readable reason.
 * @property serverError The server's refusal, or `null` when the engine refused it locally.
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
    val deltaJson: String? = null,
    val owner: String? = null,
    val rejectedAtMs: Long,
    val message: String,
    val serverError: StoredServerError? = null,
) {
    /**
     * The queue row this rejection re-queues as: every stored field, a fresh id (`0`, so the queue
     * assigns the next one and the write goes to the tail) and a new creation time.
     *
     * @param createdAtMs When it is re-queued.
     * @return A pending row.
     */
    fun toRequeuedRow(createdAtMs: Long): MutationRow = MutationRow(
        table = table,
        operation = operation,
        bodyJson = bodyJson,
        filterJson = filterJson,
        createdAtMs = createdAtMs,
        syncedAtMs = null,
        defaultToNull = defaultToNull,
        onConflict = onConflict,
        ignoreDuplicates = ignoreDuplicates,
        deltaJson = deltaJson,
    )
}

/** The persisted rejected-mutation list, oldest first. */
@Serializable
private data class StoredRejectionList(
    val version: Int = OUTBOX_STATE_FORMAT_VERSION,
    val entries: List<StoredRejection> = emptyList(),
)

/** The persisted queue owner. */
@Serializable
private data class StoredQueueOwner(
    val version: Int = OUTBOX_STATE_FORMAT_VERSION,
    val identity: String,
)

/**
 * The outbox's persisted state — the rejected-mutation list and the queue owner — kept in reserved
 * `object_cache` rows (see [RESERVED_TABLE_NAME]) so that no schema change is needed.
 *
 * A reserved row that cannot be decoded is never read as "empty": that would silently discard
 * every rejection the app has not seen yet, or hand the queue to whoever asks next. Reads throw
 * [IllegalStateException] instead, naming only the decoder's exception type (a serializer message
 * can quote the payload).
 *
 * @param objectDao The object-cache DAO the rows live in.
 * @param json The engine's [Json].
 * @param nowMillis The store's clock, stamped on each row.
 */
internal class OutboxStateStore(
    private val objectDao: ObjectDao,
    private val json: Json,
    private val nowMillis: () -> Long,
) {

    /** Serializes read-modify-write of the rejected list. */
    private val rejectedListMutex = Mutex()

    /**
     * Every rejected mutation, oldest first.
     *
     * @return The list; empty when nothing was ever rejected.
     * @throws IllegalStateException when the persisted list cannot be decoded.
     */
    suspend fun rejections(): List<StoredRejection> =
        decodeRejections(objectDao.get(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE)?.payload)

    /**
     * [rejections], re-emitted whenever the list changes.
     *
     * @return A flow of the list, oldest first; it fails when the persisted list cannot be decoded.
     */
    fun rejectionsFlow(): Flow<List<StoredRejection>> =
        objectDao.observe(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE)
            .map { row -> row?.payload }
            .distinctUntilChanged()
            .map(::decodeRejections)

    /**
     * Adds [entry] as the newest rejection, replacing an earlier entry with the same id (a write
     * rejected again after its queue row outlived a crash), and keeps the newest
     * [MAX_REJECTED_MUTATIONS].
     *
     * @param entry The rejection.
     * @throws IllegalStateException when the persisted list cannot be decoded; nothing is written.
     */
    suspend fun addRejection(entry: StoredRejection) {
        rejectedListMutex.withLock {
            val kept = rejections().filterNot { existing -> existing.id == entry.id } + entry
            writeRejections(kept.takeLast(MAX_REJECTED_MUTATIONS))
        }
    }

    /**
     * Removes the rejection with [id] and returns it.
     *
     * @param id The rejected mutation's id.
     * @return The removed entry, or `null` when the list has no entry with [id].
     * @throws IllegalStateException when the persisted list cannot be decoded.
     */
    suspend fun removeRejection(id: Long): StoredRejection? = rejectedListMutex.withLock {
        val current = rejections()
        val removed = current.firstOrNull { entry -> entry.id == id } ?: return@withLock null
        writeRejections(current - removed)
        removed
    }

    /**
     * The identity the pending queue belongs to.
     *
     * @return Its stored value, or `null` when none has been recorded (an empty database, or a queue
     *   written by a build that did not record owners).
     * @throws IllegalStateException when the persisted owner cannot be decoded.
     */
    suspend fun queueOwner(): String? {
        val payload = objectDao.get(RESERVED_TABLE_NAME, QUEUE_OWNER_SIGNATURE)?.payload ?: return null
        return decode(StoredQueueOwner.serializer(), payload, what = "queue owner").identity
    }

    /**
     * Records [identity] as the owner of the pending queue.
     *
     * @param identity The owner's stored value.
     */
    suspend fun setQueueOwner(identity: String) {
        objectDao.upsert(
            ObjectRow(
                table = RESERVED_TABLE_NAME,
                signature = QUEUE_OWNER_SIGNATURE,
                payload = json.encodeToString(StoredQueueOwner.serializer(), StoredQueueOwner(identity = identity)),
                updatedAt = nowMillis(),
            ),
        )
    }

    private suspend fun writeRejections(entries: List<StoredRejection>) {
        objectDao.upsert(
            ObjectRow(
                table = RESERVED_TABLE_NAME,
                signature = REJECTED_MUTATIONS_SIGNATURE,
                payload = json.encodeToString(StoredRejectionList.serializer(), StoredRejectionList(entries = entries)),
                updatedAt = nowMillis(),
            ),
        )
    }

    private fun decodeRejections(payload: String?): List<StoredRejection> {
        if (payload == null) return emptyList()
        return decode(StoredRejectionList.serializer(), payload, what = "rejected-mutation list").entries
    }

    private fun <T> decode(serializer: KSerializer<T>, payload: String, what: String): T = try {
        json.decodeFromString(serializer, payload)
    } catch (failure: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException.
        throw IllegalStateException("The persisted $what cannot be decoded (${failure::class.simpleName})")
    }
}
