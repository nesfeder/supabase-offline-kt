// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import okio.Path.Companion.toOkioPath

/**
 * The Android file cache writes a finished download through to the disk with `fsync` on the file's
 * own descriptor, opened for reading: a file that is not there is reported, never created empty.
 */
class FileAttributesAndroidTest {

    private val directory: File = createTempDirectory("offline-file-attributes").toFile()

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun syncFileToDisk_writes_a_file_through_and_leaves_its_bytes_as_they_are() {
        // Given
        val file = File(directory, "download.tmp")
        file.writeText("a-cached-avatar")

        // When
        syncFileToDisk(file.toOkioPath())

        // Then
        assertEquals("a-cached-avatar", file.readText())
    }

    @Test
    fun syncFileToDisk_reports_a_missing_file_and_creates_nothing() {
        // Given
        val missing = File(directory, "missing.tmp")

        // When / Then
        assertFailsWith<IOException> { syncFileToDisk(missing.toOkioPath()) }
        assertFalse(missing.exists())
    }
}
