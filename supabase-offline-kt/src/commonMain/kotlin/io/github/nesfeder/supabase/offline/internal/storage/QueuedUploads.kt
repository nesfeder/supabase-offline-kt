// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.UploadOptionBuilder
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.PendingUpload
import io.github.nesfeder.supabase.offline.RejectedUpload
import io.github.nesfeder.supabase.offline.ServerError
import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueuedUploadSends
import io.github.nesfeder.supabase.offline.internal.mutationqueue.STORAGE_NOT_INSTALLED_REASON
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredRejection
import io.github.nesfeder.supabase.offline.internal.mutationqueue.UploadSend
import io.ktor.http.ContentType
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Why a queued upload whose body no longer reads is refused. */
internal const val UNREADABLE_UPLOAD_REASON: String = "The queued upload can no longer be read"

/** Why a queued upload whose file is gone from the device, or cut short, is refused. */
internal const val MISSING_UPLOAD_FILE_REASON: String =
    "The queued upload's file is no longer on this device"

/**
 * The client's queued Storage uploads: the files that hold their bytes, how a pass sends one, what
 * a sent one leaves in the file cache, and the queued copy `cachedFile` answers while one waits.
 * The rows themselves are the mutation queue's.
 *
 * @param bucketOf supabase-kt's bucket [bucketOf] a pass sends an upload through, or `null` when
 *   Storage is not installed.
 */
internal class QueuedUploads(
    val files: QueuedUploadFiles,
    private val mutationQueue: MutationQueue,
    private val fileCache: StorageFileCache,
    private val engineLogger: EngineLogger,
    private val bucketOf: (bucketId: String) -> BucketApi?,
    private val json: Json = EngineJson,
) : QueuedUploadSends {

    override suspend fun prepare(upload: MutationRow, earlierSendUnconfirmed: Boolean): UploadSend {
        val stored =
            storedUploadOf(upload.bodyJson, json)?.takeIf { it.hasQueuedFileName }
                ?: return UploadSend.Refused(
                    "has a body that can no longer be read",
                    UNREADABLE_UPLOAD_REASON,
                )
        val bucket =
            bucketOf(stored.bucket)
                ?: return UploadSend.Refused(
                    "cannot be sent: Storage is not installed",
                    STORAGE_NOT_INSTALLED_REASON,
                )
        val bytes =
            files.read(stored.file, stored.size)
                ?: return UploadSend.Refused("has lost its file", MISSING_UPLOAD_FILE_REASON)
        return UploadSend.Ready {
            // A lost answer may have stored it: an upload that may not overwrite asks first, as a
            // second send would be refused as a duplicate. One that may is sent again
            val alreadyStored =
                earlierSendUnconfirmed && !stored.upsert && bucket.exists(stored.path)
            if (!alreadyStored) bucket.upload(stored.path, bytes) { applyStoredOptions(stored) }
            becomeCachedCopy(stored.bucket, stored.path, bytes)
            alreadyStored
        }
    }

    override suspend fun sent(upload: MutationRow) {
        release(upload)
    }

    /** Deletes the file of the upload [row], which no longer waits in the queue. */
    suspend fun release(row: MutationRow) {
        storedUploadOf(row.bodyJson, json)
            ?.takeIf { it.hasQueuedFileName }
            ?.let { stored -> files.delete(stored.file) }
    }

    /** Deletes the file of the refused upload [entry], which left the rejected list. */
    suspend fun release(entry: StoredRejection) {
        storedUploadOf(entry.bodyJson, json)
            ?.takeIf { it.hasQueuedFileName }
            ?.let { stored -> files.delete(stored.file) }
    }

    /**
     * Makes [bytes], which Storage now holds at [path] in [bucketId], the object's cached copy: its
     * stale copies go first, as for any write through the offline bucket, so a download still on
     * the wire is never cached over them. A path the cache does not map onto a file is only
     * dropped. Best effort: a failure is logged, never thrown, since Storage has the file.
     */
    suspend fun becomeCachedCopy(bucketId: String, path: String, bytes: ByteArray) {
        try {
            withContext(NonCancellable) {
                fileCache.invalidateObject(bucketId, path)
                if (!isCacheableBucketId(bucketId) || !isRequestedByItsOwnName(path)) {
                    return@withContext
                }
                val entry = fileCache.entryFor(bucketId, path) ?: return@withContext
                val start = fileCache.beginDownload()
                try {
                    fileCache.write(entry, bytes, start)
                } finally {
                    fileCache.endDownload(start)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Could not keep an uploaded file in the file cache (${failure::class.simpleName}); " +
                    "it is downloaded when next asked for"
            }
        }
    }

    /**
     * The queued copy of the object at [path] in [bucketId]: the file of the newest upload of it
     * still queued for the identity the overlay shows writes of (every identity's while that cannot
     * be told, as the overlay shows a write to its maker), or `null` when there is none, or its
     * file is no longer whole.
     */
    suspend fun queuedCopyOf(bucketId: String, path: String): CachedFile? {
        val (row, stored) =
            mutationQueue.visiblePendingUploads().asReversed().firstNotNullOfOrNull { row ->
                storedUploadOf(row.bodyJson, json)
                    ?.takeIf { stored ->
                        stored.bucket == bucketId && stored.path == path && stored.hasQueuedFileName
                    }
                    ?.let { stored -> row to stored }
            } ?: return null
        val size = files.sizeOf(stored.file)?.takeIf { size -> size == stored.size } ?: return null
        return CachedFile(
            path = files.fileOf(stored.file).toString(),
            fetchedAt = Instant.fromEpochMilliseconds(row.createdAtMs),
            sizeBytes = size,
        )
    }

    /**
     * Deletes every queued file that no pending upload and no rejected entry names, as a crash
     * between a file's rename and its row's insert, or an entry the rejected list's bound dropped,
     * leaves one. Under the queue lock, so no upload is queued between the read and the deletes; a
     * file being written for one is staged, and spared.
     */
    suspend fun sweepUnnamedFiles() {
        // Nothing to sweep, and no lock to take, on a device that never queued an upload
        if (!files.directoryExists()) return
        val deleted = mutationQueue.holdingQueueLock {
            files.deleteAllExcept(mutationQueue.queuedUploadFileNames())
        }
        if (deleted > 0) {
            engineLogger.info { "Deleted $deleted queued upload file(s) no queued upload names" }
        }
    }

    /** The upload [row] as the app sees it, or `null` when its body no longer reads. */
    fun pendingUploadOf(row: MutationRow): PendingUpload? {
        val stored = storedUploadOf(row.bodyJson, json) ?: return null
        return PendingUpload(
            id = row.id,
            bucketId = stored.bucket,
            path = stored.path,
            createdAt = Instant.fromEpochMilliseconds(row.createdAtMs),
            sizeBytes = stored.size,
            contentType = stored.contentType,
            upsert = stored.upsert,
        )
    }

    /**
     * The refused upload [entry] as the app sees it. One whose body no longer reads is still shown,
     * so the app can dismiss it: with an empty bucket and path, and never retryable.
     */
    suspend fun rejectedUploadOf(entry: StoredRejection): RejectedUpload {
        val stored = storedUploadOf(entry.bodyJson, json)
        val retryable =
            entry.bodyOmittedChars == null &&
                stored != null &&
                stored.hasQueuedFileName &&
                files.sizeOf(stored.file) == stored.size
        return RejectedUpload(
            id = entry.id,
            bucketId = stored?.bucket.orEmpty(),
            path = stored?.path.orEmpty(),
            createdAt = Instant.fromEpochMilliseconds(entry.createdAtMs),
            sizeBytes = stored?.size ?: 0L,
            contentType = stored?.contentType,
            upsert = stored?.upsert ?: false,
            serverError =
                entry.serverError?.let { error ->
                    ServerError(
                        status = error.status,
                        code = error.code,
                        message = error.message,
                        details = error.details,
                        hint = error.hint,
                    )
                },
            message = entry.message,
            rejectedAt = Instant.fromEpochMilliseconds(entry.rejectedAtMs),
            isRetryable = retryable,
        )
    }

    /** Whether the refused upload [entry] can be queued again: its file is still whole. */
    suspend fun isRetryable(entry: StoredRejection): Boolean {
        val stored = storedUploadOf(entry.bodyJson, json)?.takeIf { it.hasQueuedFileName }
        return stored != null && files.sizeOf(stored.file) == stored.size
    }
}

/** Sets the options [stored] was queued with on this builder, as the app's own upload set them. */
internal fun UploadOptionBuilder.applyStoredOptions(stored: StoredUpload) {
    upsert = stored.upsert
    stored.contentType?.let { type -> contentType = ContentType.parse(type) }
    stored.userMetadata?.let { metadata -> userMetadata = metadata }
}
