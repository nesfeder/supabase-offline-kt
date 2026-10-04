// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.inmemory

import io.github.nesfeder.offlinetasks.data.PhotoFile

/** Photo files kept on this device, by file name. It knows nothing about Android. */
interface PhotoFileStore {
    /** Saves [bytes] as [fileName], replacing any file of that name. */
    suspend fun write(fileName: String, bytes: ByteArray): PhotoFile

    /** The file called [fileName], or null when it is not on this device. */
    suspend fun find(fileName: String): PhotoFile?

    /** Deletes [fileName] if it exists. */
    suspend fun delete(fileName: String)

    /** Deletes every file. */
    suspend fun deleteAll()
}
