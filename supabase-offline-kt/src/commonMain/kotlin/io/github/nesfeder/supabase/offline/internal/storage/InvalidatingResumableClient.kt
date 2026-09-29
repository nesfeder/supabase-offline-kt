package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.storage.UploadOptionBuilder
import io.github.nesfeder.supabase.offline.internal.ClientOperationGate
import io.github.jan.supabase.storage.resumable.ResumableClient
import io.github.jan.supabase.storage.resumable.ResumableUpload
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * supabase-kt's resumable client, whose finished uploads drop the cached copies they replaced. Its
 * uploads are sealed upstream, so each is watched in [backgroundScope] until it finishes or the scope
 * ends; a closed client's [operationGate] refuses new and continued uploads.
 *
 * @param onUploadFinished drops the cached copies of an object, by bucket and path.
 */
internal class InvalidatingResumableClient(
    private val delegate: ResumableClient,
    private val backgroundScope: CoroutineScope,
    private val operationGate: ClientOperationGate,
    private val onUploadFinished: suspend (bucketId: String, path: String) -> Unit,
) : ResumableClient {

    override suspend fun createOrContinueUpload(
        channel: suspend (offset: Long) -> ByteReadChannel,
        source: String,
        size: Long,
        path: String,
        options: UploadOptionBuilder.() -> Unit,
    ): ResumableUpload = operationGate.call {
        delegate.createOrContinueUpload(channel, source, size, path, options).also(::invalidateWhenFinished)
    }

    override suspend fun continuePreviousUploads(
        channelProducer: suspend (source: String, offset: Long) -> ByteReadChannel,
    ): List<Deferred<ResumableUpload>> = operationGate.call { delegate.continuePreviousUploads(channelProducer) }.onEach { pending ->
        backgroundScope.launch {
            val upload = try {
                pending.await()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The upload could not be resumed; the caller sees why through its own Deferred.
                return@launch
            }
            invalidateWhenFinished(upload)
        }
    }

    private fun invalidateWhenFinished(upload: ResumableUpload) {
        backgroundScope.launch {
            val finished = upload.stateFlow.first { it.isDone }
            onUploadFinished(finished.bucketId, finished.path)
        }
    }
}
