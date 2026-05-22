package io.github.nesfeder.supabase.internal.storage

import android.content.Context
import okio.Path
import okio.Path.Companion.toPath
import java.io.File

/**
 * Android actual: inject a Context so we can choose an app-specific base dir.
 *
 * The base dir will be: <filesDir>/supabase_offline
 */
internal actual class PlatformBaseDirectory(context: Context) {

    actual val path: Path = File(
        context.applicationContext.filesDir,
        "supabase_offline"
    ).absolutePath.toPath()
}