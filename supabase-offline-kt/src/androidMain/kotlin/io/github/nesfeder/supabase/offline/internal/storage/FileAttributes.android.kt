package io.github.nesfeder.supabase.offline.internal.storage

import okio.Path
import java.io.File

internal actual fun markFileUsed(path: Path, epochMillis: Long) {
    File(path.toString()).setLastModified(epochMillis)
}

/**
 * Android backs up files through the app's own backup rules, not a per-file attribute, so there is
 * nothing to set here. `createSupabaseOfflineClient` documents the rules an app adds.
 */
internal actual fun excludeFromBackup(path: Path): Boolean = false
