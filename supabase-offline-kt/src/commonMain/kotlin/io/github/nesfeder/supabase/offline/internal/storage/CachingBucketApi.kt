// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.DownloadOptionBuilder
import io.github.jan.supabase.storage.FileObject
import io.github.jan.supabase.storage.FileObjectV2
import io.github.jan.supabase.storage.FileUploadResponse
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
import io.github.nesfeder.supabase.offline.internal.postgrest.RequestSession
import io.github.nesfeder.supabase.offline.internal.postgrest.settledIdentityOf
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
 * the client's gate. `purgeCache` (supabase-kt 3.8) is not overridden, so this compiles on 3.6: on
 * 3.8 it reaches the delegate past the gate, and a build on 3.6 run on 3.8 fails the call.
 *
 * @param engineLogger messages at INFO and above name the bucket, never the object path.
 * @param backgroundScope the client's own scope, which closing the client cancels.
 * @param networkAccess the client's network switch: while it is off nothing is sent, a download is
 *   answered from the cache, and every other call throws `NetworkDisabledException`.
 * @param requestSession the session a request sent now goes out under. While it is unsettled the
 *   request carries the anon key, so Storage's "not found" may only mean the key cannot read it.
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
    private val requestSession: () -> RequestSession = { RequestSession.WithoutAuth },
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
            requestSession,
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
        if (!cachesDownloadsOf(path)) return@call null
        cache.entryFor(bucketId, path)?.let { entry -> cache.useCachedCopy(entry) }
    }

    override suspend fun ensureCachedFile(path: String, isPublic: Boolean): CachedFile =
        operationGate.cancellableCall(backgroundScope) { ensureCachedFileNow(path, isPublic) }

    /** [ensureCachedFile]'s body, run while the call holds the client's gate. */
    private suspend fun ensureCachedFileNow(path: String, isPublic: Boolean): CachedFile {
        require(isRequestedByItsOwnName(path)) {
            "This object path cannot be cached on the device: it holds '%', '?' or '#', which go " +
                "into the request's URL unencoded, so its request can reach another object"
        }
        require(isCacheableObjectPath(path)) {
            "This object path cannot be cached on the device: it has a leading '/', an empty, '.' " +
                "or '..' segment, a '\\' or a control character"
        }
        val entry =
            cache.entryFor(bucketId, path)
                ?: throw IllegalArgumentException(
                    "This object cannot be cached on the device: its bucket id or its path cannot " +
                        "be mapped onto a file"
                )
        var supersededBefore = false
        while (true) {
            cache.useCachedCopy(entry)?.let {
                return it
            }
            // One download per cold object: a concurrent call for it waits for this one
            val outcome =
                cache.downloadsInFlight.joinOrStart(entry.key, backgroundScope) {
                    downloadIntoCache(entry, path, isPublic)
                }
            when (outcome) {
                CommitOutcome.COMMITTED ->
                    return cache.useCachedCopy(entry)
                        ?: throw IOException(
                            "The downloaded file left the cache before it could be returned"
                        )
                CommitOutcome.CLEARED ->
                    throw IllegalStateException(
                        "Local data was cleared while this file was downloading"
                    )
                CommitOutcome.SUPERSEDED -> {
                    // The object was written while it downloaded: a download started now fetches
                    // the new one. Once only, so writes landing in a row do not keep this call
                    // going
                    if (supersededBefore) {
                        throw IllegalStateException(
                            "The object was written while this file was downloading, twice in a row"
                        )
                    }
                    supersededBefore = true
                }
                CommitOutcome.FAILED ->
                    throw IOException("The file cache could not store the download")
            }
        }
    }

    /**
     * Downloads [path] into [entry]'s file and commits it, as the download every concurrent
     * [ensureCachedFile] of [path] shares. A request that fetched only part of the object, by a
     * header set on this bucket, is refused: the fragment cannot be the cached copy.
     *
     * @return what the commit said.
     */
    private suspend fun downloadIntoCache(
        entry: FileCacheEntry,
        path: String,
        isPublic: Boolean,
    ): CommitOutcome {
        val sentUnsettled = isSessionUnsettled()
        val file = OwnedTemporaryFile()
        val start = cache.beginDownload()
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
                    if (
                        failure.indicatesServerObjectMissing() &&
                            !sentUnsettled &&
                            !isSessionUnsettled()
                    )
                        cache.invalidateObject(bucketId, path)
                    throw failure
                }
            val written =
                when (download) {
                    is StreamedDownload.Written -> download
                    is StreamedDownload.CacheRefused ->
                        throw IOException(
                            "The file cache could not store the download",
                            download.cause,
                        )
                    StreamedDownload.StreamedThrough,
                    StreamedDownload.Served,
                    StreamedDownload.Uncacheable ->
                        error("A known cache entry was reported uncacheable")
                }
            if (written.fetchedPart) {
                throw IllegalStateException(
                    "A header set on this bucket made the request fetch only part of the object, " +
                        "which cannot be the cached copy"
                )
            }
            val outcome = cache.commit(written.temporary, start)
            if (outcome == CommitOutcome.COMMITTED) file.temporary = null
            return outcome
        } finally {
            file.discard()
            cache.endDownload(start)
        }
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
     * logged, never thrown. An [ensureCachedFile] download of the same entry in flight is waited
     * for instead, and its file read.
     */
    private suspend fun downloadBytes(
        path: String,
        fetch:
            suspend (
                wrap: (DownloadOptionBuilder.() -> Unit) -> (DownloadOptionBuilder.() -> Unit)
            ) -> ByteArray,
    ): ByteArray {
        requireUrlFor(path)
        engineLogger.debug { "Storage download $bucketId/$path attempting network" }
        if (!cachesDownloadsOf(path)) {
            networkAccess.ensureEnabled()
            return fetch { options -> options }
        }
        val request = ProbedRequest(path)
        val probe = request.probe
        val start = cache.beginDownload()
        try {
            val sentUnsettled = isSessionUnsettled()
            val bytes =
                try {
                    fetchJoiningDownloadInFlight(
                        request,
                        readCached = { entry -> cache.readBytes(entry) },
                    ) {
                        fetch(probe::wrap)
                    }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    return recover(path, probe, failure, sentUnsettled) { entry ->
                        cache.readBytes(entry)
                    }
                }
            if (!request.servedFromDownloadInFlight) {
                probe.entryToStore(cache, bucketId, path)?.let { entry ->
                    cache.write(entry, bytes, start)
                }
            }
            return bytes
        } finally {
            cache.endDownload(start)
        }
    }

    /**
     * Thrown by a [ProbedRequest]'s override to stop its request before it is sent: an
     * [ensureCachedFile] download of the very entry it would fetch is in flight, and the call waits
     * for that one instead of fetching the object a second time.
     */
    private class DownloadInFlight : Exception("A download of this entry is in flight")

    /**
     * The probe of one bytes or channel download of [path]. Its request override, once the request
     * is recorded, refuses the request while network access is off and, the first time, stops it
     * when an [ensureCachedFile] download of the entry it would fetch is in flight: the call then
     * waits for that download ([serveFromDownloadInFlight]) rather than fetch the object twice.
     */
    private inner class ProbedRequest(private val path: String) {

        /** The entry whose download in flight the stopped request waits for; `null` until then. */
        private val inFlight = AtomicReference<FileCacheEntry?>(null)

        /** Whether the next request may be stopped to wait for a download in flight: once only. */
        private val mayJoin = AtomicBoolean(true)

        /** Whether the call was answered from the download it waited for. */
        var servedFromDownloadInFlight: Boolean = false
            private set

        val probe =
            DownloadRequestProbe(renderImagePath) {
                networkAccess.ensureEnabled()
                val entry =
                    if (mayJoin.exchange(false)) entryToStore(cache, bucketId, path) else null
                if (entry != null && cache.downloadsInFlight.isInFlight(entry.key)) {
                    inFlight.store(entry)
                    throw DownloadInFlight()
                }
            }

        /** Whether the request was stopped to wait for a download in flight. */
        val stoppedForDownloadInFlight: Boolean
            get() = inFlight.load() != null

        /**
         * Waits for the download in flight the request was stopped for, and answers from the cached
         * copy it committed with [read].
         *
         * @return what [read] gave, or `null` when the download did not commit or its copy is gone
         *   already: the call fetches the object itself then.
         */
        suspend fun <T : Any> serveFromDownloadInFlight(read: suspend (FileCacheEntry) -> T?): T? {
            val entry = inFlight.exchange(null) ?: return null
            engineLogger.debug { "Storage download $bucketId/$path waiting for the one in flight" }
            if (cache.downloadsInFlight.join(entry.key) != CommitOutcome.COMMITTED) return null
            return read(entry)?.also { servedFromDownloadInFlight = true }
        }
    }

    /**
     * Runs [fetch], a download through [request]'s probe. When the probe stopped the request for an
     * [ensureCachedFile] download of the same entry in flight, waits for that download and answers
     * from its cached copy with [readCached]; when that download did not commit, or its copy is
     * gone already, fetches after all. What the download in flight threw is this call's failure
     * too.
     */
    private suspend fun <T : Any> fetchJoiningDownloadInFlight(
        request: ProbedRequest,
        readCached: suspend (FileCacheEntry) -> T?,
        fetch: suspend () -> T,
    ): T {
        try {
            return fetch()
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (!request.stoppedForDownloadInFlight) throw failure
        }
        return request.serveFromDownloadInFlight(readCached) ?: fetch()
    }

    /**
     * A download into the caller's channel, streamed through a temporary file so it is never held
     * in memory. [channel] gets nothing until the download is complete, then is closed, as
     * supabase-kt does. A response that is not a success throws as the bytes download does, and
     * none of it reaches [channel] or the cache. An [ensureCachedFile] download of the same entry
     * in flight is waited for instead, and its file streamed out.
     */
    private suspend fun downloadToChannel(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit,
        uncheckedFetch:
            suspend (target: ByteWriteChannel, options: DownloadOptionBuilder.() -> Unit) -> Unit,
    ) {
        val fetch:
            suspend (
                target: ByteWriteChannel,
                options: DownloadOptionBuilder.() -> Unit,
            ) -> CheckedDownload =
            { target, wrappedOptions ->
                checkingDownloadStatus { uncheckedFetch(target, wrappedOptions) }
            }
        requireUrlFor(path)
        engineLogger.debug { "Storage download $bucketId/$path attempting network" }
        if (!cachesDownloadsOf(path)) {
            networkAccess.ensureEnabled()
            fetch(channel, options)
            return
        }
        val request = ProbedRequest(path)
        val probe = request.probe
        val sentUnsettled = isSessionUnsettled()
        val file = OwnedTemporaryFile()
        val start = cache.beginDownload()
        try {
            val download =
                try {
                    fetchJoiningDownloadInFlight(
                        request,
                        readCached = { entry ->
                            if (cache.copyTo(entry, channel)) StreamedDownload.Served else null
                        },
                    ) {
                        downloadToTemporaryFile(
                            file,
                            entryProvider = { probe.entryToStore(cache, bucketId, path) },
                            passThrough = channel,
                        ) { target ->
                            fetch(target, probe.wrap(options))
                        }
                    }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    recover(path, probe, failure, sentUnsettled) { entry ->
                        if (cache.copyTo(entry, channel)) Unit else null
                    }
                    channel.flushAndClose()
                    return
                }
            when (download) {
                is StreamedDownload.Written -> commitAndStream(file, download, start, channel)
                is StreamedDownload.CacheRefused -> {
                    // The disk refused the copy (logged): rather than fail a good download, fetch
                    // it again straight into the caller's channel.
                    fetch(channel, options)
                    return
                }
                // Part of the object, or none: delivered as it came, and never cached
                StreamedDownload.StreamedThrough,
                // The whole object, from the copy the download in flight committed
                StreamedDownload.Served -> Unit
                StreamedDownload.Uncacheable -> error("A channel download had no channel to fill")
            }
            channel.flushAndClose()
        } finally {
            file.discard()
            cache.endDownload(start)
        }
    }

    /**
     * Commits a finished download, then streams the cache's copy (or the uncommitted file) out. A
     * download that fetched only part of the object, by a header set on this bucket, is streamed
     * out as it is and never committed.
     */
    private suspend fun commitAndStream(
        file: OwnedTemporaryFile,
        download: StreamedDownload.Written,
        start: DownloadStart,
        channel: ByteWriteChannel,
    ) {
        val temporary = download.temporary
        if (download.fetchedPart) {
            cache.copyFile(temporary.path, channel)
            return
        }
        when (cache.commit(temporary, start)) {
            CommitOutcome.COMMITTED -> {
                file.temporary = null
                if (!cache.copyTo(temporary.entry, channel)) {
                    throw IOException("The downloaded file left the cache before it could be read")
                }
            }
            // The call's own clean-up discards the file once it has been read
            CommitOutcome.CLEARED,
            CommitOutcome.SUPERSEDED,
            CommitOutcome.FAILED -> cache.copyFile(temporary.path, channel)
        }
    }

    /**
     * What a failed download returns instead of its failure. When the server says the object is
     * gone, nothing: its copies are dropped and the failure rethrown, unless the session was
     * unsettled ([sentUnsettled], or now), when the anon key may only lack access. Otherwise the
     * cached copy of the variant asked for, or of the original when the request was never built.
     * Offline, which is expected, is logged at INFO; any other failure at WARNING.
     */
    private suspend fun <T : Any> recover(
        path: String,
        probe: DownloadRequestProbe,
        failure: Exception,
        sentUnsettled: Boolean,
        readCached: suspend (FileCacheEntry) -> T?,
    ): T {
        if (failure.indicatesServerObjectMissing() && !sentUnsettled && !isSessionUnsettled()) {
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
        /**
         * Every byte is in [temporary]. With [fetchedPart], the request fetched only part of the
         * object, or none of it: the file is the caller's, never the cached copy.
         */
        class Written(val temporary: TemporaryDownload, val fetchedPart: Boolean) : StreamedDownload

        /** The disk refused the copy; the download was abandoned. */
        class CacheRefused(val cause: Throwable?) : StreamedDownload

        /** Every byte went straight to the caller's channel: no whole variant to cache. */
        data object StreamedThrough : StreamedDownload

        /**
         * Every byte went to the caller's channel from the cached copy an [ensureCachedFile]
         * download in flight committed; nothing was fetched.
         */
        data object Served : StreamedDownload

        /** The download finished without a cache entry to hold it, nor a channel to fill. */
        data object Uncacheable : StreamedDownload
    }

    /**
     * Streams [fetch] into [file] for the entry [entryProvider] names, once bytes arrive; when it
     * names none, a request for part of the object, straight into [passThrough] when there is one.
     * A failed or canceled download leaves the file to [file]'s owner; a disk that refuses it
     * deletes it at once, cancels the download and reports [StreamedDownload.CacheRefused].
     */
    private suspend fun downloadToTemporaryFile(
        file: OwnedTemporaryFile,
        entryProvider: () -> FileCacheEntry?,
        passThrough: ByteWriteChannel? = null,
        fetch: suspend (ByteWriteChannel) -> CheckedDownload,
    ): StreamedDownload = coroutineScope {
        val pipe = ByteChannel()
        val producer = async {
            try {
                fetch(pipe).also { pipe.flushAndClose() }
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
                    ?: return@coroutineScope when {
                        entryProvider() != null ->
                            abandon(file, producer, pipe, cause = null, uncacheable = false)
                        passThrough != null ->
                            streamThrough(pipe, buffer, read, passThrough, producer)
                        else -> abandon(file, producer, pipe, cause = null, uncacheable = true)
                    }
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
        val checked = producer.await()
        val target =
            file.temporary
                ?: file.open(entryProvider())
                ?: return@coroutineScope when {
                    entryProvider() != null -> StreamedDownload.CacheRefused(null)
                    passThrough != null -> StreamedDownload.StreamedThrough
                    else -> StreamedDownload.Uncacheable
                }
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
        StreamedDownload.Written(target, fetchedPart = checked.fetchedPart)
    }

    /**
     * Hands [buffer]'s first [read] bytes, then the rest of [pipe], straight to [channel]: the
     * download has no cache entry to go through.
     */
    private suspend fun streamThrough(
        pipe: ByteReadChannel,
        buffer: ByteArray,
        read: Int,
        channel: ByteWriteChannel,
        producer: Deferred<CheckedDownload>,
    ): StreamedDownload {
        var count = read
        while (count >= 0) {
            if (count > 0) {
                channel.writeFully(buffer, 0, count)
                channel.flush()
            }
            count = readOrRethrow(pipe, buffer) { producer.await() }
        }
        producer.await()
        return StreamedDownload.StreamedThrough
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
         * Opens the file for [entry] and holds it; the cache hands it back even to a canceled
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
        producer: Deferred<CheckedDownload>,
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
            requireUrlFor(path)
            networkAccess.ensureEnabled()
            delegate.createSignedUploadUrl(path, upsert)
        }

    override suspend fun createSignedUrl(
        path: String,
        expiresIn: Duration,
        builder: SignedUrlBuilder.() -> Unit,
    ): String =
        operationGate.cancellableCall(backgroundScope) {
            requireUrlFor(path)
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
            requireUrlFor(path)
            networkAccess.ensureEnabled()
            delegate.info(path)
        }

    override suspend fun exists(path: String): Boolean =
        operationGate.cancellableCall(backgroundScope) {
            requireUrlFor(path)
            networkAccess.ensureEnabled()
            delegate.exists(path)
        }

    override suspend fun changePublicStatusTo(public: Boolean): Unit =
        operationGate.cancellableCall(backgroundScope) {
            networkAccess.ensureEnabled()
            delegate.changePublicStatusTo(public)
        }

    /**
     * Whether a download of [path] in this bucket is cached: both map onto a file, and the pinned
     * supabase-kt's request reaches the object the path names.
     */
    private fun cachesDownloadsOf(path: String): Boolean =
        isCacheableBucketId(bucketId) &&
            isCacheableObjectPath(path) &&
            isRequestedByItsOwnName(path)

    /** Refuses [path] when the request's URL cannot be built, which reads as a network failure. */
    private fun requireUrlFor(path: String) {
        require(canBuildTheUrlFor(path)) {
            "This object path holds a '%' that does not begin a percent-escape, so the request's " +
                "URL cannot be built"
        }
    }

    /** Whether a request sent now goes out on the anon key because the session has not settled. */
    private fun isSessionUnsettled(): Boolean = settledIdentityOf(requestSession()) == null

    /** An upload or update of [path] that drops its stale cached copies, stored or maybe stored. */
    private suspend fun <T> invalidatingAfter(path: String, write: suspend () -> T): T {
        requireUrlFor(path)
        return storageWrite(
            write = write,
            applied = { cache.invalidateObject(bucketId, path) },
            uncertain = { cache.invalidateObject(bucketId, path) },
        )
    }

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
