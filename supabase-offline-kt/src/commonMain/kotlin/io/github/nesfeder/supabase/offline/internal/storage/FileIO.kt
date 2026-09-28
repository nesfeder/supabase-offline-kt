package io.github.nesfeder.supabase.offline.internal.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.SYSTEM

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

/** Resolve a local file path for a storage object. Layout: <base>/storage/<bucketId>/<objectPath> */
internal fun resolveLocalPath(baseDir: Path, bucketId: String, objectPath: String): Path {
    val clean = objectPath
        .removePrefix("/$bucketId/")
        .removePrefix("$bucketId/")
        .removePrefix("/")
    return baseDir / "storage" / bucketId / clean
}

/** Ensure parent directories exist for [path]. */
internal fun ensureParentDirs(path: Path) {
    val parent = path.parent ?: return
    if (!FileSystem.SYSTEM.exists(parent)) {
        FileSystem.SYSTEM.createDirectories(parent, mustCreate = false)
    }
}

/** Write [bytes] to [path]. Creates parents if needed. If [overwrite]=false and file exists, no-op. */
internal fun writeBytes(path: Path, bytes: ByteArray, overwrite: Boolean) {
    ensureParentDirs(path)
    if (!overwrite && FileSystem.SYSTEM.exists(path)) return
    FileSystem.SYSTEM.write(path) { write(bytes) }
}

/** Read entire file contents from [path], or null if missing. */
internal fun readBytesOrNull(path: Path): ByteArray? {
    if (!FileSystem.SYSTEM.exists(path)) return null
    return FileSystem.SYSTEM.read(path) { readByteArray() }
}

/** True if [path] exists on disk. */
internal fun exists(path: Path): Boolean = FileSystem.SYSTEM.exists(path)

/** Remove file at [path] if present. */
internal fun deleteIfExists(path: Path) {
    if (FileSystem.SYSTEM.exists(path)) {
        FileSystem.SYSTEM.delete(path, mustExist = false)
    }
}

/**
 * Recursively deletes the file tree rooted at [path], running on [Dispatchers.IO].
 *
 * A no-op when [path] is absent (okio's `mustExist = false`), so identity-clearing flows can
 * unconditionally wipe the offline-storage directory even if nothing was ever cached to disk.
 *
 * @param path The root directory (or file) to remove along with all of its contents.
 */
internal suspend fun deleteRecursivelyIfExists(path: Path) {
    withContext(Dispatchers.IO) {
        FileSystem.SYSTEM.deleteRecursively(path, mustExist = false)
    }
}