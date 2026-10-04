// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.testing

import io.github.nesfeder.offlinetasks.data.PhotoFile
import io.github.nesfeder.offlinetasks.data.inmemory.PhotoFileStore
import kotlin.time.Clock

/** A [PhotoFileStore] in memory: file names to their bytes, with the paths it would have used. */
class FakePhotoFileStore(private val clock: Clock) : PhotoFileStore {
    private val files = mutableMapOf<String, ByteArray>()

    /** The names of the files on the "device", for assertions. */
    val fileNames: Set<String>
        get() = files.keys.toSet()

    override suspend fun write(fileName: String, bytes: ByteArray): PhotoFile {
        files[fileName] = bytes.copyOf()
        return PhotoFile(
            path = "/photos/$fileName",
            fetchedAt = clock.now(),
            sizeBytes = bytes.size.toLong(),
        )
    }

    override suspend fun find(fileName: String): PhotoFile? =
        files[fileName]?.let {
            PhotoFile(
                path = "/photos/$fileName",
                fetchedAt = clock.now(),
                sizeBytes = it.size.toLong(),
            )
        }

    override suspend fun delete(fileName: String) {
        files.remove(fileName)
    }

    override suspend fun deleteAll() {
        files.clear()
    }
}
