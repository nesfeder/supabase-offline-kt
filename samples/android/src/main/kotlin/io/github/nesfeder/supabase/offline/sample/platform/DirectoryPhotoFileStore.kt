// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.platform

import io.github.nesfeder.supabase.offline.sample.data.PhotoFile
import io.github.nesfeder.supabase.offline.sample.data.PhotoFileStore
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A [PhotoFileStore] in one directory on the device, such as a folder of the app's cache. */
class DirectoryPhotoFileStore(private val directory: File) : PhotoFileStore {

    override suspend fun write(fileName: String, bytes: ByteArray): PhotoFile =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val file = File(directory, fileName)
            file.writeBytes(bytes)
            file.toPhotoFile()
        }

    override suspend fun find(fileName: String): PhotoFile? =
        withContext(Dispatchers.IO) {
            File(directory, fileName).takeIf { it.isFile }?.toPhotoFile()
        }

    override suspend fun delete(fileName: String) {
        withContext(Dispatchers.IO) { File(directory, fileName).delete() }
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) { directory.listFiles()?.forEach { it.delete() } }
    }

    private fun File.toPhotoFile(): PhotoFile =
        PhotoFile(
            path = absolutePath,
            fetchedAt = Instant.fromEpochMilliseconds(lastModified()),
            sizeBytes = length(),
        )
}
