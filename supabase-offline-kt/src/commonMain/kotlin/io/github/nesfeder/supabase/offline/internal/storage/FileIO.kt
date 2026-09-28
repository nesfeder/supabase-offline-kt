package io.github.nesfeder.supabase.offline.internal.storage

import okio.ByteString.Companion.toByteString
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
 * Directory under the data directory that holds cached objects with plain names, at
 * `<data>/storage/<bucket>/<object path>`. Unchanged since the first release: installs keep their
 * files there, and clearing local data removes it.
 */
internal const val STORAGE_DIRECTORY_NAME: String = "storage"

/**
 * Directory under the data directory that holds every cache entry whose name is not plain (see
 * [isPlainObjectName]) — every image-transform variant, and every object whose bucket or path
 * holds anything but printable ASCII or is too long for a file name. Such an entry's file is named
 * by a hash of its cache key, so nothing about it comes from the object path, and no two keys can
 * meet on one file however the file system folds names.
 */
internal const val HASHED_DIRECTORY_NAME: String = "storage-hashed"

/**
 * Longest path segment, in bytes, stored under its own name. File systems cap a name at 255 bytes;
 * this leaves room for the temporary-file prefix and suffix a download is written under first.
 */
private const val MAX_PLAIN_SEGMENT_BYTES: Int = 200

/** Longest object path stored under its own name, well inside every platform's path limit. */
private const val MAX_PLAIN_PATH_BYTES: Int = 512

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
 * Whether [name] — a bucket identifier or an object path — can be stored under its own name:
 * printable ASCII only, and short enough for every file system.
 *
 * Anything else could reach the file system as a different name than the server's. APFS folds
 * Unicode normalization forms, so `café` written two ways is one file; okio writes every lone
 * surrogate as `?`; a segment over 255 bytes cannot be created at all. Such names are stored under
 * a hash of their key instead. Case is not folded: iOS and Android app storage are case-sensitive,
 * and only a Mac or a simulator, whose disks are not, would put `A.png` and `a.png` in one file.
 *
 * @param name the bucket identifier or object path, already known to be cacheable.
 * @return `true` when it is stored verbatim.
 */
internal fun isPlainObjectName(name: String): Boolean =
    name.length <= MAX_PLAIN_PATH_BYTES &&
        name.all { character -> character in ' '..'~' } &&
        name.split('/').all { segment -> segment.length <= MAX_PLAIN_SEGMENT_BYTES }

/**
 * The cache file of an untransformed object: `<data>/storage/<bucket>/<object path>` for a plain
 * name, or its [hashedFilePath] for any other.
 *
 * @param dataDirectory the offline data directory.
 * @param bucketId the bucket.
 * @param objectPath the object path.
 * @return the file, or `null` when the bucket or the path cannot be cached. A verbatim path is
 *   verified to lie inside the bucket's directory.
 */
internal fun objectFilePathOrNull(dataDirectory: Path, bucketId: String, objectPath: String): Path? {
    if (!isCacheableBucketId(bucketId) || !isCacheableObjectPath(objectPath)) return null
    if (!isPlainObjectName(bucketId) || !isPlainObjectName(objectPath)) {
        return hashedFilePath(dataDirectory, "$bucketId::$objectPath")
    }
    val bucketDirectory = dataDirectory / STORAGE_DIRECTORY_NAME / bucketId
    val file = objectPath.split('/').fold(bucketDirectory) { directory, segment -> directory / segment }
    return file.takeIf { it.isStrictlyInside(bucketDirectory) }
}

/**
 * The cache file stored under a hash of its key: `<data>/storage-hashed/<sha-256 of the key>`.
 *
 * The key is hashed as UTF-16 code units, not as UTF-8: a UTF-8 encoder writes every lone
 * surrogate as the same replacement byte, and two keys differing only there would share a file.
 *
 * @param dataDirectory the offline data directory.
 * @param key the entry's cache key.
 * @return the file.
 */
internal fun hashedFilePath(dataDirectory: Path, key: String): Path {
    val codeUnits = ByteArray(key.length * 2)
    key.forEachIndexed { index, character ->
        codeUnits[index * 2] = (character.code shr 8).toByte()
        codeUnits[index * 2 + 1] = character.code.toByte()
    }
    return dataDirectory / HASHED_DIRECTORY_NAME / codeUnits.toByteString().sha256().hex()
}

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
