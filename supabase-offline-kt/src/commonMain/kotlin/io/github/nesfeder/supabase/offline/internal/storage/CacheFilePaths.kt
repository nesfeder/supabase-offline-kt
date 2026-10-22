// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.internal.TESTED_SUPABASE_KT_VERSION
import okio.ByteString.Companion.toByteString
import okio.Path

/**
 * Directory under the data directory for cached objects with plain names, at
 * `<data>/storage/<bucket>/<object path>`. The name is persisted: installs keep their files there.
 */
internal const val STORAGE_DIRECTORY_NAME: String = "storage"

/**
 * Directory under the data directory for every entry whose name is not plain (see [isPlainName]).
 * Image-transform variants go there too, each in a file named by a hash of its key.
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
 * Whether [objectPath], untrusted input, can name a cache file: no leading `/`, no empty, `.` or
 * `..` segment, no `\` and no control character. Anything else still downloads, uncached.
 *
 * @param objectPath the object path as the caller passed it.
 * @return `true` when it is safe to map onto a file.
 */
internal fun isCacheableObjectPath(objectPath: String): Boolean =
    objectPath.isNotEmpty() && objectPath.split('/').all(::isCacheableSegment)

/**
 * The characters supabase-kt before 3.8 puts into a Storage URL as written: a `%` fails the
 * request, and a `?` or `#` cuts the path short, so the request can fetch a different object. Empty
 * from 3.8, which encodes each segment; this goes when the pin reaches 3.8.
 */
internal val CHARACTERS_SENT_UNENCODED: Set<Char> =
    TESTED_SUPABASE_KT_VERSION.split('.', '-').take(2).map(String::toInt).let { (major, minor) ->
        if (major < 3 || (major == 3 && minor < 8)) setOf('%', '?', '#') else emptySet()
    }

/**
 * Whether the pinned supabase-kt's request for [objectPath] reaches that object, so what it returns
 * may be cached. A path holding one of [CHARACTERS_SENT_UNENCODED] downloads as supabase-kt
 * downloads it, and is never cached or served from the cache.
 */
internal fun isRequestedByItsOwnName(objectPath: String): Boolean = objectPath.none { character ->
    character in CHARACTERS_SENT_UNENCODED
}

/**
 * Whether the pinned supabase-kt can build the URL of a request for [objectPath]: not when it puts
 * `%` in the URL as written and a `%` does not begin a percent-escape, which the HTTP client
 * refuses before sending, as if the network had failed.
 */
internal fun canBuildTheUrlFor(objectPath: String): Boolean =
    '%' !in CHARACTERS_SENT_UNENCODED || !BARE_PERCENT.containsMatchIn(objectPath)

/** A `%` not followed by two hex digits. */
private val BARE_PERCENT = Regex("%(?![0-9A-Fa-f]{2})")

private fun isCacheableSegment(segment: String): Boolean =
    segment.isNotEmpty() &&
        segment != "." &&
        segment != ".." &&
        '\\' !in segment &&
        segment.none { character -> character < ' ' || character == '\u007F' }

/**
 * Whether [name], a bucket or an object path, is stored under its own name: printable ASCII short
 * enough for every file system, with no segment that reads as a Windows volume letter (`u:42`),
 * which okio resolves as an absolute path. Other names could reach the disk folded, truncated or
 * outside the cache, so they are hashed; case is not folded, since device app storage is
 * case-sensitive.
 *
 * @param name the bucket identifier or object path, already known to be cacheable.
 * @return `true` when it is stored verbatim.
 */
internal fun isPlainName(name: String): Boolean =
    name.length <= MAX_PLAIN_PATH_BYTES &&
        name.all { character -> character in ' '..'~' } &&
        name.split('/').all { segment ->
            segment.length <= MAX_PLAIN_SEGMENT_BYTES && !segment.readsAsAVolumeLetter()
        }

/** Whether okio's `Path` takes this segment for a volume letter: an ASCII letter and a `:`. */
private fun String.readsAsAVolumeLetter(): Boolean =
    length >= 2 && this[1] == ':' && (this[0] in 'a'..'z' || this[0] in 'A'..'Z')

/**
 * The cache file of an untransformed object: `<data>/storage/<bucket>/<object path>` for a plain
 * name, or its [hashedFilePath] for any other. A verbatim path is checked to lie inside the
 * bucket's directory.
 *
 * @return the file, or `null` when the bucket or the path cannot be cached.
 */
internal fun objectFilePathOrNull(
    dataDirectory: Path,
    bucketId: String,
    objectPath: String,
): Path? {
    if (!isCacheableBucketId(bucketId) || !isCacheableObjectPath(objectPath)) return null
    if (!isPlainName(bucketId) || !isPlainName(objectPath)) {
        return hashedFilePath(dataDirectory, "$bucketId::$objectPath")
    }
    val bucketDirectory = dataDirectory / STORAGE_DIRECTORY_NAME / bucketId
    val file =
        objectPath.split('/').fold(bucketDirectory) { directory, segment -> directory / segment }
    return file.takeIf { it.isStrictlyInside(bucketDirectory) }
}

/**
 * The cache file stored under a hash of its key: `<data>/storage-hashed/<sha-256 of the key>`.
 *
 * The key is hashed as UTF-16 code units, not as UTF-8: a UTF-8 encoder writes every lone surrogate
 * as the same replacement byte, and two keys differing only there would share a file.
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
