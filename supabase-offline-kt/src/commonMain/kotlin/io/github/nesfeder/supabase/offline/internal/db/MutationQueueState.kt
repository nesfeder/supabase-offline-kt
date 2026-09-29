@file:OptIn(ExperimentalSerializationApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.SdkLogger
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

/** Version written into every reserved row, so a later format can recognise this one. */
private const val MUTATION_QUEUE_STATE_FORMAT_VERSION: Int = 1

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
 * @property deltaJson [MutationRow.deltaJson].
 * @property owner The identity that made the write, or `null` when none was recorded.
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
    val deltaJson: String? = null,
    val owner: String? = null,
    val rejectedAtMs: Long,
    val message: String,
    val serverError: StoredServerError? = null,
    val bodyOmittedChars: Int? = null,
) {
    /**
     * The mutation row this rejection re-queues as: every stored field, a fresh id (`0`, so the
     * queue assigns the next one and the write goes to the tail) and a new creation time.
     *
     * @param createdAtMs When it is re-queued.
     * @return A pending row, or `null` when the body was not kept.
     */
    fun toRequeuedRow(createdAtMs: Long): MutationRow? {
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
            deltaJson = deltaJson,
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
    @EncodeDefault val version: Int = MUTATION_QUEUE_STATE_FORMAT_VERSION,
    val entries: List<StoredRejection> = emptyList(),
    val droppedCount: Long = 0,
)

/** The persisted owner of each queued mutation, by mutation id. */
@Serializable
private data class StoredMutationOwners(
    @EncodeDefault val version: Int = MUTATION_QUEUE_STATE_FORMAT_VERSION,
    val owners: Map<Long, String> = emptyMap(),
)

/** For each synced mutation, the cache keys whose payload already contains it. */
@Serializable
private data class StoredSyncedMutationCoverage(
    @EncodeDefault val version: Int = MUTATION_QUEUE_STATE_FORMAT_VERSION,
    val coveredSignatures: Map<Long, List<String>> = emptyMap(),
)

/** The INSERT mutations whose send started and whose outcome is unknown. */
@Serializable
private data class StoredUnconfirmedSends(
    @EncodeDefault val version: Int = MUTATION_QUEUE_STATE_FORMAT_VERSION,
    val mutationIds: List<Long> = emptyList(),
)

/** Who owns each queued mutation, as far as the engine can read it. */
internal sealed interface MutationOwners {

    /**
     * The recorded owners. A pending mutation with no entry was queued by an earlier build of the
     * engine and has no recorded owner yet.
     *
     * @property byId Mutation id → the owner's stored identity value.
     */
    data class Known(val byId: Map<Long, String>) : MutationOwners

    /** The owners row exists but cannot be decoded. Nothing is replayed until local data is cleared. */
    data object Unreadable : MutationOwners
}

/**
 * The mutation queue's persisted state — who owns each queued write, and the rejected-mutation
 * list — kept in reserved `object_cache` rows (see [RESERVED_TABLE_NAME]) so that no schema change
 * is needed. Both are cached in memory after the first read; every change goes through this class.
 *
 * **Callers serialize.** This class takes no lock of its own: the store calls it only while holding
 * its mutation-queue lock, and computes each new row here *before* opening a database transaction,
 * so no transaction ever waits on a coroutine lock.
 *
 * An unreadable rejected list is moved aside under its own reserved key, logged at ERROR, and a fresh
 * list started, so the queue never wedges on its bookkeeping and the old payload is kept, not
 * discarded. An unreadable owners row is kept as is and reported as [MutationOwners.Unreadable]: the
 * engine then queues nothing new and replays nothing, since it cannot tell whose writes are whose.
 *
 * @param objectDao The object-cache DAO the rows live in.
 * @param json The engine's [Json].
 * @param nowMillis The store's clock, stamped on each row.
 * @param logger The store's logger, read at each use.
 */
internal class MutationQueueState(
    private val objectDao: ObjectDao,
    private val json: Json,
    private val nowMillis: () -> Long,
    private val logger: () -> SdkLogger,
) {

    private val ownersCache = MutableStateFlow<MutationOwners?>(null)
    private val rejectedCache = MutableStateFlow<StoredRejectionList?>(null)
    private val coverageCache = MutableStateFlow<Map<Long, Set<String>>?>(null)
    private val unconfirmedCache = MutableStateFlow<Set<Long>?>(null)

    /* ─────────────────────────────── Owners ─────────────────────────────── */

    /**
     * Who owns each queued mutation.
     *
     * @return The owners, or [MutationOwners.Unreadable].
     */
    suspend fun owners(): MutationOwners {
        ownersCache.value?.let { return it }
        val loaded = decodeOwners(objectDao.get(RESERVED_TABLE_NAME, MUTATION_OWNERS_SIGNATURE)?.payload)
        ownersCache.compareAndSet(null, loaded)
        return ownersCache.value ?: loaded
    }

    /**
     * [owners], re-emitted whenever they change.
     *
     * @return A flow of the owners.
     */
    fun ownersFlow(): Flow<MutationOwners> = flow {
        owners()
        emitAll(ownersCache.filterNotNull())
    }

    /**
     * The owners row with [changes] applied, ready to write in the caller's transaction; commit it
     * with [ownersWritten] afterwards. Caller holds the store's mutation-queue lock.
     *
     * @param changes Turns the current map into the new one.
     * @return The row and the new owners, or `null` when the owners cannot be read.
     */
    suspend fun ownersRowWith(changes: (Map<Long, String>) -> Map<Long, String>): Pair<ObjectRow, MutationOwners.Known>? {
        val current = owners() as? MutationOwners.Known ?: return null
        val next = MutationOwners.Known(changes(current.byId))
        val row = ObjectRow(
            table = RESERVED_TABLE_NAME,
            signature = MUTATION_OWNERS_SIGNATURE,
            payload = json.encodeToString(StoredMutationOwners.serializer(), StoredMutationOwners(owners = next.byId)),
            updatedAt = nowMillis(),
        )
        return row to next
    }

    /**
     * Records that [owners] was written.
     *
     * @param owners The owners the committed row holds.
     */
    fun ownersWritten(owners: MutationOwners.Known) {
        ownersCache.value = owners
    }

    /* ─────────────────────── Synced-mutation coverage ─────────────────────── */

    /**
     * For each synced mutation, the cache keys whose payload a network response wrote after it
     * replayed. Such a payload already holds the mutation, so the overlay leaves it out there.
     */
    suspend fun syncedMutationCoverage(): Map<Long, Set<String>> {
        coverageCache.value?.let { return it }
        val payload = objectDao.get(RESERVED_TABLE_NAME, SYNCED_MUTATION_COVERAGE_SIGNATURE)?.payload
        val loaded = if (payload == null) {
            emptyMap()
        } else {
            decodeOrNull(StoredSyncedMutationCoverage.serializer(), payload)
                ?.coveredSignatures
                ?.mapValues { (_, signatures) -> signatures.toSet() }
                ?: keepAsideUnreadable(SYNCED_MUTATION_COVERAGE_SIGNATURE, payload, emptyMap())
        }
        coverageCache.compareAndSet(null, loaded)
        return coverageCache.value ?: loaded
    }

    /** [syncedMutationCoverage], re-emitted whenever it changes. */
    fun syncedMutationCoverageFlow(): Flow<Map<Long, Set<String>>> = flow {
        syncedMutationCoverage()
        emitAll(coverageCache.filterNotNull())
    }

    /**
     * The coverage row with [changes] applied, for the caller's transaction, or `null` when nothing
     * changes; commit it with [syncedMutationCoverageWritten]. Caller holds the mutation-queue lock.
     */
    suspend fun syncedMutationCoverageRowWith(
        changes: (Map<Long, Set<String>>) -> Map<Long, Set<String>>,
    ): Pair<ObjectRow, Map<Long, Set<String>>>? {
        val current = syncedMutationCoverage()
        val next = changes(current).filterValues { signatures -> signatures.isNotEmpty() }
        if (next == current) return null
        val stored = StoredSyncedMutationCoverage(coveredSignatures = next.mapValues { (_, signatures) -> signatures.sorted() })
        return reservedRow(SYNCED_MUTATION_COVERAGE_SIGNATURE, json.encodeToString(StoredSyncedMutationCoverage.serializer(), stored)) to next
    }

    /** Records that [coverage] was written. */
    fun syncedMutationCoverageWritten(coverage: Map<Long, Set<String>>) {
        coverageCache.value = coverage
    }

    /* ─────────────────────────── Unconfirmed sends ─────────────────────────── */

    /**
     * The INSERT mutations whose send started and whose outcome is unknown. Their replay must
     * tolerate finding their rows already on the server; every other INSERT replays as sent.
     */
    suspend fun unconfirmedSends(): Set<Long> {
        unconfirmedCache.value?.let { return it }
        val payload = objectDao.get(RESERVED_TABLE_NAME, UNCONFIRMED_SENDS_SIGNATURE)?.payload
        val loaded = if (payload == null) {
            emptySet()
        } else {
            decodeOrNull(StoredUnconfirmedSends.serializer(), payload)?.mutationIds?.toSet()
                ?: keepAsideUnreadable(UNCONFIRMED_SENDS_SIGNATURE, payload, emptySet())
        }
        unconfirmedCache.compareAndSet(null, loaded)
        return unconfirmedCache.value ?: loaded
    }

    /**
     * The unconfirmed-sends row with [changes] applied, for the caller's transaction, or `null`
     * when nothing changes; commit it with [unconfirmedSendsWritten]. Caller holds the
     * mutation-queue lock.
     */
    suspend fun unconfirmedSendsRowWith(changes: (Set<Long>) -> Set<Long>): Pair<ObjectRow, Set<Long>>? {
        val current = unconfirmedSends()
        val next = changes(current)
        if (next == current) return null
        val stored = StoredUnconfirmedSends(mutationIds = next.sorted())
        return reservedRow(UNCONFIRMED_SENDS_SIGNATURE, json.encodeToString(StoredUnconfirmedSends.serializer(), stored)) to next
    }

    /** Records that [unconfirmed] was written. */
    fun unconfirmedSendsWritten(unconfirmed: Set<Long>) {
        unconfirmedCache.value = unconfirmed
    }

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

    /** Resets [droppedCount] to zero. Caller holds the store's mutation-queue lock. */
    suspend fun resetDroppedCount() {
        val current = rejectedList()
        writeRejected(current.copy(droppedCount = 0))
    }

    /**
     * Adds [entry] as the newest rejection and writes the list. See [rejectedRowWith].
     *
     * @param entry The rejection.
     */
    suspend fun addRejection(entry: StoredRejection) {
        val (row, list) = rejectedRowWith(add = entry)
        objectDao.upsert(row)
        rejectedWritten(list)
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
        objectDao.upsert(row)
        rejectedWritten(list)
        return removed
    }

    /**
     * Whether the list holds an entry with [id]. Answered from memory once the list is loaded.
     *
     * @param id A mutation id.
     * @return `true` when it is on the list.
     */
    suspend fun hasRejection(id: Long): Boolean = rejectedList().entries.any { entry -> entry.id == id }

    /**
     * The rejected-list row with [add] added as the newest entry (replacing an earlier entry with the
     * same id) and the entry with [removeId] removed, bounded to [MAX_REJECTED_MUTATIONS] entries and
     * [MAX_REJECTED_LIST_CHARS] characters. Oversized bodies and server texts are trimmed first; each
     * entry that has to go is logged and counted. Write the row in the caller's transaction, then
     * commit it with [rejectedWritten]. Caller holds the store's mutation-queue lock.
     *
     * @param add An entry to add, or `null`.
     * @param removeId An entry to remove, or `null`.
     * @return The row and the new list.
     */
    suspend fun rejectedRowWith(
        add: StoredRejection? = null,
        removeId: Long? = null,
        removeIds: Set<Long> = emptySet(),
    ): Pair<ObjectRow, StoredRejectionList> {
        val current = rejectedList()
        var entries = current.entries.filterNot { entry -> entry.id == removeId || entry.id in removeIds || entry.id == add?.id }
        if (add != null) entries = entries + bounded(add)
        var droppedCount = current.droppedCount
        while (entries.size > MAX_REJECTED_MUTATIONS || charactersOf(entries) > MAX_REJECTED_LIST_CHARS) {
            val dropped = entries.first()
            logger().warning {
                "Rejected list full: dropped rejected ${dropped.operation} ${dropped.id} on ${dropped.table}"
            }
            entries = entries.drop(1)
            droppedCount++
        }
        val list = StoredRejectionList(entries = entries, droppedCount = droppedCount)
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

    /** Forgets every cached row; called once [OfflineStore.clearAll] has deleted the rows. */
    fun resetCaches() {
        ownersCache.value = null
        rejectedCache.value = null
        coverageCache.value = null
        unconfirmedCache.value = null
    }

    private suspend fun rejectedList(): StoredRejectionList {
        rejectedCache.value?.let { return it }
        val payload = objectDao.get(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE)?.payload
        val loaded = if (payload == null) {
            StoredRejectionList()
        } else {
            decodeOrNull(StoredRejectionList.serializer(), payload) ?: moveAsideUnreadable(payload)
        }
        rejectedCache.compareAndSet(null, loaded)
        return rejectedCache.value ?: loaded
    }

    /** Keeps an unreadable rejected-list payload aside and starts a new list. */
    private suspend fun moveAsideUnreadable(payload: String): StoredRejectionList {
        val fresh = StoredRejectionList()
        return keepAsideUnreadable(REJECTED_MUTATIONS_SIGNATURE, payload, fresh).also { objectDao.upsert(rejectedRow(fresh)) }
    }

    /**
     * Keeps an unreadable reserved payload under `<signature>.unreadable.<ms>`, logs it at ERROR, and
     * returns [fresh] to start over with.
     */
    private suspend fun <T> keepAsideUnreadable(signature: String, payload: String, fresh: T): T {
        val asideSignature = "$signature.unreadable.${nowMillis()}"
        objectDao.upsert(reservedRow(asideSignature, payload))
        logger().error { "The mutation queue's $signature record could not be read; kept it aside as $asideSignature and started a new one" }
        return fresh
    }

    private fun reservedRow(signature: String, payload: String): ObjectRow =
        ObjectRow(table = RESERVED_TABLE_NAME, signature = signature, payload = payload, updatedAt = nowMillis())

    private suspend fun writeRejected(list: StoredRejectionList) {
        objectDao.upsert(rejectedRow(list))
        rejectedWritten(list)
    }

    private fun rejectedRow(list: StoredRejectionList): ObjectRow = ObjectRow(
        table = RESERVED_TABLE_NAME,
        signature = REJECTED_MUTATIONS_SIGNATURE,
        payload = json.encodeToString(StoredRejectionList.serializer(), list),
        updatedAt = nowMillis(),
    )

    private fun bounded(entry: StoredRejection): StoredRejection {
        var bounded = entry.copy(
            message = entry.message.take(MAX_REJECTED_TEXT_CHARS),
            serverError = entry.serverError?.let { error ->
                error.copy(
                    message = error.message.take(MAX_REJECTED_TEXT_CHARS),
                    details = error.details?.take(MAX_REJECTED_TEXT_CHARS),
                    hint = error.hint?.take(MAX_REJECTED_TEXT_CHARS),
                )
            },
        )
        if (entry.bodyJson.length > MAX_REJECTED_BODY_CHARS) {
            logger().warning {
                "Rejected ${entry.operation} ${entry.id} on ${entry.table}: its body of " +
                    "${entry.bodyJson.length} characters is too long to keep, so it cannot be retried"
            }
            bounded = bounded.copy(bodyJson = "", bodyOmittedChars = entry.bodyJson.length)
        }
        return bounded
    }

    private fun charactersOf(entries: List<StoredRejection>): Int =
        entries.sumOf { entry -> json.encodeToString(StoredRejection.serializer(), entry).length }

    private fun decodeOwners(payload: String?): MutationOwners {
        if (payload == null) return MutationOwners.Known(emptyMap())
        val decoded = decodeOrNull(StoredMutationOwners.serializer(), payload)
        if (decoded == null) {
            logger().error { "The queue's owner records could not be read; nothing is queued or replayed until local data is cleared" }
            return MutationOwners.Unreadable
        }
        return MutationOwners.Known(decoded.owners)
    }

    private fun <T> decodeOrNull(serializer: KSerializer<T>, payload: String): T? = try {
        json.decodeFromString(serializer, payload)
    } catch (_: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException. The payload is never logged.
        null
    }
}
