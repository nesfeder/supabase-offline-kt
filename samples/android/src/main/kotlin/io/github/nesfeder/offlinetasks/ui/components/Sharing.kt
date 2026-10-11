// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.components

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The folder in the app's cache that shared photos are copied to; `res/xml/shared_files.xml`. */
private const val SHARED_FOLDER = "shared"

/** Opens the system's share sheet with [text]. */
fun Context.shareText(text: String) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    startActivity(Intent.createChooser(send, null))
}

// --8<-- [start:share-photo]
/**
 * Opens the system's share sheet with the JPEG at [path], the library's cached copy. Another app
 * reads it later, and the cache may drop its own copy at any time, so it is copied out first and
 * handed over through the app's file provider: a `file:` URI cannot leave the app.
 */
suspend fun Context.shareJpeg(path: String) {
    val shared =
        withContext(Dispatchers.IO) {
            val folder = File(cacheDir, SHARED_FOLDER).apply { mkdirs() }
            File(path).copyTo(File(folder, "photo.jpg"), overwrite = true)
        }
    val uri = FileProvider.getUriForFile(this, "$packageName.files", shared)
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    startActivity(Intent.createChooser(send, null))
}
// --8<-- [end:share-photo]
