// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

/**
 * Mapping of a bucket object path to its on-disk cache location under `<base>/storage/<bucket>/`,
 * and the object paths that are never mapped at all.
 */
class CacheFilePathsTest {

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
        // Given / When / Then — it used to be stripped, which also turned "//x" into an absolute
        // path
        assertNull(objectFilePathOrNull(baseDir, "bucket", "/file.txt"))
        assertNull(objectFilePathOrNull(baseDir, "bucket", "//etc/passwd"))
    }

    @Test
    fun paths_that_could_leave_the_bucket_directory_are_never_cached() {
        // Given
        val refused =
            listOf(
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
        refused.forEach { path ->
            assertNull(objectFilePathOrNull(baseDir, "bucket", path), "cached \"$path\"")
        }
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
        listOf("a b/my file%?#.png", "élan.txt", "...", "..hidden", ".profile", "a.b.c").forEach {
            path ->
            assertTrue(isCacheableObjectPath(path), "refused \"$path\"")
        }
    }

    @Test
    fun a_segment_that_reads_as_a_volume_letter_is_stored_inside_the_base() {
        // Given — okio takes "a:1.png" for a Windows volume letter and resolves it on its own,
        // outside the bucket's directory; such a name is stored under its hash instead
        val result = assertNotNull(objectFilePathOrNull(baseDir, "b", "x/a:1.png"))
        val bucketResult = assertNotNull(objectFilePathOrNull(baseDir, "c:", "file.txt"))

        // When / Then
        assertTrue(result.isStrictlyInside(baseDir), "$result left the base")
        assertEquals(baseDir / "storage-hashed", result.parent)
        assertTrue(bucketResult.isStrictlyInside(baseDir), "$bucketResult left the base")
        assertEquals(baseDir / "storage-hashed", bucketResult.parent)
        assertFalse(isPlainName("u:42/a.png"))
        assertFalse(isPlainName("C:"))
        // A colon anywhere else is a plain name, with its verbatim file
        assertTrue(isPlainName("x/12:30.png"))
        assertTrue(isPlainName("ab:c"))
        assertEquals(
            "/base/storage/b/x/12:30.png",
            objectFilePathOrNull(baseDir, "b", "x/12:30.png").toString(),
        )
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
