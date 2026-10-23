// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * The downloads of cold objects in flight for `ensureCachedFile`, one per cache entry. Concurrent
 * callers for one object wait for the download the first of them started instead of each streaming
 * the whole object to a file of its own, and a bytes or channel download of the same entry waits
 * for it too ([join]). A download runs in the client's scope, so no one caller's cancellation ends
 * it: it is canceled once its last waiter has, and that waiter returns only once the download has
 * stopped and cleaned up. A download that has ended, however it ended, is never joined: a caller
 * arriving after it starts afresh, so one a write superseded is not waited for twice. A caller that
 * joins a download a clear voids is told so, like the one that started it.
 */
internal class InFlightDownloads {

    /**
     * One download in flight.
     *
     * @property outcome the download, whose result is every waiter's.
     * @property waiters how many callers are waiting for it.
     */
    private class Download(val outcome: Deferred<CommitOutcome>, val waiters: Int)

    /** Entry key → the download in flight for it. Replaced whole, so a look at it needs no lock. */
    private val downloads = AtomicReference<Map<String, Download>>(emptyMap())

    /** Whether a download of the entry keyed [key] is in flight, which [join] would wait for. */
    fun isInFlight(key: String): Boolean = downloads.load()[key]?.outcome?.isActive == true

    /**
     * How many callers are waiting for the download of the entry keyed [key]; `0` when none is in
     * flight.
     */
    fun waitersOf(key: String): Int =
        downloads.load()[key]?.takeIf { it.outcome.isActive }?.waiters ?: 0

    /**
     * Waits for the download of the entry keyed [key] in flight or, when there is none, runs
     * [download] as that download in [scope], on the caller's dispatcher, and waits for it.
     *
     * @return what the download's commit said.
     */
    suspend fun joinOrStart(
        key: String,
        scope: CoroutineScope,
        download: suspend () -> CommitOutcome,
    ): CommitOutcome {
        val own =
            scope.async(currentCoroutineContext().minusKey(Job), start = CoroutineStart.LAZY) {
                download()
            }
        val entered = enter(key, own) ?: error("A download to start was neither started nor joined")
        if (entered === own) own.start() else own.cancel()
        return awaitLeaving(key, entered)
    }

    /**
     * Waits for the download of the entry keyed [key] in flight, when there is one.
     *
     * @return what the download's commit said, or `null` when no download was in flight.
     */
    suspend fun join(key: String): CommitOutcome? {
        val entered = enter(key, own = null) ?: return null
        return awaitLeaving(key, entered)
    }

    /**
     * Counts the caller among the waiters of the download in flight for [key], or of [own] when
     * none is.
     *
     * @return the download the caller waits for, or `null` when there is none and no [own].
     */
    private fun enter(key: String, own: Deferred<CommitOutcome>?): Deferred<CommitOutcome>? {
        var entered: Download? = null
        downloads.update { current ->
            val inFlight = current[key]?.takeIf { it.outcome.isActive }
            val next =
                when {
                    inFlight != null -> Download(inFlight.outcome, inFlight.waiters + 1)
                    own != null -> Download(own, 1)
                    else -> null
                }
            entered = next
            if (next == null) current else current + (key to next)
        }
        return entered?.outcome
    }

    /** Waits for [download], and leaves its waiters however the wait ends. */
    private suspend fun awaitLeaving(
        key: String,
        download: Deferred<CommitOutcome>,
    ): CommitOutcome =
        try {
            download.await()
        } finally {
            withContext(NonCancellable) { leave(key, download) }
        }

    /**
     * Takes the caller off [download]'s waiters. The last one to leave a download still running —
     * every caller canceled, then — cancels it and waits for it to stop.
     */
    private suspend fun leave(key: String, download: Deferred<CommitOutcome>) {
        var last = false
        downloads.update { current ->
            val inFlight = current[key]?.takeIf { it.outcome === download }
            if (inFlight == null) {
                last = false
                return@update current
            }
            last = inFlight.waiters == 1
            if (last) current - key else current + (key to Download(download, inFlight.waiters - 1))
        }
        if (last && download.isActive) download.cancelAndJoin()
    }
}
