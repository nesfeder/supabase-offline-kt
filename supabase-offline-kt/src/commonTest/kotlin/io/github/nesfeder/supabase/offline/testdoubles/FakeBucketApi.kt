package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.DownloadOptionBuilder
import io.github.jan.supabase.storage.FileObject
import io.github.jan.supabase.storage.FileObjectV2
import io.github.jan.supabase.storage.FileUploadResponse
import io.github.jan.supabase.storage.ImageTransformation
import io.github.jan.supabase.storage.PublicUrlBuilder
import io.github.jan.supabase.storage.PurgeCacheOptions
import io.github.jan.supabase.storage.SignedUrl
import io.github.jan.supabase.storage.SignedUrlBuilder
import io.github.jan.supabase.storage.SignedUrlsBuilder
import io.github.jan.supabase.storage.StorageListFilter
import io.github.jan.supabase.storage.UploadData
import io.github.jan.supabase.storage.UploadOptionBuilder
import io.github.jan.supabase.storage.UploadSignedUrl
import io.github.jan.supabase.network.HttpRequestOverride
import io.github.jan.supabase.storage.resumable.ResumableClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlin.time.Duration

/**
 * Scriptable [BucketApi] test double for exercising the offline bucket.
 *
 * Every download — byte array or byte channel, authenticated or public — gets its bytes from
 * [onDownload], which a test can point at a success or a specific failure (transport error, a
 * `NotFoundRestException`, or `awaitCancellation`) per case. A channel download writes those bytes
 * in [channelChunkSize] pieces through [onChannelChunk] (a test can park or fail between pieces),
 * then closes the channel, as supabase-kt does.
 *
 * Like supabase-kt, every download runs the request overrides in its options against the request
 * it would send: an untransformed object URL, since this double cannot see an image transform
 * (a test that needs transforms uses [RecordingStorageServer] and the real bucket).
 *
 * `delete`, `move`, `copy`, `upload`, `update` and `uploadToSignedUrl` succeed and record their
 * arguments so cache-maintenance tests can assert the wrapper forwarded them; [onWrite] can make
 * them fail. Every other [BucketApi] member is [unsupported].
 *
 * @property bucketId Bucket identifier the wrapper reads via `BucketApi by delegate`.
 * @property onDownload Suspending behavior invoked for every download; defaults to a failure so a
 *   test must opt into success explicitly.
 */
internal class FakeBucketApi(
    override val bucketId: String = "test-bucket",
    var onDownload: suspend (path: String) -> ByteArray = { error("onDownload not scripted") },
) : BucketApi {

    /** Size of the pieces a channel download writes. */
    var channelChunkSize: Int = 4

    /** Runs after each piece a channel download writes, with the count of bytes written so far. */
    var onChannelChunk: suspend (bytesWritten: Int) -> Unit = { }

    /** Runs before every write operation (upload, update, delete, move, copy); throw to fail it. */
    var onWrite: suspend (operation: String) -> Unit = { }

    /** Paths passed to the channel downloads, in call order. */
    val channelDownloadPaths: MutableList<String> = mutableListOf()

    /** `(operation, path)` of every upload, update and signed-URL upload, in call order. */
    val uploads: MutableList<Pair<String, String>> = mutableListOf()

    /** Number of download calls received, of every kind — for network-vs-cache assertions. */
    var downloadInvocations: Int = 0
        private set

    /** Paths passed to either `downloadPublic`, in call order. */
    val publicDownloadPaths: MutableList<String> = mutableListOf()

    /** Paths passed to either `downloadAuthenticated`, in call order. */
    val authenticatedDownloadPaths: MutableList<String> = mutableListOf()

    /** Path collections passed to [delete], in call order. */
    val deletedPaths: MutableList<Collection<String>> = mutableListOf()

    /** `(from, to, destinationBucket)` of every [move], in call order. */
    val moves: MutableList<Triple<String, String, String?>> = mutableListOf()

    /** `(from, to, destinationBucket)` of every [copy], in call order. */
    val copies: MutableList<Triple<String, String, String?>> = mutableListOf()

    private fun unsupported(member: String): Nothing =
        error("FakeBucketApi.$member must not be called in this test")

    override val supabaseClient: SupabaseClient get() = unsupported("supabaseClient")

    override val resumable: ResumableClient get() = unsupported("resumable")

    /** Headers set through [setHeader]. */
    val headers: MutableMap<String, String> = mutableMapOf()

    override fun setHeader(name: String, value: String): BucketApi {
        headers[name] = value
        return this
    }

    override suspend fun upload(
        path: String,
        data: UploadData,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse = recordUpload("upload", path)

    override suspend fun uploadToSignedUrl(
        path: String,
        token: String,
        data: UploadData,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse = recordUpload("uploadToSignedUrl", path)

    override suspend fun update(
        path: String,
        data: UploadData,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse = recordUpload("update", path)

    private suspend fun recordUpload(operation: String, path: String): FileUploadResponse {
        onWrite(operation)
        uploads += operation to path
        return FileUploadResponse(id = "id-${uploads.size}", path = path, key = "$bucketId/$path")
    }

    /**
     * Runs the request overrides in [options] against the request supabase-kt would send for an
     * untransformed download of [path], as the real bucket does before it sends anything.
     */
    private fun runRequestOverrides(path: String, isPublic: Boolean, options: DownloadOptionBuilder.() -> Unit) {
        val overrides = mutableListOf<HttpRequestOverride>()
        DownloadOptionBuilder(httpRequestOverrides = overrides).apply(options)
        val access = if (isPublic) "public" else "authenticated"
        val request = HttpRequestBuilder().apply {
            url("$TEST_SUPABASE_URL/storage/v1/object/$access/$bucketId/$path")
        }
        overrides.forEach { override -> request.override() }
    }

    private suspend fun writeToChannel(path: String, channel: ByteWriteChannel) {
        channelDownloadPaths += path
        val bytes = onDownload(path)
        var written = 0
        while (written < bytes.size) {
            val end = minOf(written + channelChunkSize, bytes.size)
            channel.writeFully(bytes, written, end)
            channel.flush()
            written = end
            onChannelChunk(written)
        }
        channel.flushAndClose()
    }

    override suspend fun delete(paths: Collection<String>) {
        onWrite("delete")
        deletedPaths += paths
    }

    override suspend fun move(from: String, to: String, destinationBucket: String?) {
        onWrite("move")
        moves += Triple(from, to, destinationBucket)
    }

    override suspend fun copy(from: String, to: String, destinationBucket: String?) {
        onWrite("copy")
        copies += Triple(from, to, destinationBucket)
    }

    override suspend fun createSignedUploadUrl(path: String, upsert: Boolean): UploadSignedUrl =
        unsupported("createSignedUploadUrl")

    override suspend fun createSignedUrl(
        path: String,
        expiresIn: Duration,
        builder: SignedUrlBuilder.() -> Unit,
    ): String = unsupported("createSignedUrl")

    override suspend fun createSignedUrls(
        expiresIn: Duration,
        paths: Collection<String>,
        builder: SignedUrlsBuilder.() -> Unit,
    ): List<SignedUrl> = unsupported("createSignedUrls")

    override suspend fun downloadAuthenticated(
        path: String,
        options: DownloadOptionBuilder.() -> Unit,
    ): ByteArray {
        downloadInvocations++
        authenticatedDownloadPaths += path
        runRequestOverrides(path, isPublic = false, options)
        return onDownload(path)
    }

    override suspend fun downloadAuthenticated(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit,
    ) {
        downloadInvocations++
        authenticatedDownloadPaths += path
        runRequestOverrides(path, isPublic = false, options)
        writeToChannel(path, channel)
    }

    override suspend fun downloadPublic(
        path: String,
        options: DownloadOptionBuilder.() -> Unit,
    ): ByteArray {
        downloadInvocations++
        publicDownloadPaths += path
        runRequestOverrides(path, isPublic = true, options)
        return onDownload(path)
    }

    override suspend fun downloadPublic(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit,
    ) {
        downloadInvocations++
        publicDownloadPaths += path
        runRequestOverrides(path, isPublic = true, options)
        writeToChannel(path, channel)
    }

    override suspend fun list(
        prefix: String,
        filter: StorageListFilter.Files.() -> Unit,
    ): List<FileObject> = unsupported("list")

    override suspend fun info(path: String): FileObjectV2 = unsupported("info")

    override suspend fun exists(path: String): Boolean = unsupported("exists")

    override suspend fun changePublicStatusTo(public: Boolean): Unit =
        unsupported("changePublicStatusTo")

    override suspend fun purgeCache(path: String, options: PurgeCacheOptions.() -> Unit): Unit =
        unsupported("purgeCache")

    override fun publicUrl(path: String, builder: PublicUrlBuilder.() -> Unit): String =
        unsupported("publicUrl")

    override fun authenticatedUrl(path: String): String = unsupported("authenticatedUrl")

    override fun authenticatedRenderUrl(
        path: String,
        transform: ImageTransformation.() -> Unit,
    ): String = unsupported("authenticatedRenderUrl")
}
