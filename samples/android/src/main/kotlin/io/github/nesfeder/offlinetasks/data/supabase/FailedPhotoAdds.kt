// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A photo whose upload was sent at once and failed: Storage refused it, or answered with an error
 * the library does not keep queued (a `500`, say), so the library threw it and kept nothing. The
 * app keeps it, with its bytes, so the user can retry it or remove it, offline and across restarts.
 *
 * @property createdAt when it was added, as ISO-8601 text.
 * @property failure why it failed, in the app's words, or null when nothing says.
 */
@Serializable
internal data class FailedPhotoAdd(
    @SerialName("photo_id") val photoId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("object_path") val objectPath: String,
    val width: Int?,
    val height: Int?,
    @SerialName("created_at") val createdAt: String,
    val failure: String?,
)

/**
 * The photos whose upload failed when it was sent at once, kept in [directory]: for each, its bytes
 * as `<photo id>.jpg` and its record as `<photo id>.json`, the bytes written first, so a record
 * always has its file. Read once at start, in [scope].
 */
internal class FailedPhotoAdds(private val directory: File, scope: CoroutineScope) {

    private val kept = MutableStateFlow<Map<String, FailedPhotoAdd>>(emptyMap())

    /** The photos kept, by photo id. */
    val adds: StateFlow<Map<String, FailedPhotoAdd>> = kept.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val read = readAll()
            kept.update { current -> read + current }
        }
    }

    /** The file the bytes of the photo [photoId] are kept in. */
    fun fileOf(photoId: String): File = File(directory, "$photoId.jpg")

    /** The kept bytes of the photo [photoId], or null when they are gone. */
    suspend fun bytesOf(photoId: String): ByteArray? =
        withContext(Dispatchers.IO) { fileOf(photoId).takeIf { it.isFile }?.readBytes() }

    /** Keeps [add] with its bytes [jpeg], over any earlier record of it. */
    suspend fun keep(add: FailedPhotoAdd, jpeg: ByteArray) {
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            write(fileOf(add.photoId), jpeg)
            write(recordOf(add.photoId), Json.encodeToString(add).encodeToByteArray())
        }
        kept.update { it + (add.photoId to add) }
    }

    /** Deletes the photo [photoId], its record first. */
    suspend fun forget(photoId: String) {
        kept.update { it - photoId }
        withContext(Dispatchers.IO) {
            recordOf(photoId).delete()
            fileOf(photoId).delete()
        }
    }

    /** Deletes every photo [which] takes. */
    suspend fun forgetAll(which: (FailedPhotoAdd) -> Boolean = { true }) {
        kept.value.values.filter(which).forEach { add -> forget(add.photoId) }
    }

    private fun recordOf(photoId: String): File = File(directory, "$photoId.json")

    private companion object {
        const val ORPHAN_AGE_MILLIS = 60_000L
    }

    /**
     * Writes [bytes] to [file] through a temporary file, so a crash never leaves it half written.
     */
    private fun write(file: File, bytes: ByteArray) {
        val partial = File(file.parentFile, file.name + ".partial")
        partial.writeBytes(bytes)
        if (!partial.renameTo(file)) {
            partial.delete()
            throw IOException("Could not keep ${file.name}")
        }
    }

    /**
     * The records whose bytes are there. A record without them, or unreadable, is deleted, and so
     * are bytes no record names (a crash between the two writes).
     */
    private fun readAll(): Map<String, FailedPhotoAdd> {
        val records = directory.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
        val read =
            records
                .mapNotNull { record ->
                    val add =
                        try {
                            Json.decodeFromString<FailedPhotoAdd>(record.readText())
                        } catch (unreadable: SerializationException) {
                            null
                        } catch (unreadable: IllegalArgumentException) {
                            null
                        }
                    if (add == null || !fileOf(add.photoId).isFile) {
                        record.delete()
                        null
                    } else add
                }
                .associateBy { it.photoId }
        // Only files older than a minute: one written for a photo failing just now is spared
        val before = System.currentTimeMillis() - ORPHAN_AGE_MILLIS
        directory
            .listFiles { file -> file.name.endsWith(".jpg") || file.name.endsWith(".partial") }
            .orEmpty()
            .filter { file ->
                file.lastModified() < before &&
                    (file.name.endsWith(".partial") || file.nameWithoutExtension !in read)
            }
            .forEach { file -> file.delete() }
        return read
    }
}
