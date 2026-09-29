// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import okio.Path

/**
 * Records [epochMillis] as the file's modification time, the moment the file cache last used
 * [path]. That survives a restart and needs no column at schema version 2. Best effort: a file
 * whose time cannot be set is ranked, for least-recently-used eviction, by when it was written.
 */
internal expect fun markFileUsed(path: Path, epochMillis: Long)

/**
 * Asks the platform to leave [path], a file or a whole directory, out of device backups. A restore
 * then never brings one user's cache and unsent writes to another device.
 *
 * @param path the file or directory.
 * @return `true` when the exclusion is in place. Android has no per-file switch, so it always
 *   returns `false` there; apps exclude the files through their backup rules instead.
 */
internal expect fun excludeFromBackup(path: Path): Boolean
