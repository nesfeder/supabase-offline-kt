package io.github.nesfeder.supabase.offline.internal.storage

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mapping of a bucket object path to its on-disk cache location under `<base>/storage/<bucket>/`.
 */
class FileIOTest {

    @Test
    fun resolveLocalPath_doesNotStripPartialMatches() {
        // Given
        val baseDir = "/base".toPath()
        val bucketId = "images" // contains 'a'
        val objectPath = "apple.jpg" // starts with 'a'

        // When
        val result = resolveLocalPath(baseDir, bucketId, objectPath)

        // Then
        assertEquals("/base/storage/images/apple.jpg", result.toString())
    }

    @Test
    fun resolveLocalPath_removesBucketPrefix() {
        // Given
        val baseDir = "/base".toPath()
        val bucketId = "bucket"
        val objectPath = "bucket/file.txt"

        // When
        val result = resolveLocalPath(baseDir, bucketId, objectPath)

        // Then
        assertEquals("/base/storage/bucket/file.txt", result.toString())
    }

    @Test
    fun resolveLocalPath_removesLeadingSlashBucketPrefix() {
        // Given
        val baseDir = "/base".toPath()
        val bucketId = "bucket"
        val objectPath = "/bucket/file.txt"

        // When
        val result = resolveLocalPath(baseDir, bucketId, objectPath)

        // Then
        assertEquals("/base/storage/bucket/file.txt", result.toString())
    }

    @Test
    fun resolveLocalPath_removesLeadingSlash() {
        // Given
        val baseDir = "/base".toPath()
        val bucketId = "bucket"
        val objectPath = "/file.txt"

        // When
        val result = resolveLocalPath(baseDir, bucketId, objectPath)

        // Then
        assertEquals("/base/storage/bucket/file.txt", result.toString())
    }
}
