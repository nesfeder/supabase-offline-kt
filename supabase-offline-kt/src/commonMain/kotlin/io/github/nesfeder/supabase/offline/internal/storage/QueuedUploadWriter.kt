// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.storage.FileUploadResponse
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.UploadOutcome
import io.github.nesfeder.supabase.offline.internal.TransportClosedException
import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.EnqueueOutcome
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer.HeadSendOutcome
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.uploadMayPassWhenSentAgain
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import okio.IOException

/**
 * Makes the uploads a bucket is asked to make under `MutationStrategy.QueueIfOffline`, as
 * `OfflineTable` makes a row write: the bytes kept on the device and the upload queued first, then
 * sent at once when nothing of its user is pending ahead of it. Accepted, it is `Applied`;
 * unanswered, or answered with what a replay keeps queued too, it stays `Queued`; refused, it
 * leaves the queue and the failure is thrown; behind other writes, it waits and a pass is asked
 * for. An upload the queue cannot hold faithfully is sent at once and never queued.
 *
 * @param queueIdentity the identity an upload is made for, read as it is made; `null` when it
 *   cannot be told, and the upload is then sent at once.
 * @param requestSync asks for a replay pass.
 * @param queueEmptied told when an upload sent at once left its user's queue empty.
 */
internal class QueuedUploadWriter(
    val uploads: QueuedUploads,
    private val mutationQueue: MutationQueue,
    private val freshness: CacheFreshness,
    private val headSender: MutationQueueReplayer,
    private val queueIdentity: suspend () -> QueueIdentity?,
    private val requestSync: () -> Unit,
    private val queueEmptied: suspend () -> Unit,
    private val engineLogger: EngineLogger,
) {

    /**
     * Queues and sends the upload of [data] to [path] in [bucketId], whose options read as
     * [options], unless [notQueueableBecause] says why it cannot be queued faithfully.
     *
     * @param send the upload's request, with the app's own options, as supabase-kt sends it.
     * @param sendDirectly the upload sent at once and never queued, through the offline bucket.
     * @throws MutationNotQueueableException when an upload the queue cannot hold could not be sent
     *   for want of a connection.
     */
    suspend fun upload(
        bucketId: String,
        path: String,
        data: ByteArray,
        options: QueuedUploadOptions,
        notQueueableBecause: String?,
        send: suspend () -> FileUploadResponse,
        sendDirectly: suspend () -> FileUploadResponse,
    ): UploadOutcome {
        val direct = DirectUpload(bucketId, sendDirectly)
        if (notQueueableBecause != null) {
            return direct.send(EngineLogLevel.INFO) {
                "Not queueing an upload to $bucketId: $notQueueableBecause"
            }
        }
        val startedAtTick = freshness.tickNow()
        // Taken as the upload is made: a session switch while it is in flight must not re-own it
        val identity =
            queueIdentity()
                ?: return direct.send(EngineLogLevel.WARNING) {
                    "Not queueing an upload to $bucketId: the session's identity cannot be told"
                }
        val file =
            try {
                uploads.files.stage(data)
            } catch (failure: IOException) {
                return direct.send(EngineLogLevel.WARNING) {
                    "Not queueing an upload to $bucketId: its file could not be kept on the device " +
                        "(${failure::class.simpleName})"
                }
            }
        val enqueued =
            try {
                mutationQueue.enqueueOwnedBy(identity.storedValue, startedAtTick) {
                    enqueueUpload(
                        StoredUpload(
                            bucket = bucketId,
                            path = path,
                            file = file,
                            size = data.size.toLong(),
                            upsert = options.upsert,
                            contentType = options.contentType,
                            userMetadata = options.userMetadata,
                        )
                    )
                }
            } catch (failure: Throwable) {
                uploads.files.delete(file)
                throw failure
            } finally {
                uploads.files.endStaging(file)
            }
        val queued =
            when (enqueued) {
                is EnqueueOutcome.Queued -> enqueued.row
                EnqueueOutcome.LocalDataCleared -> {
                    uploads.files.delete(file)
                    return direct.send(EngineLogLevel.INFO) {
                        "Not queueing an upload to $bucketId: local data was cleared while it was in flight"
                    }
                }
            }
        return sendQueued(queued, identity, bucketId, path, data, send)
    }

    /** Sends the queued upload [queued] at the head of [identity]'s queue, when it is there. */
    private suspend fun sendQueued(
        queued: MutationRow,
        identity: QueueIdentity,
        bucketId: String,
        path: String,
        data: ByteArray,
        send: suspend () -> FileUploadResponse,
    ): UploadOutcome {
        val sent =
            try {
                headSender.sendAtHead(
                    queued.id,
                    identity,
                    send,
                    staysQueuedOnAnswer = { failure -> failure.uploadMayPassWhenSentAgain() },
                ) { _ ->
                    uploads.becomeCachedCopy(bucketId, path, data)
                }
            } catch (cancellation: CancellationException) {
                // Canceled on the wire: whether Storage got it is not known, so it stays queued,
                // and a pass sends it
                requestSync()
                throw cancellation
            }
        return when (sent) {
            HeadSendOutcome.NotAtHead -> {
                engineLogger.info { "An upload to $bucketId was queued behind pending mutations" }
                requestSync()
                UploadOutcome.Queued(pendingUploadOf(queued))
            }
            is HeadSendOutcome.Sent -> {
                uploads.release(queued)
                if (!requestPassIfPending(identity)) queueEmptied()
                UploadOutcome.Applied(sent.result)
            }
            is HeadSendOutcome.StaysQueued -> {
                engineLogger.info {
                    if (sent.cause.isNetworkOrConnectivityFailure())
                        "An upload to $bucketId was queued offline (network unavailable)"
                    else "An upload to $bucketId was queued (${sent.cause::class.simpleName})"
                }
                UploadOutcome.Queued(pendingUploadOf(queued))
            }
            is HeadSendOutcome.Refused -> {
                uploads.release(queued)
                requestPassIfPending(identity)
                throw sent.cause
            }
            is HeadSendOutcome.Cleared -> {
                engineLogger.info {
                    "An upload to $bucketId returned after local data was cleared; the cache is left as it was"
                }
                uploads.release(queued)
                requestPassIfPending(identity)
                sent.result?.let { response -> UploadOutcome.Applied(response) }
                    ?: throw checkNotNull(sent.cause)
            }
        }
    }

    /** The queued upload [row] as the caller sees it in [UploadOutcome.Queued]. */
    private fun pendingUploadOf(row: MutationRow) =
        checkNotNull(uploads.pendingUploadOf(row)) { "the queue stored an upload it cannot read" }

    /**
     * Asks for a pass when [identity] has writes queued, which the head it just left held back, or
     * when that cannot be read.
     *
     * @return whether [identity] may have writes queued.
     */
    private suspend fun requestPassIfPending(identity: QueueIdentity): Boolean {
        val pending = mutationQueue.mayHavePendingMutationsOf(identity.storedValue)
        if (pending) requestSync()
        return pending
    }

    /** An upload sent at once, outside the queue. */
    private inner class DirectUpload(
        private val bucketId: String,
        private val sendDirectly: suspend () -> FileUploadResponse,
    ) {

        /**
         * Sends the upload. A connectivity failure is logged with [whyNotQueued] and thrown as a
         * [MutationNotQueueableException] carrying it; every other failure is thrown as it is.
         */
        suspend fun send(level: EngineLogLevel, whyNotQueued: () -> String): UploadOutcome {
            val response =
                try {
                    sendDirectly()
                } catch (failure: Exception) {
                    // The client is closing, not offline: the caller gets the closed error
                    if (failure is TransportClosedException) throw failure
                    if (!failure.isNetworkOrConnectivityFailure()) throw failure
                    engineLogger.log(level, whyNotQueued)
                    throw MutationNotQueueableException(
                        "${whyNotQueued()}, and it could not be sent",
                        failure,
                    )
                }
            engineLogger.debug { "An upload to $bucketId was sent at once" }
            return UploadOutcome.Applied(response)
        }
    }
}

/**
 * The options of an upload the queue keeps with it, read from the app's `UploadOptionBuilder`.
 *
 * @property upsert whether the upload overwrites an object already at its path.
 * @property contentType the content type it sets, as text, or `null` for none.
 * @property userMetadata the user metadata it sets, or `null` for none.
 */
internal class QueuedUploadOptions(
    val upsert: Boolean,
    val contentType: String?,
    val userMetadata: JsonObject?,
)
