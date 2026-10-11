// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** The app's pending-photo directory, on real files in a temporary directory. */
class PendingPhotoDirectoryTest {

    private val root: File = createTempDirectory("pending-photos").toFile()

    @AfterTest
    fun deleteRoot() {
        root.deleteRecursively()
    }

    @Test
    fun a_record_and_its_file_read_back_as_written_after_a_restart() = runTest {
        // Given — a photo whose upload failed twice, filed and updated
        val jpeg = byteArrayOf(-1, -40, 7, -1, -39)
        val failed =
            testPendingPhoto()
                .copy(
                    state = PendingPhotoState.Failed,
                    failureMessage = "Storage refused it (403): new row violates policy",
                    attempts = 2,
                    canRetry = false,
                )
        PendingPhotoDirectory(root).run {
            add(testPendingPhoto(), jpeg)
            update(failed.photoId) { failed }
        }

        // When — read by a new directory over the same files, as after the app restarts
        val reread = PendingPhotoDirectory(root)

        // Then — the same record, field for field, and the same bytes
        assertEquals(listOf(failed), reread.photos.first())
        assertContentEquals(jpeg, reread.readJpeg(failed.photoId))
        // The stored names are the record's format: a later version must still read them.
        val stored = File(root, "${failed.photoId}.json").readText()
        listOf("\"photo_id\"", "\"picked_at\"", "\"state\":\"failed\"", "\"can_retry\":false")
            .forEach { assertTrue(it in stored, "$it not in $stored") }
    }

    @Test
    fun a_try_a_closed_app_left_running_waits_again_and_a_lone_file_is_swept() = runTest {
        // Given — a photo left uploading, and a JPEG whose record was never written
        val photo = testPendingPhoto()
        PendingPhotoDirectory(root).run {
            add(photo, byteArrayOf(1))
            update(photo.photoId) { it.copy(state = PendingPhotoState.Uploading, attempts = 1) }
        }
        val lone =
            File(root, "00000000-0000-4000-8000-0000000000ff.jpg").apply {
                writeBytes(byteArrayOf(2))
            }

        // When
        val reread = PendingPhotoDirectory(root).photos.first()

        // Then — waiting to be tried again, its try counted; the lone file gone
        assertEquals(PendingPhotoState.Waiting, reread.single().state)
        assertEquals(1, reread.single().attempts)
        assertFalse(lone.exists())
    }

    @Test
    fun removing_a_task_removes_only_its_photos_with_their_files() = runTest {
        // Given — a photo of each of two tasks
        val directory = PendingPhotoDirectory(root)
        val kept = testPendingPhoto(photoId = KEPT_PHOTO_ID, taskId = OTHER_TASK_ID)
        directory.add(testPendingPhoto(), byteArrayOf(1))
        directory.add(kept, byteArrayOf(2))

        // When
        directory.removeTask(TASK_ID)

        // Then
        assertEquals(listOf(kept), directory.photos.first())
        assertEquals(
            setOf("$KEPT_PHOTO_ID.jpg", "$KEPT_PHOTO_ID.json"),
            root.list().orEmpty().toSet(),
        )
    }

    private fun testPendingPhoto(
        photoId: String = PHOTO_ID,
        taskId: String = TASK_ID,
    ): PendingPhoto =
        PendingPhoto(
            photoId = photoId,
            taskId = taskId,
            userId = USER_ID,
            objectPath = photoObjectPath(USER_ID, taskId, photoId),
            pickedAt = Instant.parse("2026-10-11T09:30:00Z"),
            width = 4,
            height = 3,
        )

    private companion object {
        const val USER_ID = "00000000-0000-4000-8000-000000000001"
        const val TASK_ID = "00000000-0000-4000-8000-0000000000a1"
        const val OTHER_TASK_ID = "00000000-0000-4000-8000-0000000000a2"
        const val PHOTO_ID = "00000000-0000-4000-8000-0000000000b1"
        const val KEPT_PHOTO_ID = "00000000-0000-4000-8000-0000000000b2"
    }
}
