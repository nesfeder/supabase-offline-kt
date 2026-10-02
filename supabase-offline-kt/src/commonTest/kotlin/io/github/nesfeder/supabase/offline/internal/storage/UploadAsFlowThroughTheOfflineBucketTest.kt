// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.storage.UploadStatus
import io.github.jan.supabase.storage.uploadAsFlow
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * supabase-kt's `uploadAsFlow` works on the offline bucket as on its own: it goes through the
 * bucket's `upload`, which the offline bucket overrides, so the upload is sent, its status flow
 * ends in success, and the cached copy it replaced is dropped.
 */
class UploadAsFlowThroughTheOfflineBucketTest {

    private val baseDirectory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-upload-${Random.nextLong()}"
    private val fake = FakeBucketApi(bucketId = BUCKET, onDownload = { byteArrayOf(1, 2, 3) })
    private val bucket = newTestBucketApi(delegate = fake, baseDirectory = baseDirectory)

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(baseDirectory, mustExist = false)
    }

    @Test
    fun an_upload_as_a_flow_is_sent_and_drops_the_copy_it_replaced() = runTest {
        // Given — a file the device has cached
        bucket.downloadAuthenticated(OBJECT_PATH)

        // When — a new version is uploaded through supabase-kt's flow
        val statuses =
            bucket.uploadAsFlow(OBJECT_PATH, byteArrayOf(9, 9)) { upsert = true }.toList()

        // Then
        assertIs<UploadStatus.Success>(statuses.last())
        assertEquals(listOf("upload" to OBJECT_PATH), fake.uploads)
        assertNull(bucket.cachedFile(OBJECT_PATH))
    }

    private companion object {
        const val BUCKET = "avatars"
        const val OBJECT_PATH = "user/avatar.png"
    }
}
