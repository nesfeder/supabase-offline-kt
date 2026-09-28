package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.DownloadOptionBuilder
import io.github.jan.supabase.storage.FileUploadResponse
import io.github.jan.supabase.storage.UploadData
import io.github.jan.supabase.storage.UploadOptionBuilder
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.internal.util.httpStatusOrNull
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
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

/** HTTP status returned by the server for an object that no longer exists. */
private const val HTTP_NOT_FOUND = 404

/** Size of the buffer a streamed download moves bytes through. */
private const val DOWNLOAD_BUFFER_BYTES: Int = 64 * 1024

/**
 * The offline client's [OfflineBucketApi]: every [BucketApi] operation forwarded to supabase-kt,
 * with downloads cached through the client's [StorageFileCache].
 *
 * Every entry point that can change what the server holds at a path is overridden — including the
 * interface's own convenience overloads (`upload(ByteArray)`, `update(ByteArray)`,
 * `uploadToSignedUrl(ByteArray)`, `delete(vararg)`), which `BucketApi by delegate` would otherwise
 * forward straight to supabase-kt past the cache. [setHeader] returns another offline bucket, for
 * the same reason.
 *
 * @param delegate the supabase-kt bucket.
 * @param cache the client's file cache.
 * @param sdkLogger the SDK logger. Messages at INFO and above name the bucket, never the object path.
 */
internal class OfflineBucketApiImpl(
    private val delegate: BucketApi,
    private val cache: StorageFileCache,
    private val sdkLogger: SdkLogger,
) : OfflineBucketApi, BucketApi by delegate {

    override fun setHeader(name: String, value: String): BucketApi =
        OfflineBucketApiImpl(delegate.setHeader(name, value), cache, sdkLogger)

    // ─────────────────────────────── Cached files ───────────────────────────────

    override suspend fun cachedFile(path: String): CachedFile? {
        val entry = cache.entryFor(bucketId, path) ?: return null
        return cache.lookup(entry)
    }

    override suspend fun ensureCachedFile(path: String, isPublic: Boolean): CachedFile {
        val entry = cache.entryFor(bucketId, path)
            ?: throw IllegalArgumentException(
                "This object path cannot be cached on the device: it has a leading '/', an empty, '.' " +
                    "or '..' segment, a '\\' or a control character",
            )
        cache.lookup(entry)?.let { return it }
        val generation = cache.currentGeneration()
        val download = try {
            downloadToTemporaryFile(entryProvider = { entry }) { target ->
                if (isPublic) delegate.downloadPublic(path, target) { } else delegate.downloadAuthenticated(path, target) { }
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (failure.indicatesServerObjectMissing()) cache.invalidateObject(bucketId, path)
            throw failure
        }
        val temporary = when (download) {
            is StreamedDownload.Written -> download.temporary
            is StreamedDownload.CacheRefused -> throw IOException("The file cache could not store the download", download.cause)
            StreamedDownload.Uncacheable -> error("A known cache entry was reported uncacheable")
        }
        when (cache.commit(temporary, generation)) {
            CommitOutcome.COMMITTED -> Unit
            CommitOutcome.CLEARED -> {
                cache.discard(temporary)
                throw IllegalStateException("Local data was cleared while this file was downloading")
            }
            CommitOutcome.FAILED -> {
                cache.discard(temporary)
                throw IOException("The file cache could not store the download")
            }
        }
        return cache.lookup(entry) ?: throw IOException("The downloaded file left the cache before it could be returned")
    }

    // ───────────────────────────────── Downloads ─────────────────────────────────

    override suspend fun downloadAuthenticated(path: String, options: DownloadOptionBuilder.() -> Unit): ByteArray =
        downloadBytes(path) { wrapped -> delegate.downloadAuthenticated(path, wrapped(options)) }

    override suspend fun downloadPublic(path: String, options: DownloadOptionBuilder.() -> Unit): ByteArray =
        downloadBytes(path) { wrapped -> delegate.downloadPublic(path, wrapped(options)) }

    override suspend fun downloadAuthenticated(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit,
    ) {
        downloadToChannel(path, channel, options) { target, wrappedOptions ->
            delegate.downloadAuthenticated(path, target, wrappedOptions)
        }
    }

    override suspend fun downloadPublic(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit,
    ) {
        downloadToChannel(path, channel, options) { target, wrappedOptions ->
            delegate.downloadPublic(path, target, wrappedOptions)
        }
    }

    /**
     * A download returning its bytes. They are in memory anyway — that is what the caller asked
     * for — so the cache copy is written from them after the download, and a failure to write it
     * is logged, never thrown.
     */
    private suspend fun downloadBytes(
        path: String,
        fetch: suspend (wrap: (DownloadOptionBuilder.() -> Unit) -> (DownloadOptionBuilder.() -> Unit)) -> ByteArray,
    ): ByteArray {
        sdkLogger.debug { "Storage download $bucketId/$path attempting network" }
        val probe = DownloadRequestProbe()
        val generation = cache.currentGeneration()
        val bytes = try {
            fetch(probe::wrap)
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            return recover(path, probe, failure) { entry -> cache.readBytes(entry) }
        }
        probe.entryIn(cache, bucketId, path)?.let { entry -> cache.store(entry, bytes, generation) }
        return bytes
    }

    /**
     * A download into the caller's channel. It streams to a temporary file first and never holds
     * the object in memory; the caller's channel receives nothing until the download is complete,
     * so a failure part-way can still fall back to the cached copy cleanly. Closes [channel] after
     * a successful download, as supabase-kt does.
     */
    private suspend fun downloadToChannel(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit,
        fetch: suspend (target: ByteWriteChannel, options: DownloadOptionBuilder.() -> Unit) -> Unit,
    ) {
        sdkLogger.debug { "Storage download $bucketId/$path attempting network" }
        if (!isCacheableObjectPath(path) || !isCacheableBucketId(bucketId)) {
            fetch(channel, options)
            return
        }
        val probe = DownloadRequestProbe()
        val generation = cache.currentGeneration()
        val download = try {
            downloadToTemporaryFile(entryProvider = { probe.entryIn(cache, bucketId, path) }) { target ->
                fetch(target, probe.wrap(options))
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            recover(path, probe, failure) { entry -> if (cache.copyTo(entry, channel)) Unit else null }
            channel.flushAndClose()
            return
        }
        when (download) {
            is StreamedDownload.Written -> deliver(download.temporary, generation, channel)
            is StreamedDownload.CacheRefused, StreamedDownload.Uncacheable -> {
                // The disk refused the copy (logged when it did), or the variant could not be told:
                // fetch again straight into the caller's channel rather than fail a good download.
                fetch(channel, options)
                return
            }
        }
        channel.flushAndClose()
    }

    /** Commits a finished download, then streams the cache's copy (or the uncommitted file) out. */
    private suspend fun deliver(temporary: TemporaryDownload, generation: Long, channel: ByteWriteChannel) {
        when (cache.commit(temporary, generation)) {
            CommitOutcome.COMMITTED -> {
                if (!cache.copyTo(temporary.entry, channel)) {
                    throw IOException("The downloaded file left the cache before it could be read")
                }
            }
            CommitOutcome.CLEARED, CommitOutcome.FAILED -> try {
                cache.copyFile(temporary.path, channel)
            } finally {
                cache.discard(temporary)
            }
        }
    }

    /**
     * What a failed download returns instead of its failure, if anything.
     *
     * - The server says the object is gone: every cached copy of it is dropped and the failure is
     *   rethrown, so a deleted object stops being served.
     * - Anything else (offline, a server error, a 403 during a token refresh): the cached copy of
     *   the variant the download asked for, when there is one. When the request was never built,
     *   the variant is unknown, and nothing is served rather than the wrong one.
     */
    private suspend fun <T : Any> recover(
        path: String,
        probe: DownloadRequestProbe,
        failure: Exception,
        readCached: suspend (StorageCacheEntry) -> T?,
    ): T {
        if (failure.indicatesServerObjectMissing()) {
            cache.invalidateObject(bucketId, path)
            sdkLogger.warning { "Storage object in $bucketId no longer exists; dropped its cached copy" }
            throw failure
        }
        val cached = probe.entryIn(cache, bucketId, path)?.let { entry -> readCached(entry) }
        if (cached != null) {
            sdkLogger.warning { "Storage download from $bucketId failed; serving the cached copy" }
            return cached
        }
        sdkLogger.error { "Storage download from $bucketId failed and nothing is cached" }
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
     * Runs [fetch] into a pipe and streams the pipe into a temporary file, chunk by chunk.
     *
     * The temporary file is opened when the first bytes arrive, in the directory of the entry
     * [entryProvider] names then — by that point the request has been built, so the variant is
     * known. A failed or cancelled download deletes it; a failure to write it cancels the download
     * and reports [StreamedDownload.CacheRefused]. The download's own failure is rethrown as it was.
     */
    private suspend fun downloadToTemporaryFile(
        entryProvider: () -> StorageCacheEntry?,
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
        var temporary: TemporaryDownload? = null
        var completed = false
        try {
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val read = readOrRethrow(pipe, buffer) { producer.await() }
                if (read < 0) break
                if (read == 0) continue
                val target = temporary ?: openFor(entryProvider())
                    ?: return@coroutineScope abandon(producer, pipe, cause = null, uncacheable = entryProvider() == null)
                temporary = target
                try {
                    withContext(Dispatchers.IO) { target.sink.write(buffer, 0, read) }
                } catch (failure: IOException) {
                    sdkLogger.warning { "Storage cache could not write a download (${failure::class.simpleName}); not caching" }
                    return@coroutineScope abandon(producer, pipe, cause = failure, uncacheable = false)
                }
            }
            producer.await()
            val target = temporary ?: openFor(entryProvider())
                ?: return@coroutineScope if (entryProvider() == null) StreamedDownload.Uncacheable else StreamedDownload.CacheRefused(null)
            temporary = target
            // The sink buffers; a full disk can surface only here. Past this point the temporary
            // file holds every byte, so it can be served even if the rename that follows fails.
            try {
                withContext(Dispatchers.IO) { target.sink.flush() }
            } catch (failure: IOException) {
                sdkLogger.warning { "Storage cache could not write a download (${failure::class.simpleName}); not caching" }
                return@coroutineScope StreamedDownload.CacheRefused(failure)
            }
            completed = true
            StreamedDownload.Written(target)
        } finally {
            if (!completed) temporary?.let { cache.discard(it) }
        }
    }

    private suspend fun openFor(entry: StorageCacheEntry?): TemporaryDownload? =
        entry?.let { cache.openTemporaryDownload(it) }

    private fun abandon(
        producer: Deferred<Unit>,
        pipe: ByteChannel,
        cause: Throwable?,
        uncacheable: Boolean,
    ): StreamedDownload {
        producer.cancel()
        pipe.cancel(null)
        return if (uncacheable) StreamedDownload.Uncacheable else StreamedDownload.CacheRefused(cause)
    }

    /**
     * Reads the next chunk of [pipe]. When the producer failed, the pipe fails too; this rethrows the
     * producer's own failure rather than the pipe's wrapper, so callers see what supabase-kt threw.
     */
    private suspend fun readOrRethrow(
        pipe: ByteReadChannel,
        buffer: ByteArray,
        producerFailure: suspend () -> Unit,
    ): Int = try {
        pipe.readAvailable(buffer, 0, buffer.size)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        producerFailure()
        throw failure
    }

    // ─────────────────────── Writes: online-only, invalidating ───────────────────────

    override suspend fun upload(path: String, data: UploadData, options: UploadOptionBuilder.() -> Unit): FileUploadResponse =
        invalidatingAfter(path) { delegate.upload(path, data, options) }

    override suspend fun upload(path: String, data: ByteArray, options: UploadOptionBuilder.() -> Unit): FileUploadResponse =
        invalidatingAfter(path) { delegate.upload(path, data, options) }

    override suspend fun update(path: String, data: UploadData, options: UploadOptionBuilder.() -> Unit): FileUploadResponse =
        invalidatingAfter(path) { delegate.update(path, data, options) }

    override suspend fun update(path: String, data: ByteArray, options: UploadOptionBuilder.() -> Unit): FileUploadResponse =
        invalidatingAfter(path) { delegate.update(path, data, options) }

    override suspend fun uploadToSignedUrl(
        path: String,
        token: String,
        data: UploadData,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse = invalidatingAfter(path) { delegate.uploadToSignedUrl(path, token, data, options) }

    override suspend fun uploadToSignedUrl(
        path: String,
        token: String,
        data: ByteArray,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse = invalidatingAfter(path) { delegate.uploadToSignedUrl(path, token, data, options) }

    override suspend fun delete(paths: Collection<String>) {
        delegate.delete(paths)
        paths.forEach { path -> cache.invalidateObject(bucketId, path) }
    }

    override suspend fun delete(vararg paths: String) {
        delete(paths.toList())
    }

    override suspend fun move(from: String, to: String, destinationBucket: String?) {
        delegate.move(from, to, destinationBucket)
        cache.moveObject(fromBucket = bucketId, from = from, toBucket = destinationBucket ?: bucketId, to = to)
    }

    override suspend fun copy(from: String, to: String, destinationBucket: String?) {
        delegate.copy(from, to, destinationBucket)
        // The copy's bytes are not on the device; the destination's cached copy, if any, is stale.
        cache.invalidateObject(destinationBucket ?: bucketId, to)
    }

    /**
     * Runs a write, then drops the cached copies of [path] it made stale. A write that timed out
     * may have been stored anyway, so it drops them too; any other failure left the server's copy
     * as it was, and the cache with it. Storage writes are never queued.
     */
    private suspend fun <T> invalidatingAfter(path: String, write: suspend () -> T): T {
        val result = try {
            write()
        } catch (failure: Exception) {
            if (failure.mayHaveReachedTheServer()) withContext(NonCancellable) { cache.invalidateObject(bucketId, path) }
            throw failure
        }
        withContext(NonCancellable) { cache.invalidateObject(bucketId, path) }
        return result
    }

    private fun Throwable.mayHaveReachedTheServer(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is HttpRequestTimeoutException || current is SocketTimeoutException) return true
            current = current.cause
        }
        return false
    }

    /**
     * True when this throwable (or any cause in its chain) says the object no longer exists on the
     * server, so its cached copies should be dropped rather than served.
     *
     * Supabase Storage reports a missing object as a [NotFoundRestException] — usually carried on an
     * HTTP 400 whose JSON body reports a 404, so its [RestException.statusCode] can read 400 — so
     * the type is matched directly, plus any [RestException] or Ktor response with a literal 404.
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
