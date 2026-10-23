// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.internal.newEngineScope
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.FakeFailingFileSystem
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestFileCache
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.ktor.utils.io.ByteChannel
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A download whose caller is canceled leaves no file behind, wherever the cancellation lands: while
 * the cache is still creating the download's file, or while it is committing a finished one. The
 * caller returns only once that file is gone. Each way in: bytes, a channel, and
 * `ensureCachedFile`. The file system holds its operation until the caller's cancellation has
 * reached the call's own work, so the cancellation always lands there, however the threads run.
 *
 * `ensureCachedFile`'s download is shared, and runs in the client's scope: a caller's cancellation
 * reaches it only once the caller's work runs again to leave it. A download that comes to commit
 * before then leaves no file either.
 */
class StorageCanceledDownloadTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: TestStore
    private lateinit var delegate: FakeBucketApi
    private val fileSystem = FakeFailingFileSystem()
    private val engine = newEngineScope(engineLogger = null)

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-canceled-${Random.nextLong().toULong()}"
        store = newTestStore()
        delegate = FakeBucketApi(bucketId = BUCKET)
        delegate.onDownload = { CONTENT }
    }

    @AfterTest
    fun tearDown() {
        engine.cancel()
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun bucket() =
        newTestBucketApi(
            delegate = delegate,
            baseDirectory = dataDirectory,
            store = store,
            backgroundScope = engine,
            cache =
                newTestFileCache(
                    baseDirectory = dataDirectory,
                    store = store,
                    fileSystem = fileSystem,
                ),
        )

    @Test
    fun a_download_canceled_while_its_file_is_being_created_leaves_no_file() = runTest {
        // Given — the caller is canceled while the cache is still creating the download's file
        lateinit var caller: Job
        fileSystem.onSinkOpened = { cancelAndHoldUntilItReachesTheWork(caller) }

        // When
        val outcomes = DOWNLOADS.map { (name, download) ->
            name to canceledDownload(download) { job -> caller = job }
        }

        // Then — every call ended canceled, and the file it had just created went with it
        assertEquals(DOWNLOADS.map { (name, _) -> name to CANCELED_AND_CLEAN }, outcomes)
    }

    @Test
    fun a_download_canceled_while_the_cache_refuses_its_file_leaves_no_file() = runTest {
        // Given — the whole object is on disk, the file system refuses to move it into place, and
        // the caller is canceled while the cache is trying
        fileSystem.failMoves = true
        lateinit var caller: Job
        fileSystem.onMove = { _, _ -> cancelAndHoldUntilItReachesTheWork(caller) }

        // When
        val outcomes = DOWNLOADS.map { (name, download) ->
            name to canceledDownload(download) { job -> caller = job }
        }

        // Then — every call ended canceled, and the finished file the cache could not keep is
        // gone as well
        assertEquals(DOWNLOADS.map { (name, _) -> name to CANCELED_AND_CLEAN }, outcomes)
    }

    @Test
    fun a_shared_download_whose_caller_has_not_run_since_its_cancellation_leaves_no_file() =
        runTest {
            // Given — an ensureCachedFile caller on a dispatcher that runs it only when told to. It
            // is canceled while the cache is creating the download's file, and run once, so its
            // cancellation reaches its call's work; that work is not run again, to leave the
            // download and stop it, until the download has ended
            val cache =
                newTestFileCache(
                    baseDirectory = dataDirectory,
                    store = store,
                    fileSystem = fileSystem,
                )
            val bucket =
                newTestBucketApi(
                    delegate = delegate,
                    baseDirectory = dataDirectory,
                    store = store,
                    backgroundScope = engine,
                    cache = cache,
                )
            val entryKey = assertNotNull(cache.entryFor(BUCKET, PATH)).key
            val callerDispatcher = HeldDispatcher()
            val waiting = AtomicBoolean(false)
            val canceled = AtomicBoolean(false)
            var caught: Throwable? = null
            val caller =
                CoroutineScope(callerDispatcher).launch {
                    try {
                        bucket.ensureCachedFile(PATH)
                    } catch (failure: Throwable) {
                        caught = failure
                    }
                }
            fileSystem.onSinkOpened = {
                spinUntil { waiting.load() }
                caller.cancel()
                check(callerDispatcher.runNext()) { "The caller's cancellation never reached it" }
                canceled.store(true)
            }

            // When — the caller runs until it waits for its download, then not until the download
            // has ended
            spinUntil {
                callerDispatcher.runNext()
                cache.downloadsInFlight.waitersOf(entryKey) == 1
            }
            waiting.store(true)
            spinUntil { canceled.load() && !cache.downloadsInFlight.isInFlight(entryKey) }
            spinUntil {
                callerDispatcher.runNext()
                caller.isCompleted
            }

            // Then — the call ended canceled, and the download committed nothing
            assertIs<CancellationException>(caught)
            assertEquals(emptyList(), regularFilesUnder(dataDirectory))
        }

    /**
     * A dispatcher that runs nothing until told to: [runNext] runs the oldest task it holds, on the
     * calling thread.
     */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val tasks = Channel<Runnable>(Channel.UNLIMITED)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.trySend(block)
        }

        /** Runs the oldest task held; `false` when there is none. */
        fun runNext(): Boolean {
            val task = tasks.tryReceive().getOrNull() ?: return false
            task.run()
            return true
        }
    }

    /** Spins on real time until [condition] holds, and fails past [HOLD_LIMIT]. */
    private fun spinUntil(condition: () -> Boolean) {
        val started = TimeSource.Monotonic.markNow()
        while (!condition()) {
            check(started.elapsedNow() < HOLD_LIMIT) { "The condition never held" }
        }
    }

    /**
     * Runs [download] from an empty cache in a child the file system's hook cancels, once the call
     * has returned: how it ended, and how many files it left.
     */
    private suspend fun CoroutineScope.canceledDownload(
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
        val ended = if (caught is CancellationException) "canceled" else "ended with $caught"
        return "$ended, ${regularFilesUnder(dataDirectory).size} file(s) left"
    }

    /**
     * Cancels [caller], then holds the file operation in progress until that cancellation has
     * reached the call's own work, which runs in [engine]. Without the hold, work that finishes
     * before its caller next suspends completes as if never canceled, and the test proves nothing.
     */
    private fun cancelAndHoldUntilItReachesTheWork(caller: Job) {
        caller.cancel()
        val started = TimeSource.Monotonic.markNow()
        while (engine.coroutineContext.job.children.none { work -> work.isCancelled }) {
            check(started.elapsedNow() < HOLD_LIMIT) {
                "The caller's cancellation never reached its work"
            }
        }
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
        const val CANCELED_AND_CLEAN = "canceled, 0 file(s) left"
        val HOLD_LIMIT = 5.seconds

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
