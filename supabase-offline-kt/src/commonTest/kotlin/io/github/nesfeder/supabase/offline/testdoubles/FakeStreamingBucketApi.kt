// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.network.HttpRequestOverride
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.DownloadOptionBuilder
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully

/**
 * A bucket whose channel downloads generate [totalBytes] on the fly, so a test can measure what the
 * offline bucket itself holds in memory. It runs the download's request overrides on a request for
 * the untransformed object, as supabase-kt does.
 *
 * @param inner answers everything but the channel downloads.
 * @param totalBytes the size of every download.
 * @param onProgress called with the bytes written so far, after each chunk.
 */
internal class FakeStreamingBucketApi(
    private val inner: FakeBucketApi,
    private val totalBytes: Long,
    private val onProgress: (Long) -> Unit = {},
) : BucketApi by inner {

    override suspend fun downloadAuthenticated(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit,
    ) {
        val overrides = mutableListOf<HttpRequestOverride>()
        DownloadOptionBuilder(httpRequestOverrides = overrides).apply(options)
        val request =
            HttpRequestBuilder().apply {
                url("$TEST_SUPABASE_URL/storage/v1/object/authenticated/$bucketId/$path")
            }
        overrides.forEach { override -> request.override() }
        val chunk = ByteArray(CHUNK_BYTES) { 7 }
        var written = 0L
        while (written < totalBytes) {
            val size = minOf(CHUNK_BYTES.toLong(), totalBytes - written).toInt()
            channel.writeFully(chunk, 0, size)
            written += size
            onProgress(written)
        }
        channel.flushAndClose()
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
    }
}
