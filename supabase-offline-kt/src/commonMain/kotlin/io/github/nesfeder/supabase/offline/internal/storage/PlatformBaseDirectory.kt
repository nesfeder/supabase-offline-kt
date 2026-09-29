// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import okio.Path

/**
 * The platform's default offline data directory: `<filesDir>/supabase_offline` on Android,
 * `Documents/supabase_offline` on iOS.
 */
internal expect class PlatformBaseDirectory {
    /** The directory's absolute path. */
    val path: Path
}
