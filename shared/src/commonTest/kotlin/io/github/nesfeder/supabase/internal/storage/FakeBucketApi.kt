package io.github.nesfeder.supabase.internal.storage

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.BucketListFilter
import io.github.jan.supabase.storage.DownloadOptionBuilder
import io.github.jan.supabase.storage.FileObject
import io.github.jan.supabase.storage.FileObjectV2
import io.github.jan.supabase.storage.FileUploadResponse
import io.github.jan.supabase.storage.ImageTransformation
import io.github.jan.supabase.storage.SignedUrl
import io.github.jan.supabase.storage.UploadData
import io.github.jan.supabase.storage.UploadOptionBuilder
import io.github.jan.supabase.storage.UploadSignedUrl
import io.github.jan.supabase.storage.resumable.ResumableClient
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlin.time.Duration

/**
 * Very small in-memory fake for [BucketApi] used in tests.
 *
 * Only the subset of behaviour exercised by [OfflineBucketApiTest] is
 * implemented:
 * - [bucketId]
 * - [downloadPublic] (both ByteArray and ByteWriteChannel variants)
 * - [delete]
 *
 * All other members either no-op or throw if accidentally used.
 */
internal open class FakeBucketApi(
    override val bucketId: String,
) : BucketApi {

    /**
     * Next result that [downloadPublic] should return or throw.
     */
    data class DownloadScenario(
        val bytes: ByteArray? = null,
        val error: Throwable? = null,
    )

    private val publicDownloads = mutableMapOf<String, DownloadScenario>()
    private val authenticatedDownloads = mutableMapOf<String, DownloadScenario>()
    val deletedPaths = mutableListOf<Collection<String>>()

    fun enqueuePublicDownload(path: String, scenario: DownloadScenario) {
        publicDownloads[path] = scenario
    }

    fun enqueueAuthenticatedDownload(path: String, scenario: DownloadScenario) {
        authenticatedDownloads[path] = scenario
    }

    override suspend fun downloadPublic(
        path: String,
        options: DownloadOptionBuilder.() -> Unit
    ): ByteArray {
        val scenario = publicDownloads[path]
            ?: error("No FakeBucketApi scenario configured for path=$path")

        scenario.error?.let { throw it }
        return scenario.bytes
            ?: error("FakeBucketApi scenario for path=$path has no bytes")
    }

    override suspend fun downloadPublic(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit
    ) {
        val bytes = downloadPublic(path, options)
        channel.writeFully(bytes)
    }

    override suspend fun delete(paths: Collection<String>) {
        deletedPaths += paths
    }

    // ---------------------------------------------------------------------
    // Unused members in current tests
    // ---------------------------------------------------------------------

    override suspend fun downloadAuthenticated(
        path: String,
        options: DownloadOptionBuilder.() -> Unit
    ): ByteArray {
        val scenario = authenticatedDownloads[path]
            ?: error("No FakeBucketApi auth scenario configured for path=$path")

        scenario.error?.let { throw it }
        return scenario.bytes
            ?: error("FakeBucketApi auth scenario for path=$path has no bytes")
    }

    override suspend fun downloadAuthenticated(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit
    ) {
        val bytes = downloadAuthenticated(path, options)
        channel.writeFully(bytes)
    }

    override suspend fun move(from: String, to: String, destinationBucket: String?) {
        // Not needed for current tests
    }

    override suspend fun copy(from: String, to: String, destinationBucket: String?) {
        // Not needed for current tests
    }

    // ---------------- Required abstract members with simple stubs ----------------

    override val supabaseClient: SupabaseClient
        get() = error("supabaseClient not needed in FakeBucketApi")

    override val resumable: ResumableClient
        get() = error("resumable not needed in FakeBucketApi")

    override fun authenticatedUrl(path: String): String =
        "fake://$bucketId/auth/$path"

    override fun publicUrl(path: String): String =
        "fake://$bucketId/pub/$path"

    override fun authenticatedRenderUrl(
        path: String,
        transform: ImageTransformation.() -> Unit
    ): String = authenticatedUrl(path)

    override fun publicRenderUrl(
        path: String,
        transform: ImageTransformation.() -> Unit
    ): String = publicUrl(path)

    override suspend fun changePublicStatusTo(public: Boolean) {
        // no-op
    }

    override suspend fun createSignedUploadUrl(
        path: String,
        upsert: Boolean
    ): UploadSignedUrl = error("createSignedUploadUrl not implemented in FakeBucketApi")

    override suspend fun createSignedUrl(
        path: String,
        expiresIn: Duration,
        transform: ImageTransformation.() -> Unit
    ): String = error("createSignedUrl not implemented in FakeBucketApi")

    override suspend fun createSignedUrls(
        expiresIn: Duration,
        paths: Collection<String>
    ): List<SignedUrl> = error("createSignedUrls not implemented in FakeBucketApi")

    override suspend fun exists(path: String): Boolean = false

    override suspend fun info(path: String): FileObjectV2 =
        error("info not implemented in FakeBucketApi")

    override suspend fun list(
        prefix: String,
        filter: BucketListFilter.() -> Unit
    ): List<FileObject> = emptyList()

    override suspend fun update(
        path: String,
        data: UploadData,
        options: UploadOptionBuilder.() -> Unit
    ): FileUploadResponse = error("update not implemented in FakeBucketApi")

    override suspend fun upload(
        path: String,
        data: UploadData,
        options: UploadOptionBuilder.() -> Unit
    ): FileUploadResponse = error("upload not implemented in FakeBucketApi")

    override suspend fun uploadToSignedUrl(
        path: String,
        token: String,
        data: UploadData,
        options: UploadOptionBuilder.() -> Unit
    ): FileUploadResponse = error("uploadToSignedUrl not implemented in FakeBucketApi")

    // Any new abstract members added to BucketApi in future versions should
    // either be implemented here or deliberately left throwing.
}
