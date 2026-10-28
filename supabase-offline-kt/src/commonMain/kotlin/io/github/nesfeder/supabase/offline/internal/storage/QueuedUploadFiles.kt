// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class, ExperimentalSerializationApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.STORAGE_UPLOADS_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredRejection
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import okio.buffer
import okio.use

/** The `operation` column of a queued upload's row: frozen, as every stored name is. */
internal const val UPLOAD_OPERATION_NAME: String = "UPLOAD"

/**
 * Directory under the data directory holding the bytes of every queued upload, one file each, named
 * by [newQueuedUploadFileName].
 */
internal const val QUEUED_UPLOADS_DIRECTORY_NAME: String = "storage-uploads"

/** Version written into every queued upload's body, so a later format can recognize this one. */
private const val QUEUED_UPLOAD_FORMAT_VERSION: Int = 1

/** The suffix of a queued file while it is written, before it is renamed into place. */
private const val PARTIAL_SUFFIX: String = ".partial"

/** How many random bytes name a queued file: 32 hex characters. */
private const val FILE_NAME_BYTES: Int = 16

/** A queued file's name: [FILE_NAME_BYTES] random bytes in lowercase hex, and nothing else. */
private val QUEUED_FILE_NAME = Regex("[0-9a-f]{${FILE_NAME_BYTES * 2}}")

/**
 * A queued Storage upload as its mutation row's `body_json` holds it, and as its rejected entry
 * keeps it: where it goes, which file of [QUEUED_UPLOADS_DIRECTORY_NAME] holds its bytes, and the
 * options it is sent with.
 *
 * @property version The format's version.
 * @property bucket The bucket's id.
 * @property path The object path within [bucket].
 * @property file The name of the file holding the bytes.
 * @property size The bytes' length.
 * @property upsert Whether the upload overwrites an object already at [path].
 * @property contentType The content type it is sent with; `null` lets supabase-kt infer it from
 *   [path], as it does for an upload that sets none.
 * @property userMetadata The user metadata it is sent with, or `null` for none.
 */
@Serializable
internal data class StoredUpload(
    @EncodeDefault val version: Int = QUEUED_UPLOAD_FORMAT_VERSION,
    val bucket: String,
    val path: String,
    val file: String,
    val size: Long,
    val upsert: Boolean,
    val contentType: String? = null,
    val userMetadata: JsonObject? = null,
) {
    /** This upload as its row's body holds it. */
    fun toBodyJson(json: Json): String = json.encodeToString(serializer(), this)

    /** Whether [file] is a name [newQueuedUploadFileName] made, so it names a file of ours. */
    val hasQueuedFileName: Boolean
        get() = QUEUED_FILE_NAME.matches(file)
}

/** [bodyJson] as a queued upload, or `null` when it does not read as one. */
internal fun storedUploadOf(bodyJson: String, json: Json): StoredUpload? =
    try {
        json.decodeFromString(StoredUpload.serializer(), bodyJson)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/** Whether this row is a queued upload rather than a row write. */
internal val MutationRow.isQueuedUpload: Boolean
    get() = table == STORAGE_UPLOADS_TABLE_NAME && operation == UPLOAD_OPERATION_NAME

/** Whether this rejected entry is a refused upload rather than a row write. */
internal val StoredRejection.isQueuedUpload: Boolean
    get() = table == STORAGE_UPLOADS_TABLE_NAME && operation == UPLOAD_OPERATION_NAME

/** A new name for a queued file: random, so two uploads never share one. */
internal fun newQueuedUploadFileName(): String =
    Random.nextBytes(FILE_NAME_BYTES).joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

/**
 * The directory of queued uploads' bytes, `<data>/storage-uploads`: one file per queued or rejected
 * upload, written whole before its row is inserted, and deleted once no row and no rejected entry
 * names it. Not part of the file cache: its size bound and `clearFileCache()` never touch it.
 *
 * A file being written for an upload not yet queued is staged: [deleteAllExcept] spares it, so a
 * sweep or a clear never deletes the file of an upload on its way into the queue. Callers that read
 * which files the queue names and then delete the rest do both under the queue lock, which every
 * insert takes, so no file can become named in between.
 *
 * @param dataDirectoryProvider the client's data directory.
 * @param syncFile asks the platform to write a file through to the disk before it is renamed.
 * @param excludeFromBackup asks the platform to leave the directory out of device backups.
 */
internal class QueuedUploadFiles(
    private val dataDirectoryProvider: () -> Path,
    private val engineLogger: EngineLogger,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val syncFile: (Path) -> Unit = ::syncFileToDisk,
    private val excludeFromBackup: (Path) -> Boolean = ::excludeFromBackup,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** `<data>/storage-uploads`. */
    val directory: Path by lazy {
        dataDirectoryProvider().normalized() / QUEUED_UPLOADS_DIRECTORY_NAME
    }

    /** The names of the files being written for uploads not yet queued. */
    private val staging = AtomicReference<Set<String>>(emptySet())

    /**
     * Creates the directory and asks the platform to leave it out of device backups, as the file
     * cache's directories are. Best effort: a failure is logged at DEBUG, and the directory is
     * created on the first upload instead.
     */
    fun prepareDirectory() {
        try {
            fileSystem.createDirectories(directory)
            excludeFromBackup(directory)
        } catch (failure: IOException) {
            engineLogger.debug { "Queued uploads could not prepare their directory: $failure" }
        }
    }

    /** Whether the directory is there: until an upload is queued, or it is prepared, it is not. */
    suspend fun directoryExists(): Boolean =
        withContext(ioDispatcher) {
            try {
                fileSystem.metadataOrNull(directory)?.isDirectory == true
            } catch (_: IOException) {
                false
            }
        }

    /** The file named [name] in the directory. */
    fun fileOf(name: String): Path = directory / name

    /**
     * Writes [bytes] to a new file, through to the disk and then renamed into place, and keeps it
     * staged until [endStaging]: the caller queues the upload that names it in between.
     *
     * @return the file's name.
     * @throws IOException when the file cannot be written; nothing is left behind.
     */
    suspend fun stage(bytes: ByteArray): String {
        val name = newQueuedUploadFileName()
        staging.update { names -> names + name }
        try {
            withContext(ioDispatcher + NonCancellable) { writeWhole(name, bytes) }
        } catch (failure: Throwable) {
            endStaging(name)
            throw failure
        }
        return name
    }

    /** Ends the staging of [name]: its upload is queued now, or it was deleted. */
    fun endStaging(name: String) {
        staging.update { names -> names - name }
    }

    /** [stage]'s write: the partial file, its sync, then the rename. */
    private fun writeWhole(name: String, bytes: ByteArray) {
        val partial = directory / "$name$PARTIAL_SUFFIX"
        try {
            fileSystem.createDirectories(directory)
            fileSystem.sink(partial, mustCreate = true).buffer().use { sink -> sink.write(bytes) }
            try {
                syncFile(partial)
            } catch (failure: IOException) {
                // A size check on the send catches what a power loss leaves of the file
                engineLogger.debug { "Queued uploads could not sync a file to disk: $failure" }
            }
            fileSystem.atomicMove(partial, fileOf(name))
        } catch (failure: IOException) {
            deleteQuietly(partial)
            throw failure
        }
    }

    /**
     * The bytes of the queued file [name], or `null` when it is gone, or holds another size than
     * [expectedSize], as a power loss right after it was renamed can leave it.
     */
    suspend fun read(name: String, expectedSize: Long): ByteArray? =
        withContext(ioDispatcher) {
            val file = fileOf(name)
            try {
                val size = fileSystem.metadataOrNull(file)?.size ?: return@withContext null
                if (size != expectedSize) return@withContext null
                fileSystem.source(file).buffer().use { source -> source.readByteArray() }
            } catch (failure: IOException) {
                engineLogger.debug { "Queued uploads could not read a file: $failure" }
                null
            }
        }

    /** The size on disk of the queued file [name], or `null` when it is not there. */
    suspend fun sizeOf(name: String): Long? =
        withContext(ioDispatcher) {
            try {
                fileSystem.metadataOrNull(fileOf(name))?.takeIf { it.isRegularFile }?.size
            } catch (_: IOException) {
                null
            }
        }

    /** Deletes the queued file [name], if it is there. A failure is logged, never thrown. */
    suspend fun delete(name: String) {
        withContext(ioDispatcher + NonCancellable) { deleteQuietly(fileOf(name)) }
    }

    /**
     * Deletes every file of the directory but those [named] and those staged, partial files of a
     * staged one included. Call it under the queue lock, with [named] read under the same hold.
     *
     * @param named the names the queue and the rejected list hold.
     * @return how many files it deleted.
     */
    suspend fun deleteAllExcept(named: Set<String>): Int =
        withContext(ioDispatcher + NonCancellable) {
            val spared = named + staging.load()
            val files =
                try {
                    if (!fileSystem.exists(directory)) emptyList() else fileSystem.list(directory)
                } catch (failure: IOException) {
                    engineLogger.debug { "Queued uploads could not list their directory: $failure" }
                    emptyList()
                }
            var deleted = 0
            files.forEach { file ->
                if (file.name.removeSuffix(PARTIAL_SUFFIX) in spared) return@forEach
                if (fileSystem.metadataOrNull(file)?.isDirectory == true) {
                    try {
                        fileSystem.deleteRecursively(file, mustExist = false)
                        deleted++
                    } catch (failure: IOException) {
                        engineLogger.debug { "Queued uploads could not delete a file: $failure" }
                    }
                } else if (deleteQuietly(file)) {
                    deleted++
                }
            }
            deleted
        }

    /** Deletes [path], logging a failure; whether it did. */
    private fun deleteQuietly(path: Path): Boolean =
        try {
            fileSystem.delete(path, mustExist = false)
            true
        } catch (failure: IOException) {
            engineLogger.debug { "Queued uploads could not delete a file: $failure" }
            false
        }
}
