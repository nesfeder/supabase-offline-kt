package io.github.nesfeder.supabase.offline.internal.storage

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mapping of a bucket object path to its on-disk cache location under `<base>/storage/<bucket>/`,
 * and the object paths that are never mapped at all.
 */
class FileIOTest {

    private val baseDir = "/base".toPath()

    @Test
    fun an_object_path_maps_under_its_bucket() {
        // Given
        val bucketId = "images" // contains 'a'
        val objectPath = "apple.jpg" // starts with 'a'

        // When
        val result = objectFilePathOrNull(baseDir, bucketId, objectPath)

        // Then
        assertEquals("/base/storage/images/apple.jpg", result.toString())
    }

    @Test
    fun a_nested_object_path_keeps_its_folders() {
        // Given / When
        val result = objectFilePathOrNull(baseDir, "avatars", "u-1/2024/me.png")

        // Then
        assertEquals("/base/storage/avatars/u-1/2024/me.png", result.toString())
    }

    @Test
    fun a_path_that_starts_with_the_bucket_name_is_its_own_object() {
        // Given — "bucket/file.txt" in bucket "bucket" is a different object from "file.txt"; the
        // server addresses it as bucket/bucket/file.txt, so the cache must not merge the two
        val bucketId = "bucket"

        // When
        val prefixed = objectFilePathOrNull(baseDir, bucketId, "bucket/file.txt")
        val plain = objectFilePathOrNull(baseDir, bucketId, "file.txt")

        // Then
        assertEquals("/base/storage/bucket/bucket/file.txt", prefixed.toString())
        assertEquals("/base/storage/bucket/file.txt", plain.toString())
    }

    @Test
    fun a_leading_slash_is_never_cached() {
        // Given / When / Then — it used to be stripped, which also turned "//x" into an absolute path
        assertNull(objectFilePathOrNull(baseDir, "bucket", "/file.txt"))
        assertNull(objectFilePathOrNull(baseDir, "bucket", "//etc/passwd"))
    }

    @Test
    fun paths_that_could_leave_the_bucket_directory_are_never_cached() {
        // Given
        val refused = listOf(
            "",
            "../x",
            "a/../../x",
            "a/..",
            ".",
            "./x",
            "a//b",
            "a/",
            "..\\x",
            "a\\..\\..\\x",
            "nul\u0000",
            "line\nbreak",
            "del\u007F",
        )

        // When / Then
        refused.forEach { path -> assertNull(objectFilePathOrNull(baseDir, "bucket", path), "cached \"$path\"") }
    }

    @Test
    fun a_bucket_id_that_is_not_one_segment_is_never_cached() {
        // Given / When / Then
        listOf("", ".", "..", "a/b", "../..", "a\\b", "tab\t").forEach { bucketId ->
            assertFalse(isCacheableBucketId(bucketId), "cached bucket \"$bucketId\"")
            assertNull(objectFilePathOrNull(baseDir, bucketId, "file.txt"))
        }
    }

    @Test
    fun ordinary_names_with_spaces_and_punctuation_are_cached() {
        // Given / When / Then — only what could escape or be misread is refused
        listOf("a b/my file%?#.png", "élan.txt", "...", "..hidden", ".profile", "a.b.c").forEach { path ->
            assertTrue(isCacheableObjectPath(path), "refused \"$path\"")
        }
    }

    @Test
    fun containment_is_judged_after_normalizing() {
        // Given / When / Then
        assertTrue("/base/storage/b/x".toPath().isStrictlyInside("/base/storage".toPath()))
        assertFalse("/base/storage/../x".toPath().isStrictlyInside("/base/storage".toPath()))
        assertFalse("/base/storage".toPath().isStrictlyInside("/base/storage".toPath()))
        assertFalse("/base/storage-other/x".toPath().isStrictlyInside("/base/storage".toPath()))
        assertFalse("base/storage/x".toPath().isStrictlyInside("/base/storage".toPath()))
    }
}
