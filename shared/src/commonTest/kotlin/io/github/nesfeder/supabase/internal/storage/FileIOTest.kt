package io.github.nesfeder.supabase.internal.storage

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals

class FileIOTest {

    @Test
    fun resolveLocalPath_doesNotStripPartialMatches() {
        val baseDir = "/base".toPath()
        val bucketId = "images" // contains 'a'
        val objectPath = "apple.jpg" // starts with 'a'

        val result = resolveLocalPath(baseDir, bucketId, objectPath)

        assertEquals("/base/storage/images/apple.jpg", result.toString())
    }

    @Test
    fun resolveLocalPath_removesBucketPrefix() {
        val baseDir = "/base".toPath()
        val bucketId = "bucket"
        val objectPath = "bucket/file.txt"

        val result = resolveLocalPath(baseDir, bucketId, objectPath)

        assertEquals("/base/storage/bucket/file.txt", result.toString())
    }

    @Test
    fun resolveLocalPath_removesLeadingSlashBucketPrefix() {
        val baseDir = "/base".toPath()
        val bucketId = "bucket"
        val objectPath = "/bucket/file.txt"

        val result = resolveLocalPath(baseDir, bucketId, objectPath)

        assertEquals("/base/storage/bucket/file.txt", result.toString())
    }

    @Test
    fun resolveLocalPath_removesLeadingSlash() {
        val baseDir = "/base".toPath()
        val bucketId = "bucket"
        val objectPath = "/file.txt"

        val result = resolveLocalPath(baseDir, bucketId, objectPath)

        assertEquals("/base/storage/bucket/file.txt", result.toString())
    }
}
