// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.FakeFailingFileSystem
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestFileCache
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.utils.io.ByteChannel
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A download whose caller is cancelled leaves no file behind, wherever the cancellation lands:
 * while the cache is still creating the download's file, or while it is committing a finished one.
 * The caller returns only once that file is gone. Each way in: bytes, a channel, and
 * `ensureCachedFile`.
 */
class StorageCancelledDownloadTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: OfflineStore
    private lateinit var delegate: FakeBucketApi
    private val fileSystem = FakeFailingFileSystem()

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-cancelled-${Random.nextLong().toULong()}"
        store = newTestOfflineStore()
        delegate = FakeBucketApi(bucketId = BUCKET)
        delegate.onDownload = { CONTENT }
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun bucket() =
        newTestBucketApi(
            delegate = delegate,
            baseDirectory = dataDirectory,
            store = store,
            cache =
                newTestFileCache(
                    baseDirectory = dataDirectory,
                    store = store,
                    fileSystem = fileSystem,
                ),
        )

    @Test
    fun a_download_cancelled_while_its_file_is_being_created_leaves_no_file() = runTest {
        // Given — the caller is cancelled while the cache is still creating the download's file
        lateinit var caller: Job
        fileSystem.onSinkOpened = { caller.cancel() }

        // When
        val outcomes = DOWNLOADS.map { (name, download) ->
            name to cancelledDownload(download) { job -> caller = job }
        }

        // Then — every call ended cancelled, and the file it had just created went with it
        assertEquals(DOWNLOADS.map { (name, _) -> name to CANCELLED_AND_CLEAN }, outcomes)
    }

    @Test
    fun a_download_cancelled_while_the_cache_refuses_its_file_leaves_no_file() = runTest {
        // Given — the whole object is on disk, the file system refuses to move it into place, and
        // the caller is cancelled while the cache is trying
        fileSystem.failMoves = true
        lateinit var caller: Job
        fileSystem.onMove = { _, _ -> caller.cancel() }

        // When
        val outcomes = DOWNLOADS.map { (name, download) ->
            name to cancelledDownload(download) { job -> caller = job }
        }

        // Then — every call ended cancelled, and the finished file the cache could not keep is
        // gone as well
        assertEquals(DOWNLOADS.map { (name, _) -> name to CANCELLED_AND_CLEAN }, outcomes)
    }

    /**
     * Runs [download] from an empty cache in a child the file system's hook cancels, once the call
     * has returned: how it ended, and how many files it left.
     */
    private suspend fun CoroutineScope.cancelledDownload(
        download: suspend (OfflineBucketApi) -> Unit,
        started: (Job) -> Unit,
    ): String {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
        var caught: Throwable? = null
        val job = launch {
            try {
                download(bucket())
            } catch (failure: Throwable) {
                caught = failure
            }
        }
        started(job)
        job.join()
        val ended = if (caught is CancellationException) "cancelled" else "ended with $caught"
        return "$ended, ${regularFilesUnder(dataDirectory).size} file(s) left"
    }

    private fun regularFilesUnder(directory: Path): List<Path> =
        if (!FileSystem.SYSTEM.exists(directory)) {
            emptyList()
        } else {
            FileSystem.SYSTEM.listRecursively(directory)
                .filter { FileSystem.SYSTEM.metadata(it).isRegularFile }
                .toList()
        }

    private companion object {
        const val BUCKET = "videos"
        const val PATH = "u-1/clip.mp4"
        val CONTENT = ByteArray(300) { index -> (index % 251).toByte() }
        const val CANCELLED_AND_CLEAN = "cancelled, 0 file(s) left"

        val DOWNLOADS: List<Pair<String, suspend (OfflineBucketApi) -> Unit>> =
            listOf(
                "downloadAuthenticated(bytes)" to
                    { bucket ->
                        bucket.downloadAuthenticated(PATH) {}
                    },
                "downloadAuthenticated(channel)" to
                    { bucket ->
                        bucket.downloadAuthenticated(PATH, ByteChannel()) {}
                    },
                "ensureCachedFile" to { bucket -> bucket.ensureCachedFile(PATH) },
            )
    }
}
