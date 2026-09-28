package io.github.nesfeder.supabase.offline.internal.storage

import okio.Path

/**
 * Records [epochMillis] as the moment the file cache last used [path], by setting the file's
 * modification time. The size bound evicts the least recently used files first, and the time has to
 * survive a restart; no database column may be added for it at schema version 2. Best effort: a
 * file whose time cannot be set is simply ranked by when it was written.
 *
 * @param path the cached file.
 * @param epochMillis the time of use.
 */
internal expect fun markFileUsed(path: Path, epochMillis: Long)

/**
 * Asks the platform to leave [path] — a file, or a directory with everything in it — out of device
 * backups, so a restore never brings one user's cache and unsent writes to another device.
 *
 * @param path the file or directory.
 * @return `true` when the exclusion is in place. Android has no per-file switch, so it always
 *   returns `false` there; apps exclude the files through their backup rules instead.
 */
internal expect fun excludeFromBackup(path: Path): Boolean
