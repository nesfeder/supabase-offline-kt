// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** HTTP's "request timeout": Storage gave up waiting, and may not next time. */
private const val HTTP_REQUEST_TIMEOUT = 408

/** HTTP's "too many requests": Storage asks for a pause, not a change. */
private const val HTTP_TOO_MANY_REQUESTS = 429

/** The first status of a server failure, which may pass, rather than a refusal. */
private const val HTTP_SERVER_ERROR = 500

/** Sends one pending photo: its file, then its row, then the file into the library's cache. */
fun interface PendingPhotoUpload {
    /**
     * Uploads [photo], whose JPEG is [jpeg], handing [onProgress] the fraction of the file sent. It
     * returns once the upload is complete; a failure is thrown in the app's terms.
     */
    suspend fun send(photo: PendingPhoto, jpeg: ByteArray, onProgress: (Float) -> Unit)
}

/**
 * The worker of the app's pending-photo queue: whenever [canUpload] says the network is on and a
 * user is signed in, it uploads the photos waiting in [directory], one at a time, in the order they
 * were picked. The library never queues a Storage write; this queue is the app's own.
 *
 * Each `true` from [canUpload] is a new chance (the network came back, or changed): failed photos
 * another try may upload wait again, and a pass starts. A failure marks its photo failed with
 * [describeFailure]'s sentence and keeps its file; a failure for want of the network ends the pass,
 * and the photos after it stay waiting.
 */
class PendingPhotoUploader(
    private val directory: PendingPhotoDirectory,
    private val canUpload: Flow<Boolean>,
    private val upload: PendingPhotoUpload,
    private val describeFailure: (Throwable) -> String,
) {
    private val passes = Channel<Unit>(Channel.CONFLATED)
    private val allowed = MutableStateFlow(false)

    /** Held while one photo uploads, and by [pausedWhile]: never both at once. */
    private val uploadLock = Mutex()

    private val sent = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** The fraction sent of each photo uploading now, by photo id. */
    val progress: StateFlow<Map<String, Float>> = sent.asStateFlow()

    /** Starts watching [canUpload] and uploading, in [scope], until it is canceled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            canUpload.collect { can ->
                allowed.value = can
                if (can) {
                    directory.retryFailed()
                    passes.trySend(Unit)
                }
            }
        }
        for (pass in passes) uploadWaitingPhotos()
    }

    /** Starts a pass soon, when uploading is allowed: a photo was just filed, or retried. */
    fun uploadSoon() {
        passes.trySend(Unit)
    }

    /** Puts the failed photo [photoId] back to waiting, and starts a pass soon. */
    suspend fun retry(photoId: String) {
        directory.update(photoId) { photo ->
            if (photo.state == PendingPhotoState.Failed)
                photo.copy(state = PendingPhotoState.Waiting)
            else photo
        }
        uploadSoon()
    }

    /**
     * Runs [block] with no upload in progress: it waits for the photo uploading now, if any, and no
     * other starts until [block] returns. Start over runs so, so no upload lands after its deletes.
     */
    suspend fun <T> pausedWhile(block: suspend () -> T): T = uploadLock.withLock { block() }

    /** Uploads the waiting photos, oldest first, while uploading is allowed. */
    private suspend fun uploadWaitingPhotos() {
        while (allowed.value) {
            val endsPass = uploadLock.withLock {
                val next = directory.oldestWaiting() ?: return
                uploadOne(next)
            }
            if (endsPass) return
        }
    }

    /** Uploads [photo]; true when it failed for want of the network, which ends the pass. */
    private suspend fun uploadOne(photo: PendingPhoto): Boolean {
        val trying =
            directory.update(photo.photoId) {
                it.copy(state = PendingPhotoState.Uploading, attempts = it.attempts + 1)
            } ?: return false
        return try {
            upload.send(trying, directory.readJpeg(trying.photoId)) { fraction ->
                sent.update { it + (trying.photoId to fraction) }
            }
            directory.remove(trying.photoId) // uploaded: the device's copy goes
            false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            directory.update(trying.photoId) { // null when it was removed meanwhile
                it.copy(
                    state = PendingPhotoState.Failed,
                    failure = describeFailure(error),
                    retryable = error.mayPass(),
                )
            }
            error is NetworkUnavailableException // the next photo would fail the same way
        } finally {
            sent.update { it - trying.photoId }
        }
    }
}

/**
 * Whether another try may upload a photo this failure stopped: not when Storage refused the file
 * itself (its policies, its size, its type) or the server refused its row (its task is gone).
 */
private fun Throwable.mayPass(): Boolean =
    when (this) {
        is PhotoStorageException ->
            status >= HTTP_SERVER_ERROR ||
                status == HTTP_REQUEST_TIMEOUT ||
                status == HTTP_TOO_MANY_REQUESTS
        is TaskWriteRefusedException -> false
        else -> true // the network, the server or the session, any of which may come back
    }
