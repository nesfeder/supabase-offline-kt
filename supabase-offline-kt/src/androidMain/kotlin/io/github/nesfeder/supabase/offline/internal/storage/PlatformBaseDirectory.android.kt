// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import android.content.Context
import java.io.File
import okio.Path
import okio.Path.Companion.toPath

/** `<filesDir>/supabase_offline`, resolved from the application context. */
internal actual class PlatformBaseDirectory(context: Context) {

    actual val path: Path =
        File(
                context.applicationContext.filesDir,
                "supabase_offline",
            )
            .absolutePath
            .toPath()
}
