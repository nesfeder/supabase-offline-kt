@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.nesfeder.supabase.internal.db

import io.github.nesfeder.supabase.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.internal.postgrest.FilterEngine.encode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okio.Path
import kotlin.time.Clock

/**
 * Central persistence for offline features:
 *
 * - PostgREST object cache (SELECT payloads) via [ObjectDao]
 * - Storage metadata (downloaded files) via [StorageDao]
 * - Mutation queue (insert/upsert/update/delete) via [MutationDao]
 */
internal class OfflineStore(
    private val objectDao: ObjectDao,
    private val storageDao: StorageDao,
    private val mutationDao: MutationDao,
    private val dbPathProvider: DbPathProvider,
    private val settings: OfflineCacheSettings,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val dbSizeProvider: (Path) -> Long? = ::currentDbSizeBytes,
) {

    internal val json = Json { ignoreUnknownKeys = true }
    private val evictionMutex = Mutex()

    /* ────────────────────────── Object cache (SELECT payloads) ────────────────────────── */

    suspend fun addPayload(
        table: String,
        signature: String,
        payload: String
    ) {
        objectDao.upsert(
            row = ObjectRow(
                table = table,
                signature = signature,
                payload = payload,
                updatedAt = nowMillis()
            )
        )

        enforceDbSizeLimitIfNeeded()
    }

    suspend fun getPayload(table: String, signature: String): String? =
        objectDao.get(table, signature)?.payload

    suspend fun getPayloadWithTimestamp(table: String, signature: String): Pair<String, Long>? {
        val row = objectDao.get(table, signature) ?: return null
        return row.payload to row.updatedAt
    }

    fun observePayload(table: String, signature: String): Flow<String?> {
        return objectDao.observe(table, signature)
            .map { row -> row?.payload }
    }

    /** Emits the full list of cached rows for [table] whenever the object cache changes for that table. */
    fun observeObjectRowsForTable(table: String): Flow<List<ObjectRow>> =
        objectDao.observeAllForTable(table)

    /**
     * Returns all cached rows for [table], used for subset cache derivation.
     * The returned list may be empty if nothing has been cached for this table yet.
     */
    suspend fun getCandidateRowsForTable(table: String): List<ObjectRow> =
        objectDao.getAllForTable(table)

    suspend fun deleteTablePayload(table: String, signature: String) {
        objectDao.delete(table, signature)
    }

    suspend fun clearTable(table: String) {
        objectDao.deleteTable(table)
    }

    /* ───────────────────────────── Storage metadata ───────────────────────────── */

    suspend fun addStorage(
        bucketId: String,
        path: String,
        file: Path,
        size: Long?,
        eTag: String?,
    ) {
        val row = StorageRow(
            key = storageKey(bucketId, path),
            bucketId = bucketId,
            path = path,
            localPath = file.toString(),
            sizeBytes = size,
            eTag = eTag,
            lastFetchedEpochMs = nowMillis()
        )
        storageDao.upsert(row)
    }

    suspend fun getStorageByKey(
        bucketId: String,
        path: String
    ): StorageRow? {
        return storageDao.getByKey(storageKey(bucketId, path))
    }

    suspend fun deleteStorageByKey(
        bucketId: String,
        path: String
    ) {
        storageDao.delete(storageKey(bucketId, path))
    }

    private fun storageKey(bucketId: String, path: String) = "$bucketId::$path"

    /* ────────────────────────────── Mutation queue ────────────────────────────── */

    suspend fun enqueueInsert(
        table: String,
        body: JsonArray,
        filter: FilterDescriptor? = null,
        defaultToNull: Boolean = true
    ) {
        mutationDao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.INSERT,
                bodyJson = body.toString(),
                filterJson = filter?.let { encode(it, json) },
                createdAtMs = nowMillis(),
                syncedAtMs = null,
                defaultToNull = defaultToNull
            )
        )
    }

    suspend fun enqueueUpsert(
        table: String,
        bodyJson: JsonArray,
        filter: FilterDescriptor? = null,
        onConflict: String? = null,
        ignoreDuplicates: Boolean = false,
        defaultToNull: Boolean = true
    ) {
        mutationDao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.UPSERT,
                bodyJson = bodyJson.toString(),
                filterJson = filter?.let { encode(it, json) },
                createdAtMs = nowMillis(),
                syncedAtMs = null,
                defaultToNull = defaultToNull,
                onConflict = onConflict,
                ignoreDuplicates = ignoreDuplicates
            )
        )
    }

    /**
     * Enqueue an UPDATE mutation, with an optional [filter] describing the targeted rows.
     */
    suspend fun enqueueUpdate(
        table: String,
        body: JsonElement,
        filter: FilterDescriptor? = null
    ) {
        mutationDao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.UPDATE,
                bodyJson = body.toString(),
                filterJson = filter?.let { encode(it, json) },
                createdAtMs = nowMillis(),
                syncedAtMs = null
            )
        )
    }

    /**
     * Enqueue a DELETE mutation. [filter] describes which rows are removed.
     */
    suspend fun enqueueDelete(
        table: String,
        filter: FilterDescriptor? = null
    ) {
        mutationDao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.DELETE,
                bodyJson = "{}", // no body for delete
                filterJson = filter?.let { encode(it, json) },
                createdAtMs = nowMillis(),
                syncedAtMs = null
            )
        )
    }


    suspend fun pendingMutationsForTable(table: String): List<MutationRow> =
        mutationDao.pendingForTable(table)

    /**
     * Returns all pending mutations in creation order across **all tables**.
     * Used by [OfflineMutationSyncer.syncAllTables].
     */
    suspend fun allPendingMutations(): List<MutationRow> =
        mutationDao.pendingAll()

    /**
     * Total number of queued mutations across all tables.
     */
    suspend fun pendingMutationCount(): Int =
        mutationDao.countPending()

    /**
     * Flow of total number of queued mutations across all tables.
     */
    fun pendingMutationCountFlow(): Flow<Int> =
        mutationDao.countPendingFlow()

    /**
     * Number of queued mutations for a specific [table].
     */
    suspend fun pendingMutationCountForTable(table: String): Int =
        mutationDao.countPendingForTable(table)

    /**
     * Flow of number of queued mutations for a specific [table].
     */
    fun pendingMutationCountForTableFlow(table: String): Flow<Int> =
        mutationDao.countPendingForTableFlow(table)

    suspend fun deleteMutation(id: Long) {
        mutationDao.deleteById(id)
    }

    /* ────────────────── Overlay pending mutations onto SELECT JSON ────────────────── */

    /**
     * Apply client-side mutations to fresh network data for [table].
     *
     * For fresh data we only want to overlay *pending* (unsynced) mutations,
     * since the server already reflects anything that has been successfully
     * synced.
     */
    suspend fun applyPendingMutations(
        table: String,
        serverJson: String
    ): String {
        val pending = mutationDao.pendingForTable(table)
        if (pending.isEmpty()) return serverJson

        return OfflineMutationOverlay.applyPendingMutations(
            json = json,
            serverJson = serverJson,
            pending = pending
        )
    }

    /**
     * Apply both pending and already-synced mutations to cached data for
     * [table].
     *
     * When we fall back to cached SELECT payloads because the network is
     * unavailable, those payloads might be older than some mutations that
     * have since been synced. We need to re-apply both pending and synced
     * mutations on top of the cached snapshot so the UI reflects the latest
     * known state.
     */
    suspend fun applyAllMutationsForOfflineFallback(
        table: String,
        cachedJson: String
    ): String {
        val all = mutationDao.allForTable(table)
        if (all.isEmpty()) return cachedJson

        return OfflineMutationOverlay.applyPendingMutations(
            json = json,
            serverJson = cachedJson,
            pending = all
        )
    }

    /**
     * Apply both pending and already-synced mutations to cached data Flow for
     * [table].
     *
     * Combines a Flow of cached SELECT payloads with a Flow of all mutations
     * for that table, applying the mutations on top of each cached payload
     * as they arrive.
     *
     * The resulting Flow emits updated JSON strings whenever either the
     * cached payload or the mutations change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun Flow<String?>.applyAllMutationsForOfflineFallbackFlow(
        table: String
    ): Flow<String?> {
        return this.flatMapLatest { cachedJson ->
            if (cachedJson == null) return@flatMapLatest emptyFlow()

            mutationDao.allForTableFlow(table).map { all ->
                if (all.isEmpty()) return@map cachedJson
                OfflineMutationOverlay.applyPendingMutations(
                    json = json,
                    serverJson = cachedJson,
                    pending = all
                )
            }
        }
    }

    /**
     * Mark a mutation as synced at the current time.
     */
    suspend fun markMutationSynced(id: Long) {
        mutationDao.markSynced(id = id, syncedAtMs = nowMillis())
    }

    /**
     * Remove all already-synced mutations for [table]. Intended to be called
     * after a successful fresh SELECT for that table so the local queue
     * doesn't grow without bounds.
     */
    suspend fun clearSyncedMutationsForTable(table: String) {
        mutationDao.deleteSyncedForTable(table)
    }

    /* ──────────────────────── Size-based eviction (DB) ──────────────────────── */

    internal suspend fun enforceDbSizeLimitIfNeeded() {
        // If size-based eviction is disabled, bail out early.
        val max = settings.maxDbSizeBytes ?: return
        val path = dbPathProvider.dbPath()
        val size = dbSizeProvider(path) ?: return
        // If we're already at or below the configured limit, nothing to do.
        if (size <= max) return

        evictionMutex.lock()
        try {
            // Re-check using the size we already fetched to avoid a tiny race
            // window between reading the file size and acquiring the lock.
            var currentSize = size
            while (currentSize > max) {
                // Evict a small batch of the oldest cached SELECT payloads.
                objectDao.deleteOldestPayloadRows(limit = settings.evictionBatchSize)
                // Recompute the DB file size after eviction. If the file no
                // longer exists or its size can't be read, we stop.
                currentSize = dbSizeProvider(path) ?: break

                // Optional early stop once we've shrunk sufficiently below the
                // configured max, keeping at least [minRetainedBytes] of data
                // when possible so we don't wipe the entire cache.
                if (currentSize <= (max - settings.minRetainedBytes).coerceAtLeast(0)) break
            }
        } finally {
            evictionMutex.unlock()
        }
    }
}