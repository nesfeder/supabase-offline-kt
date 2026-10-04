// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.DownloadOptionBuilder
import io.github.jan.supabase.storage.FileObject
import io.github.jan.supabase.storage.FileObjectV2
import io.github.jan.supabase.storage.FileUploadResponse
import io.github.jan.supabase.storage.PurgeCacheOptions
import io.github.jan.supabase.storage.SignedUrl
import io.github.jan.supabase.storage.SignedUrlBuilder
import io.github.jan.supabase.storage.SignedUrlsBuilder
import io.github.jan.supabase.storage.StorageListFilter
import io.github.jan.supabase.storage.UploadData
import io.github.jan.supabase.storage.UploadOptionBuilder
import io.github.jan.supabase.storage.UploadSignedUrl
import io.github.jan.supabase.storage.resumable.ResumableClient
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.internal.ClientOperationGate
import io.github.nesfeder.supabase.offline.internal.httpStatusOrNull
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.IOException

/** A literal HTTP 404, which also marks the object missing. */
private const val HTTP_NOT_FOUND = 404

/** Size of the buffer a streamed download moves bytes through. */
private const val DOWNLOAD_BUFFER_BYTES: Int = 64 * 1024

/**
 * The offline client's [OfflineBucketApi]: supabase-kt's bucket, with downloads cached in the
 * client's [StorageFileCache]. Every member that can change the server's copy of a path is
 * overridden, its convenience overloads included, so stale copies are dropped; every member passes
 * the client's gate.
 *
 * @param engineLogger messages at INFO and above name the bucket, never the object path.
 * @param backgroundScope the client's own scope, which closing the client cancels.
 * @param networkAccess the client's network switch: while it is off nothing is sent, a download is
 *   answered from the cache, and every other call throws `NetworkDisabledException`.
 */
@OptIn(OfflineInternal::class)
internal class CachingBucketApi(
    private val delegate: BucketApi,
    private val cache: StorageFileCache,
    private val engineLogger: EngineLogger,
    private val backgroundScope: CoroutineScope,
    private val operationGate: ClientOperationGate = ClientOperationGate(),
    private val renderImagePath: String = DEFAULT_RENDER_IMAGE_PATH,
    /** Where a streamed download writes its file. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val networkAccess: NetworkAccess = NetworkAccess(),
) : OfflineBucketApi, BucketApi by delegate {

    override fun setHeader(name: String, value: String): OfflineBucketApi =
        CachingBucketApi(
            delegate.setHeader(name, value),
            cache,
            engineLogger,
            backgroundScope,
            operationGate,
            renderImagePath,
            ioDispatcher,
            networkAccess,
        )

    override val resumable: ResumableClient by lazy {
        InvalidatingResumableClient(
            delegate = delegate.resumable,
            backgroundScope = backgroundScope,
            operationGate = operationGate,
            onUploadFinished = { bucketId, path -> cache.invalidateObject(bucketId, path) },
            networkAccess = networkAccess,
        )
    }

    override suspend fun cachedFile(path: String): CachedFile? = operationGate.call {
        cache.entryFor(bucketId, path)?.let { entry -> cache.useCachedCopy(entry) }
    }

    override suspend fun ensureCachedFile(path: String, isPublic: Boolean): CachedFile =
        operationGate.cancellableCall(backgroundScope) { ensureCachedFileNow(path, isPublic) }

    /** [ensureCachedFile]'s body, run while the call holds the client's gate. */
    private suspend fun ensureCachedFileNow(path: String, isPublic: Boolean): CachedFile {
        val entry =
            cache.entryFor(bucketId, path)
                ?: throw IllegalArgumentException(
                    "This object path cannot be cached on the device: it has a leading '/', an empty, '.' " +
                        "or '..' segment, a '\\' or a control character"
                )
        cache.useCachedCopy(entry)?.let {
            return it
        }
        val generation = cache.currentGeneration()
        val file = OwnedTemporaryFile()
        try {
            val download =
                try {
                    downloadToTemporaryFile(file, entryProvider = { entry }) { target ->
                        networkAccess.ensureEnabled()
                        checkingDownloadStatus {
                            if (isPublic) delegate.downloadPublic(path, target) {}
                            else delegate.downloadAuthenticated(path, target) {}
                        }
                    }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (failure.indicatesServerObjectMissing())
                        cache.invalidateObject(bucketId, path)
                    throw failure
                }
            val temporary =
                when (download) {
                    is StreamedDownload.Written -> download.temporary
                    is StreamedDownload.CacheRefused ->
                        throw IOException(
                            "The file cache could not store the download",
                            download.cause,
                        )
                    StreamedDownload.Uncacheable ->
                        error("A known cache entry was reported uncacheable")
                }
            when (cache.commit(temporary, generation)) {
                CommitOutcome.COMMITTED -> file.temporary = null
                CommitOutcome.CLEARED ->
                    throw IllegalStateException(
                        "Local data was cleared while this file was downloading"
                    )
                CommitOutcome.FAILED ->
                    throw IOException("The file cache could not store the download")
            }
        } finally {
            file.discard()
        }
        return cache.useCachedCopy(entry)
            ?: throw IOException("The downloaded file left the cache before it could be returned")
    }

    override suspend fun downloadAuthenticated(
        path: String,
        options: DownloadOptionBuilder.() -> Unit,
    ): ByteArray =
        operationGate.cancellableCall(backgroundScope) {
            downloadBytes(path) { wrapped ->
                delegate.downloadAuthenticated(path, wrapped(options))
            }
        }

    override suspend fun downloadPublic(
        path: String,
        options: DownloadOptionBuilder.() -> Unit,
    ): ByteArray =
        operationGate.cancellableCall(backgroundScope) {
            downloadBytes(path) { wrapped -> delegate.downloadPublic(path, wrapped(options)) }
        }

    override suspend fun downloadAuthenticated(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit,
    ): Unit =
        operationGate.cancellableCall(backgroundScope) {
            downloadToChannel(path, channel, options) { target, wrappedOptions ->
                delegate.downloadAuthenticated(path, target, wrappedOptions)
            }
        }

    override suspend fun downloadPublic(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit,
    ): Unit =
        operationGate.cancellableCall(backgroundScope) {
            downloadToChannel(path, channel, options) { target, wrappedOptions ->
                delegate.downloadPublic(path, target, wrappedOptions)
            }
        }

    /**
     * A download returning its bytes. They are in memory anyway — that is what the caller asked for
     * — so the cache copy is written from them after the download, and a failure to write it is
     * logged, never thrown.
     */
    private suspend fun downloadBytes(
        path: String,
        fetch:
            suspend (
                wrap: (DownloadOptionBuilder.() -> Unit) -> (DownloadOptionBuilder.() -> Unit)
            ) -> ByteArray,
    ): ByteArray {
        engineLogger.debug { "Storage download $bucketId/$path attempting network" }
        val probe = DownloadRequestProbe(renderImagePath, beforeSend = networkAccess::ensureEnabled)
        val generation = cache.currentGeneration()
        val bytes =
            try {
                fetch(probe::wrap)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                return recover(path, probe, failure) { entry -> cache.readBytes(entry) }
            }
        probe.entryToStore(cache, bucketId, path)?.let { entry ->
            cache.write(entry, bytes, generation)
        }
        return bytes
    }

    /**
     * A download into the caller's channel, streamed through a temporary file so it is never held
     * in memory. [channel] gets nothing until the download is complete, then is closed, as
     * supabase-kt does. A response that is not a success throws as the bytes download does, and
     * none of it reaches [channel] or the cache.
     */
    private suspend fun downloadToChannel(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit,
        uncheckedFetch:
            suspend (target: ByteWriteChannel, options: DownloadOptionBuilder.() -> Unit) -> Unit,
    ) {
        val fetch:
            suspend (target: ByteWriteChannel, options: DownloadOptionBuilder.() -> Unit) -> Unit =
            { target, wrappedOptions ->
                checkingDownloadStatus { uncheckedFetch(target, wrappedOptions) }
            }
        engineLogger.debug { "Storage download $bucketId/$path attempting network" }
        if (!isCacheableObjectPath(path) || !isCacheableBucketId(bucketId)) {
            networkAccess.ensureEnabled()
            fetch(channel, options)
            return
        }
        val probe = DownloadRequestProbe(renderImagePath, beforeSend = networkAccess::ensureEnabled)
        val generation = cache.currentGeneration()
        val file = OwnedTemporaryFile()
        try {
            val download =
                try {
                    downloadToTemporaryFile(
                        file,
                        entryProvider = { probe.entryToStore(cache, bucketId, path) },
                    ) { target ->
                        fetch(target, probe.wrap(options))
                    }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    recover(path, probe, failure) { entry ->
                        if (cache.copyTo(entry, channel)) Unit else null
                    }
                    channel.flushAndClose()
                    return
                }
            when (download) {
                is StreamedDownload.Written ->
                    commitAndStream(file, download.temporary, generation, channel)
                is StreamedDownload.CacheRefused,
                StreamedDownload.Uncacheable -> {
                    // The disk refused the copy (logged), or the variant is unknown: rather than
                    // fail a good download, fetch it again straight into the caller's channel.
                    fetch(channel, options)
                    return
                }
            }
            channel.flushAndClose()
        } finally {
            file.discard()
        }
    }

    /** Commits a finished download, then streams the cache's copy (or the uncommitted file) out. */
    private suspend fun commitAndStream(
        file: OwnedTemporaryFile,
        temporary: TemporaryDownload,
        generation: Long,
        channel: ByteWriteChannel,
    ) {
        when (cache.commit(temporary, generation)) {
            CommitOutcome.COMMITTED -> {
                file.temporary = null
                if (!cache.copyTo(temporary.entry, channel)) {
                    throw IOException("The downloaded file left the cache before it could be read")
                }
            }
            // The call's own clean-up discards the file once it has been read
            CommitOutcome.CLEARED,
            CommitOutcome.FAILED -> cache.copyFile(temporary.path, channel)
        }
    }

    /**
     * What a failed download returns instead of its failure. When the server says the object is
     * gone, nothing: its copies are dropped and the failure rethrown. Otherwise the cached copy of
     * the variant asked for, or of the original when the request was never built. Offline, which
     * is expected, is logged at INFO; any other failure at WARNING.
     */
    private suspend fun <T : Any> recover(
        path: String,
        probe: DownloadRequestProbe,
        failure: Exception,
        readCached: suspend (FileCacheEntry) -> T?,
    ): T {
        if (failure.indicatesServerObjectMissing()) {
            cache.invalidateObject(bucketId, path)
            engineLogger.warning {
                "Storage object in $bucketId no longer exists; dropped its cached copy"
            }
            throw failure
        }
        val level =
            if (failure.isNetworkOrConnectivityFailure()) EngineLogLevel.INFO
            else EngineLogLevel.WARNING
        val cached = probe.entryToServe(cache, bucketId, path)?.let { entry -> readCached(entry) }
        if (cached != null) {
            engineLogger.log(level) {
                "Storage download from $bucketId failed; serving the cached copy"
            }
            return cached
        }
        engineLogger.log(level) { "Storage download from $bucketId failed and nothing is cached" }
        throw failure
    }

    /** How a streamed download into a temporary file ended, when it did not throw. */
    private sealed interface StreamedDownload {
        /** Every byte is in [temporary]. */
        class Written(val temporary: TemporaryDownload) : StreamedDownload

        /** The disk refused the copy; the download was abandoned. */
        class CacheRefused(val cause: Throwable?) : StreamedDownload

        /** The download finished without a cache entry to hold it. */
        data object Uncacheable : StreamedDownload
    }

    /**
     * Streams [fetch] into [file] for the entry [entryProvider] names, once bytes arrive. A failed
     * or cancelled download leaves the file to [file]'s owner; a disk that refuses it deletes it at
     * once, cancels the download and reports [StreamedDownload.CacheRefused].
     */
    private suspend fun downloadToTemporaryFile(
        file: OwnedTemporaryFile,
        entryProvider: () -> FileCacheEntry?,
        fetch: suspend (ByteWriteChannel) -> Unit,
    ): StreamedDownload = coroutineScope {
        val pipe = ByteChannel()
        val producer = async {
            try {
                fetch(pipe)
                pipe.flushAndClose()
            } catch (failure: Throwable) {
                pipe.cancel(failure)
                throw failure
            }
        }
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        while (true) {
            val read = readOrRethrow(pipe, buffer) { producer.await() }
            if (read < 0) break
            if (read == 0) continue
            val target =
                file.temporary
                    ?: file.open(entryProvider())
                    ?: return@coroutineScope abandon(
                        file,
                        producer,
                        pipe,
                        cause = null,
                        uncacheable = entryProvider() == null,
                    )
            try {
                withContext(ioDispatcher) { target.sink.write(buffer, 0, read) }
            } catch (failure: IOException) {
                engineLogger.warning {
                    "Storage cache could not write a download (${failure::class.simpleName}); not caching"
                }
                return@coroutineScope abandon(
                    file,
                    producer,
                    pipe,
                    cause = failure,
                    uncacheable = false,
                )
            }
        }
        producer.await()
        val target =
            file.temporary
                ?: file.open(entryProvider())
                ?: return@coroutineScope if (entryProvider() == null) StreamedDownload.Uncacheable
                else StreamedDownload.CacheRefused(null)
        // The sink buffers; a full disk can surface only here. Past this point the temporary
        // file holds every byte, so it can be served even if the rename that follows fails.
        try {
            withContext(ioDispatcher) { target.sink.flush() }
        } catch (failure: IOException) {
            engineLogger.warning {
                "Storage cache could not write a download (${failure::class.simpleName}); not caching"
            }
            file.discard()
            return@coroutineScope StreamedDownload.CacheRefused(failure)
        }
        StreamedDownload.Written(target)
    }

    /**
     * The temporary file one download writes. The call that opened it discards it on its way out,
     * however it ends, unless the cache has committed it; a cancellation can land anywhere in
     * between.
     */
    private inner class OwnedTemporaryFile {
        /** The file, or `null` before it is opened and once it is committed or discarded. */
        var temporary: TemporaryDownload? = null

        /**
         * Opens the file for [entry] and holds it; the cache hands it back even to a cancelled
         * caller.
         */
        suspend fun open(entry: FileCacheEntry?): TemporaryDownload? =
            entry?.let { cache.openTemporaryDownload(it) }?.also { opened -> temporary = opened }

        /** Deletes the file if it is still held. */
        suspend fun discard() {
            val held = temporary ?: return
            temporary = null
            cache.discard(held)
        }
    }

    /** Stops the download, deletes its file and says why nothing was cached. */
    private suspend fun abandon(
        file: OwnedTemporaryFile,
        producer: Deferred<Unit>,
        pipe: ByteChannel,
        cause: Throwable?,
        uncacheable: Boolean,
    ): StreamedDownload {
        producer.cancel()
        pipe.cancel(null)
        file.discard()
        return if (uncacheable) StreamedDownload.Uncacheable
        else StreamedDownload.CacheRefused(cause)
    }

    /**
     * Reads the next chunk of [pipe]. When the producer failed, the pipe fails too; this rethrows
     * the producer's own failure rather than the pipe's wrapper, so callers see what supabase-kt
     * threw.
     */
    private suspend fun readOrRethrow(
        pipe: ByteReadChannel,
        buffer: ByteArray,
        producerFailure: suspend () -> Unit,
    ): Int =
        try {
            pipe.readAvailable(buffer, 0, buffer.size)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            producerFailure()
            throw failure
        }

    // Writes are never queued: each drops or moves the cached copies it may change, and close()
    // stops it.

    override suspend fun upload(
        path: String,
        data: UploadData,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse = invalidatingAfter(path) { delegate.upload(path, data, options) }

    override suspend fun upload(
        path: String,
        data: ByteArray,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse = invalidatingAfter(path) { delegate.upload(path, data, options) }

    override suspend fun update(
        path: String,
        data: UploadData,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse = invalidatingAfter(path) { delegate.update(path, data, options) }

    override suspend fun update(
        path: String,
        data: ByteArray,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse = invalidatingAfter(path) { delegate.update(path, data, options) }

    override suspend fun uploadToSignedUrl(
        path: String,
        token: String,
        data: UploadData,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse =
        invalidatingAfter(path) { delegate.uploadToSignedUrl(path, token, data, options) }

    override suspend fun uploadToSignedUrl(
        path: String,
        token: String,
        data: ByteArray,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse =
        invalidatingAfter(path) { delegate.uploadToSignedUrl(path, token, data, options) }

    override suspend fun delete(paths: Collection<String>): Unit =
        storageWrite(
            write = { delegate.delete(paths) },
            applied = { paths.forEach { path -> cache.invalidateObject(bucketId, path) } },
            uncertain = { paths.forEach { path -> cache.invalidateObject(bucketId, path) } },
        )

    override suspend fun delete(vararg paths: String) {
        delete(paths.toList())
    }

    override suspend fun move(from: String, to: String, destinationBucket: String?): Unit =
        storageWrite(
            write = { delegate.move(from, to, destinationBucket) },
            applied = {
                cache.moveObject(
                    fromBucket = bucketId,
                    from = from,
                    toBucket = destinationBucket ?: bucketId,
                    to = to,
                )
            },
            uncertain = {
                cache.invalidateObject(bucketId, from)
                cache.invalidateObject(destinationBucket ?: bucketId, to)
            },
        )

    // The copy's bytes are not on the device, and a cached copy of the destination is stale
    // whether the copy went through or not
    override suspend fun copy(from: String, to: String, destinationBucket: String?): Unit =
        storageWrite(
            write = { delegate.copy(from, to, destinationBucket) },
            applied = { cache.invalidateObject(destinationBucket ?: bucketId, to) },
            uncertain = { cache.invalidateObject(destinationBucket ?: bucketId, to) },
        )

    // Passed through: refused once the client is closed, and stopped by close().

    override suspend fun createSignedUploadUrl(path: String, upsert: Boolean): UploadSignedUrl =
        operationGate.cancellableCall(backgroundScope) {
            networkAccess.ensureEnabled()
            delegate.createSignedUploadUrl(path, upsert)
        }

    override suspend fun createSignedUrl(
        path: String,
        expiresIn: Duration,
        builder: SignedUrlBuilder.() -> Unit,
    ): String =
        operationGate.cancellableCall(backgroundScope) {
            networkAccess.ensureEnabled()
            delegate.createSignedUrl(path, expiresIn, builder)
        }

    override suspend fun createSignedUrls(
        expiresIn: Duration,
        paths: Collection<String>,
        builder: SignedUrlsBuilder.() -> Unit,
    ): List<SignedUrl> =
        operationGate.cancellableCall(backgroundScope) {
            networkAccess.ensureEnabled()
            delegate.createSignedUrls(expiresIn, paths, builder)
        }

    override suspend fun createSignedUrls(
        expiresIn: Duration,
        vararg paths: String,
        builder: SignedUrlsBuilder.() -> Unit,
    ): List<SignedUrl> = createSignedUrls(expiresIn, paths.toList(), builder)

    override suspend fun list(
        prefix: String,
        filter: StorageListFilter.Files.() -> Unit,
    ): List<FileObject> =
        operationGate.cancellableCall(backgroundScope) {
            networkAccess.ensureEnabled()
            delegate.list(prefix, filter)
        }

    override suspend fun info(path: String): FileObjectV2 =
        operationGate.cancellableCall(backgroundScope) {
            networkAccess.ensureEnabled()
            delegate.info(path)
        }

    override suspend fun exists(path: String): Boolean =
        operationGate.cancellableCall(backgroundScope) {
            networkAccess.ensureEnabled()
            delegate.exists(path)
        }

    override suspend fun changePublicStatusTo(public: Boolean): Unit =
        operationGate.cancellableCall(backgroundScope) {
            networkAccess.ensureEnabled()
            delegate.changePublicStatusTo(public)
        }

    override suspend fun purgeCache(path: String, options: PurgeCacheOptions.() -> Unit): Unit =
        operationGate.cancellableCall(backgroundScope) {
            networkAccess.ensureEnabled()
            delegate.purgeCache(path, options)
        }

    /** An upload or update of [path] that drops its stale cached copies, stored or maybe stored. */
    private suspend fun <T> invalidatingAfter(path: String, write: suspend () -> T): T =
        storageWrite(
            write = write,
            applied = { cache.invalidateObject(bucketId, path) },
            uncertain = { cache.invalidateObject(bucketId, path) },
        )

    /**
     * Runs a Storage write that close() may stop, never queued; [applied] brings the cache in line.
     * One that timed out or was stopped may have been applied anyway, so [uncertain] drops what it
     * touched; any other failure left the server, and the cache, as they were.
     */
    private suspend fun <T> storageWrite(
        write: suspend () -> T,
        applied: suspend () -> Unit,
        uncertain: suspend () -> Unit,
    ): T =
        operationGate.cancellableCall(backgroundScope) {
            // Nothing leaves the device while network access is off; the cache is left as it is
            networkAccess.ensureEnabled()
            val result =
                try {
                    write()
                } catch (failure: Exception) {
                    if (failure is CancellationException || failure.mayHaveReachedTheServer())
                        withContext(NonCancellable) { uncertain() }
                    throw failure
                }
            withContext(NonCancellable) { applied() }
            result
        }

    /** Whether this failure, or a cause, is a timeout, after which the write may have landed. */
    private fun Throwable.mayHaveReachedTheServer(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is HttpRequestTimeoutException || current is SocketTimeoutException)
                return true
            current = current.cause
        }
        return false
    }

    /**
     * Whether this failure, or a cause, says the object is gone. That is a [NotFoundRestException]
     * (Storage reports one on an HTTP 400), or any [RestException] or response with a literal 404.
     */
    private fun Throwable.indicatesServerObjectMissing(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is NotFoundRestException) return true
            if (current is RestException && current.statusCode == HTTP_NOT_FOUND) return true
            current = current.cause
        }
        return httpStatusOrNull() == HTTP_NOT_FOUND
    }
}
