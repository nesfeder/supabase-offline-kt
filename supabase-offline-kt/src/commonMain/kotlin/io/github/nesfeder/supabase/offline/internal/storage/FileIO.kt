package io.github.nesfeder.supabase.offline.internal.storage

import okio.ByteString.Companion.encodeUtf8
import okio.Path

/**
 * Platform-specific base directory holder.
 *
 * - Android: construct with a Context (actual class in androidMain)
 * - iOS:     no-arg constructor that resolves Documents as base dir (actual class in iosMain)
 */
internal expect class PlatformBaseDirectory {
    /** Absolute base dir path for offline storage (e.g. /data/data/.../files/supabase_offline). */
    val path: Path
}

/**
 * Directory under the data directory that holds cached objects, at
 * `<data>/storage/<bucket>/<object path>`. Unchanged since the first release: installs keep their
 * files there, and clearing local data removes it.
 */
internal const val STORAGE_DIRECTORY_NAME: String = "storage"

/**
 * Directory under the data directory that holds cached image-transform variants. A variant's file
 * is named by a hash of its cache key, so nothing about it comes from the object path.
 */
internal const val TRANSFORMED_DIRECTORY_NAME: String = "storage-transformed"

/**
 * Separates an object path from its image transform in a variant's cache-row path. It is a control
 * character, and a path holding one is never cached, so no object path can equal a variant's.
 */
internal const val VARIANT_SEPARATOR: Char = '\u001F'

/**
 * Whether [bucketId] can name a directory of the cache: one non-empty segment, not `.` or `..`,
 * with no `/`, `\` or control character.
 *
 * @param bucketId the bucket identifier.
 * @return `true` when it is safe to use as a path segment.
 */
internal fun isCacheableBucketId(bucketId: String): Boolean =
    isCacheableSegment(bucketId) && '/' !in bucketId

/**
 * Whether [objectPath] can name a file of the cache. Object paths are untrusted input — they often
 * come from rows other users wrote — so anything that could climb out of the bucket's directory, or
 * that the file system would read differently from how the server does, is refused: a leading `/`,
 * an empty segment (`//`, or a trailing `/`), a `.` or `..` segment, a `\`, NUL or any other control
 * character. Such an object still downloads; it is just never cached.
 *
 * @param objectPath the object path as the caller passed it.
 * @return `true` when it is safe to map onto a file.
 */
internal fun isCacheableObjectPath(objectPath: String): Boolean =
    objectPath.isNotEmpty() && objectPath.split('/').all(::isCacheableSegment)

private fun isCacheableSegment(segment: String): Boolean =
    segment.isNotEmpty() &&
        segment != "." &&
        segment != ".." &&
        '\\' !in segment &&
        segment.none { character -> character < ' ' || character == '\u007F' }

/**
 * The cache file of an untransformed object: `<data>/storage/<bucket>/<object path>`.
 *
 * @param dataDirectory the offline data directory.
 * @param bucketId the bucket.
 * @param objectPath the object path.
 * @return the file, or `null` when the bucket or the path cannot be cached. A returned path is
 *   verified to lie inside the bucket's directory.
 */
internal fun objectFilePathOrNull(dataDirectory: Path, bucketId: String, objectPath: String): Path? {
    if (!isCacheableBucketId(bucketId) || !isCacheableObjectPath(objectPath)) return null
    val bucketDirectory = dataDirectory / STORAGE_DIRECTORY_NAME / bucketId
    val file = objectPath.split('/').fold(bucketDirectory) { directory, segment -> directory / segment }
    return file.takeIf { it.isStrictlyInside(bucketDirectory) }
}

/**
 * The cache file of an image-transform variant: `<data>/storage-transformed/<sha-256 of its key>`.
 *
 * @param dataDirectory the offline data directory.
 * @param variantKey the variant's cache key.
 * @return the file.
 */
internal fun variantFilePath(dataDirectory: Path, variantKey: String): Path =
    dataDirectory / TRANSFORMED_DIRECTORY_NAME / variantKey.encodeUtf8().sha256().hex()

/**
 * Whether this path lies strictly below [root] once both are normalized, so no `..` can lead out.
 *
 * @param root the directory it must stay inside.
 * @return `true` for a descendant of [root], `false` for [root] itself or anything outside it.
 */
internal fun Path.isStrictlyInside(root: Path): Boolean {
    val child = normalized()
    val parent = root.normalized()
    if (child.root != parent.root) return false
    val childSegments = child.segments
    val parentSegments = parent.segments
    return childSegments.size > parentSegments.size &&
        childSegments.subList(0, parentSegments.size) == parentSegments
}
