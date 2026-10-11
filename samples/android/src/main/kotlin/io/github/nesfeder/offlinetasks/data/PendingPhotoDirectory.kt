// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** What a pending photo's record is written as: every field, defaults included. */
private val recordJson = Json { encodeDefaults = true }

/**
 * The app's own queue of photos picked on this device: for each, `<photo id>.jpg` and its record,
 * `<photo id>.json`, in [root], a directory of the app's own files. It is outside the library's
 * data directory and file cache, so `clearLocalData()` never touches it: the app clears it itself.
 *
 * @param io where the files are read and written.
 */
class PendingPhotoDirectory(
    private val root: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()

    /** The records, oldest picked first; null until they are read from disk. */
    private val records = MutableStateFlow<List<PendingPhoto>?>(null)

    /** Every pending photo, oldest picked first, as each change is made. */
    val photos: Flow<List<PendingPhoto>> = records.onStart { load() }.filterNotNull()

    /** The JPEG of the photo [photoId], which the screens draw until the upload is done. */
    fun fileOf(photoId: String): File = File(root, "$photoId.jpg")

    // --8<-- [start:pending-photo-add]
    /**
     * Files [record] with its [jpeg]: the JPEG first, then the record, so a record always has its
     * file. Nothing is sent: the uploader takes it from here.
     */
    suspend fun add(record: PendingPhoto, jpeg: ByteArray) = change {
        root.mkdirs()
        fileOf(record.photoId).writeBytes(jpeg)
        write(record)
        it + record
    }

    // --8<-- [end:pending-photo-add]

    /** The JPEG of the photo [photoId], to upload. */
    suspend fun readJpeg(photoId: String): ByteArray =
        withContext(io) { fileOf(photoId).readBytes() }

    /** Whether the photo [photoId] is still here: it is not, once removed or uploaded. */
    suspend fun contains(photoId: String): Boolean = current().any { it.photoId == photoId }

    /** The oldest photo waiting to upload, or null when none is. */
    suspend fun oldestWaiting(): PendingPhoto? =
        current().firstOrNull { it.state == PendingPhotoState.Waiting }

    /** Applies [update] to the record of [photoId] and returns it, or null when there is none. */
    suspend fun update(photoId: String, update: (PendingPhoto) -> PendingPhoto): PendingPhoto? {
        var updated: PendingPhoto? = null
        change { list ->
            list.map { record ->
                if (record.photoId != photoId) return@map record
                update(record).also {
                    write(it)
                    updated = it
                }
            }
        }
        return updated
    }

    /** Puts every failed photo that another try may upload back to waiting. */
    suspend fun retryFailed() = change { list ->
        list.map { record ->
            if (record.state != PendingPhotoState.Failed || !record.retryable) return@map record
            record.copy(state = PendingPhotoState.Waiting).also(::write)
        }
    }

    /** Deletes the photo [photoId], file and record; false when there was none. */
    suspend fun remove(photoId: String): Boolean {
        val removed = removeWhere { it.photoId == photoId }
        return removed.isNotEmpty()
    }

    /** Deletes the pending photos of the task [taskId] and returns them. */
    suspend fun removeTask(taskId: String): List<PendingPhoto> = removeWhere { it.taskId == taskId }

    /** Deletes every pending photo, file and record, and returns what there was. */
    suspend fun removeAll(): List<PendingPhoto> = removeWhere { true }

    /** The records as they are now, read from disk first if they have not been. */
    private suspend fun current(): List<PendingPhoto> {
        load()
        return records.value.orEmpty()
    }

    /**
     * Reads the records once. A try a closed app left running is waiting again, and what a filing
     * or a write stopped half way left (a JPEG with no record, a partial record) is deleted.
     */
    private suspend fun load() {
        if (records.value != null) return
        lock.withLock {
            if (records.value != null) return
            records.value = withContext(io) { readRecords() }
        }
    }

    private fun readRecords(): List<PendingPhoto> {
        val files = root.listFiles().orEmpty()
        val read =
            files
                .filter { it.extension == "json" }
                .map { recordJson.decodeFromString<PendingPhoto>(it.readText()) }
                .map { record ->
                    if (record.state != PendingPhotoState.Uploading) record
                    else record.copy(state = PendingPhotoState.Waiting).also(::write)
                }
        val named = read.mapTo(HashSet()) { "${it.photoId}.jpg" }
        files
            .filter { it.extension == "partial" || (it.extension == "jpg" && it.name !in named) }
            .forEach { it.delete() }
        return read.sortedWith(compareBy({ it.pickedAt }, { it.photoId }))
    }

    /** Deletes the photos [matches] picks and returns them. */
    private suspend fun removeWhere(matches: (PendingPhoto) -> Boolean): List<PendingPhoto> {
        var removed = emptyList<PendingPhoto>()
        change { list ->
            removed = list.filter(matches)
            removed.forEach { record ->
                File(root, "${record.photoId}.json").delete() // first: a lone JPEG is swept on load
                fileOf(record.photoId).delete()
            }
            list - removed.toSet()
        }
        return removed
    }

    /** Runs [edit] on the records under the lock, on [io], and publishes what it returns. */
    private suspend fun change(edit: (List<PendingPhoto>) -> List<PendingPhoto>) {
        load()
        lock.withLock {
            records.value = withContext(io) { edit(records.value.orEmpty()) }
        }
    }

    /**
     * Writes [record] whole: to a temporary file first, then renamed over the old one, so a record
     * is never left half written.
     */
    private fun write(record: PendingPhoto) {
        val target = File(root, "${record.photoId}.json")
        val partial = File(root, "${record.photoId}.json.partial")
        partial.writeText(recordJson.encodeToString(record))
        check(partial.renameTo(target)) { "The record of a pending photo could not be saved" }
    }
}
