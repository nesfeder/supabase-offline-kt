// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.exceptions.UnauthorizedRestException
import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.forbiddenRestException
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * The iOS storage helpers (`ensureLocalPath`, `localPathIfCached`) over the offline bucket: they
 * hand back the cached file, and they never turn a cancellation or a failure into "not available".
 */
class BucketApiIosExtensionsTest {

    private val directory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-ios-helpers-${Random.nextLong().toULong()}"
    private val fakeBucket = FakeBucketApi(bucketId = "test-bucket")
    private val offlineApi = newTestBucketApi(delegate = fakeBucket, baseDirectory = directory)

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    @Test
    fun ensureLocalPath_returns_the_cached_file() = runTest {
        // Given
        fakeBucket.onDownload = { "content".encodeToByteArray() }

        // When
        val path: String = offlineApi.ensureLocalPath("a b/test.txt", isPublic = true)

        // Then
        assertEquals((directory / "storage" / "test-bucket" / "a b" / "test.txt").toString(), path)
        assertEquals(path, offlineApi.localPathIfCached("a b/test.txt"))
    }

    @Test
    fun localPathIfCached_with_a_transform_is_that_transforms_copy() = runTest {
        // Given — a thumbnail downloaded through supabase-kt's own bucket, which builds the
        // transformed request
        val server = RecordingStorageServer()
        server.objects["avatars/a.png"] = "image".encodeToByteArray()
        val bucket =
            newTestBucketApi(
                delegate = server.supabaseClient().storage.from("avatars"),
                baseDirectory = directory,
            )
        bucket.downloadAuthenticated("a.png") { transform { size(64, 64) } }

        // When
        val thumbnail = bucket.localPathIfCached("a.png") { size(64, 64) }
        val otherSize = bucket.localPathIfCached("a.png") { size(32, 32) }

        // Then
        assertEquals(bucket.cachedFile("a.png") { size(64, 64) }?.path, thumbnail)
        assertTrue(thumbnail.orEmpty().startsWith(directory.toString()))
        assertNull(otherSize)
    }

    @Test
    fun localPathIfCached_is_null_before_anything_is_cached() = runTest {
        // Given / When / Then
        assertNull(offlineApi.localPathIfCached("missing.txt"))
        assertEquals(0, fakeBucket.downloadInvocations)
    }

    @Test
    fun ensureLocalPath_throws_the_download_failure_when_nothing_is_cached() = runTest {
        // Given
        fakeBucket.onDownload = { throw IOException("network unreachable") }

        // When / Then
        assertFailsWith<IOException> { offlineApi.ensureLocalPath("missing.txt") }
    }

    @Test
    fun ensureLocalPath_throws_an_auth_failure() = runTest {
        // Given
        fakeBucket.onDownload = { throw forbiddenRestException() }

        // When / Then
        assertFailsWith<UnauthorizedRestException> { offlineApi.ensureLocalPath("missing.txt") }
    }

    @Test
    fun ensureLocalPath_propagates_cancellation() = runTest {
        // Given
        fakeBucket.onDownload = { awaitCancellation() }
        var returned = false
        var caught: Throwable? = null
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    offlineApi.ensureLocalPath("slow.txt")
                    returned = true
                } catch (failure: Throwable) {
                    caught = failure
                }
            }

        // When
        job.cancelAndJoin()

        // Then
        assertFalse(returned)
        assertTrue(caught is CancellationException, "expected CancellationException, got $caught")
    }
}
