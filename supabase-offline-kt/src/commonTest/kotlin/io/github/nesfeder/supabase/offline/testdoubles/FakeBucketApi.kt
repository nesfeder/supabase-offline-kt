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
import io.github.jan.supabase.storage.resumable.ResumableClient
import io.ktor.utils.io.ByteWriteChannel
import kotlin.time.Duration

/**
 * Scriptable [BucketApi] test double for exercising [io.github.nesfeder.supabase.offline.internal.storage.OfflineBucketApi].
 *
 * The byte-array download overrides (`downloadAuthenticated` / `downloadPublic`) route through
 * [onDownload], which a test can point at a success or a specific failure (transport error, a
 * `NotFoundRestException`, or `awaitCancellation`) per case. `delete`, `move` and `copy` succeed
 * and record their arguments ([deletedPaths], [moves], [copies]) so cache-maintenance tests can
 * assert the wrapper forwarded them. Every other [BucketApi] member is [unsupported]: the offline
 * wrapper under test never reaches them, and the byte-channel download variants delegate to the
 * byte-array ones inside the wrapper, so they are never invoked here either.
 *
 * @property bucketId Bucket identifier the wrapper reads via `BucketApi by delegate`.
 * @property onDownload Suspending behavior invoked for every byte-array download; defaults to a
 *   failure so a test must opt into success explicitly.
 */
internal class FakeBucketApi(
    override val bucketId: String = "test-bucket",
    var onDownload: suspend (path: String) -> ByteArray = { error("onDownload not scripted") },
) : BucketApi {

    /** Number of byte-array download calls received — for network-vs-cache assertions. */
    var downloadInvocations: Int = 0
        private set

    /** Paths passed to the byte-array `downloadPublic`, in call order. */
    val publicDownloadPaths: MutableList<String> = mutableListOf()

    /** Paths passed to the byte-array `downloadAuthenticated`, in call order. */
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

    override fun setHeader(name: String, value: String): BucketApi = unsupported("setHeader")

    override suspend fun upload(
        path: String,
        data: UploadData,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse = unsupported("upload")

    override suspend fun uploadToSignedUrl(
        path: String,
        token: String,
        data: UploadData,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse = unsupported("uploadToSignedUrl")

    override suspend fun update(
        path: String,
        data: UploadData,
        options: UploadOptionBuilder.() -> Unit,
    ): FileUploadResponse = unsupported("update")

    override suspend fun delete(paths: Collection<String>) {
        deletedPaths += paths
    }

    override suspend fun move(from: String, to: String, destinationBucket: String?) {
        moves += Triple(from, to, destinationBucket)
    }

    override suspend fun copy(from: String, to: String, destinationBucket: String?) {
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
        return onDownload(path)
    }

    override suspend fun downloadAuthenticated(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit,
    ): Unit = unsupported("downloadAuthenticated(channel)")

    override suspend fun downloadPublic(
        path: String,
        options: DownloadOptionBuilder.() -> Unit,
    ): ByteArray {
        downloadInvocations++
        publicDownloadPaths += path
        return onDownload(path)
    }

    override suspend fun downloadPublic(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit,
    ): Unit = unsupported("downloadPublic(channel)")

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
