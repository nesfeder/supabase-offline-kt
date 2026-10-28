// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.DownloadProgress
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Stands for a size the server gave none of. */
private const val UNKNOWN_SIZE: Long = -1L

/**
 * The progress of one shared download, which every caller waiting for it that asked for progress
 * watches ([watching]). The download [report]s each chunk it writes; the progress is published only
 * while a caller watches, so a download nobody watches pays two stores and a look per chunk.
 *
 * @param onPublished runs each time a progress is published to the watchers; a test counts there.
 */
internal class DownloadProgressRelay(private val onPublished: () -> Unit = {}) {

    /** How many callers watch. */
    private val watchers = AtomicInt(0)

    /** The bytes received so far, or [UNKNOWN_SIZE] before the first chunk. */
    private val received = AtomicLong(UNKNOWN_SIZE)

    /** The object's size, or [UNKNOWN_SIZE] while the server gave none. */
    private val total = AtomicLong(UNKNOWN_SIZE)

    /** The progress last published; `null` until one is. */
    private val latest = MutableStateFlow<DownloadProgress?>(null)

    /**
     * Records that [bytesReceived] bytes of the object have arrived, of [totalBytes] when the
     * server gave its size, and publishes it while a caller watches.
     */
    fun report(bytesReceived: Long, totalBytes: Long?) {
        received.store(bytesReceived)
        total.store(totalBytes ?: UNKNOWN_SIZE)
        if (watchers.load() > 0) publish(DownloadProgress(bytesReceived, totalBytes))
    }

    /**
     * Runs [block] while handing [onProgress] the download's progress, from where it stands when
     * the watch begins. [onProgress] runs in the caller's coroutine, never two at once; a progress
     * that arrives while it runs replaces any it has not been handed yet, and the last one is
     * handed once [block] has returned. What [onProgress] throws ends the watch, and [block], with
     * it.
     *
     * @return what [block] returns.
     */
    suspend fun <T> watching(onProgress: (DownloadProgress) -> Unit, block: suspend () -> T): T {
        watchers.incrementAndFetch()
        try {
            return coroutineScope {
                // A caller joining a download under way starts from where it stands
                snapshot()?.let(::publish)
                var handed: DownloadProgress? = null
                val watch = launch {
                    latest.filterNotNull().collect { progress ->
                        handed = progress
                        onProgress(progress)
                    }
                }
                val result = block()
                watch.cancelAndJoin()
                latest.value?.takeIf { last -> last != handed }?.let(onProgress)
                result
            }
        } finally {
            watchers.decrementAndFetch()
        }
    }

    /** Where the download stands, or `null` before its first chunk. */
    private fun snapshot(): DownloadProgress? {
        val bytes = received.load().takeIf { it != UNKNOWN_SIZE } ?: return null
        return DownloadProgress(bytes, total.load().takeIf { it != UNKNOWN_SIZE })
    }

    private fun publish(progress: DownloadProgress) {
        latest.value = progress
        onPublished()
    }
}
