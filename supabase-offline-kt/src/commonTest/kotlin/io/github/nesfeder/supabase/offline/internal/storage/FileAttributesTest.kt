// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM

/**
 * Each platform's `syncFileToDisk` writes a finished download through to the disk and leaves its
 * bytes as they are, and reports a file that is not there rather than creating it empty. The file
 * cache logs a sync that fails and relies on its size check instead, so a platform actual that
 * always failed would pass every other test: these run on each platform's own actual.
 */
class FileAttributesTest {

    private val directory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-sync-${Random.nextLong().toULong()}"

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    @Test
    fun syncFileToDisk_writes_a_file_through_and_leaves_its_bytes_as_they_are() {
        // Given
        val file = directory / "download.tmp"
        FileSystem.SYSTEM.createDirectories(directory)
        FileSystem.SYSTEM.write(file) { writeUtf8("a-cached-avatar") }

        // When
        syncFileToDisk(file)

        // Then
        assertEquals("a-cached-avatar", FileSystem.SYSTEM.read(file) { readUtf8() })
    }

    @Test
    fun syncFileToDisk_reports_a_missing_file_and_creates_nothing() {
        // Given
        val missing = directory / "missing.tmp"
        FileSystem.SYSTEM.createDirectories(directory)

        // When / Then
        assertFailsWith<IOException> { syncFileToDisk(missing) }
        assertFalse(FileSystem.SYSTEM.exists(missing))
    }
}
