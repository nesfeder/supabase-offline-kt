// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import okio.Path

/**
 * The default data directory's name on every platform. Installs keep their cached files under it;
 * renaming it would orphan them.
 */
internal const val PLATFORM_DATA_DIRECTORY_NAME: String = "supabase_offline"

/**
 * The platform's default offline data directory: `<filesDir>/supabase_offline` on Android,
 * `Library/Application Support/supabase_offline` on iOS.
 */
internal expect class PlatformBaseDirectory {
    /** The directory's absolute path. */
    val path: Path
}
