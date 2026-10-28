// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.DownloadProgress
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * How a shared download ended for one of its callers.
 *
 * @property joined whether the caller joined a download another caller had started, rather than
 *   start it: the request was that caller's — its URL, headers and session — so a failure of it may
 *   not be this caller's.
 */
internal sealed interface SharedOutcome {
    val joined: Boolean

    /** The download ran to its commit, which said [outcome]. */
    class Ended(val outcome: CommitOutcome, override val joined: Boolean) : SharedOutcome

    /** The download threw [cause]. */
    class Failed(val cause: Throwable, override val joined: Boolean) : SharedOutcome
}

/**
 * The downloads of cold objects in flight for `ensureCachedFile`, one per cache entry. Concurrent
 * callers for one object wait for the download the first of them started instead of each streaming
 * the whole object to a file of its own, and a bytes or channel download of the same entry waits
 * for it too ([join]). A download runs in the client's scope, on its dispatcher, so no one caller's
 * cancellation ends it: it is canceled once its last waiter has, and that waiter returns only once
 * the download has stopped and cleaned up. A download that has ended, however it ended, is never
 * joined: a caller arriving after it starts afresh. Nor is one the cache has [forgotten][forget] —
 * its object written, or local data cleared — while the callers already waiting for it keep their
 * place, and the last of them to cancel still stops it.
 *
 * A canceled waiter stops the download only once it runs again, to leave it, and its dispatcher may
 * run it after the download has finished. So the download asks, just before it commits, whether any
 * waiter is still uncanceled ([joinOrStart]'s `ensureWanted`), and stops there when none is: once
 * every caller's cancellation has reached it, the download commits nothing, however late their
 * dispatchers run them again.
 *
 * Downloads are keyed by their cache entry's key, which for the untransformed object is the
 * object's own row key, so the cache forgets a written object's download by that key.
 *
 * A download reports its progress to its [DownloadProgressRelay], and each caller that asked for
 * progress watches it, joined late or not; while none watches, nothing is published.
 *
 * @param onRegistered runs once a caller has registered a download of its own and before it starts
 *   it, with the entry key; a test acts there, in the window another caller may land in.
 * @param onProgressPublished runs each time a download publishes its progress to the callers
 *   watching it; a test counts there.
 */
internal class InFlightDownloads(
    private val onRegistered: (key: String) -> Unit = {},
    private val onProgressPublished: () -> Unit = {},
) {

    /**
     * One caller waiting for a download.
     *
     * @property job the caller's job; `null` for a caller without one, which nothing cancels.
     */
    private class Waiter(val job: Job?) {
        /** Whether the caller still wants the download: it has not been canceled. */
        val isWaiting: Boolean
            get() = job?.isActive != false
    }

    /**
     * One download in flight.
     *
     * @property outcome the download, whose result is every waiter's.
     * @property waiters the callers waiting for it.
     * @property progress the download's progress, which the waiters that asked for it watch.
     */
    private class Download(
        val outcome: Deferred<Result<CommitOutcome>>,
        val waiters: List<Waiter>,
        val progress: DownloadProgressRelay,
    )

    /**
     * The register. Replaced whole, so a look at it needs no lock.
     *
     * @property joinable entry key → the download in flight for it, which a caller joins.
     * @property forgotten the downloads with callers still waiting that none joins any more: the
     *   cache forgot them, or every caller had been canceled when they came to commit.
     */
    private class State(val joinable: Map<String, Download>, val forgotten: List<Download>)

    private val state = AtomicReference(State(emptyMap(), emptyList()))

    /** Whether a download of the entry keyed [key] is in flight, which [join] would wait for. */
    fun isInFlight(key: String): Boolean = state.load().joinable[key]?.outcome?.isRunning == true

    /**
     * How many callers are waiting for the download of the entry keyed [key]; `0` when none is in
     * flight.
     */
    fun waitersOf(key: String): Int =
        state.load().joinable[key]?.takeIf { it.outcome.isRunning }?.waiters?.size ?: 0

    /**
     * Waits for the download of the entry keyed [key] in flight or, when there is none, runs
     * [download] as that download in [scope] and waits for it. A cancellation of the download — the
     * scope's, as no one waiter's reaches it — is thrown; any other failure is handed back.
     *
     * @param onProgress handed the download's progress while this caller waits, in this caller's
     *   coroutine ([DownloadProgressRelay.watching]); `null` to watch none.
     * @param download the download, run on its progress relay, to which it reports each chunk. It
     *   calls the `ensureWanted` it is handed just before it commits, which throws a
     *   [CancellationException] when every caller waiting for it has been canceled — some of them
     *   perhaps not yet run again to leave it — and from then on lets no caller join it.
     * @return how the download ended for this caller.
     */
    suspend fun joinOrStart(
        key: String,
        scope: CoroutineScope,
        onProgress: ((DownloadProgress) -> Unit)? = null,
        download: suspend DownloadProgressRelay.(ensureWanted: () -> Unit) -> CommitOutcome,
    ): SharedOutcome {
        val waiter = Waiter(currentCoroutineContext()[Job])
        val progress = DownloadProgressRelay(onProgressPublished)
        // Not started before it is registered, so no download runs unregistered; registered and
        // not yet started, it is joined all the same. Its failure is handed back as a value, so
        // it never fails the scope, whatever its job
        val own =
            scope.async(start = CoroutineStart.LAZY) {
                val self = coroutineContext.job
                try {
                    Result.success(progress.download { ensureWanted(self) })
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
            }
        val entered =
            enter(key, own, progress, waiter)
                ?: error("A download to start was neither started nor joined")
        val joined = entered.outcome !== own
        if (joined) {
            own.cancel()
        } else {
            onRegistered(key)
            own.start()
        }
        return awaitLeaving(entered, waiter, joined, onProgress)
    }

    /**
     * Waits for the download of the entry keyed [key] in flight, when there is one.
     *
     * @return how the download ended, or `null` when no download was in flight.
     */
    suspend fun join(key: String): SharedOutcome? {
        val waiter = Waiter(currentCoroutineContext()[Job])
        val entered = enter(key, own = null, progress = null, waiter) ?: return null
        return awaitLeaving(entered, waiter, joined = true, onProgress = null)
    }

    /**
     * Keeps the download of the entry keyed [key], if one is in flight, from being joined from now
     * on: the callers already waiting for it keep their place and get its outcome, and a later
     * caller starts afresh.
     */
    fun forget(key: String) {
        state.update { current ->
            val download = current.joinable[key] ?: return@update current
            State(current.joinable - key, current.forgotten + download)
        }
    }

    /** [forget]s every download in flight. */
    fun forgetAll() {
        state.update { current ->
            if (current.joinable.isEmpty()) current
            else State(emptyMap(), current.forgotten + current.joinable.values)
        }
    }

    /**
     * Counts [waiter] among the waiters of the download in flight for [key], or of [own], reporting
     * to [progress], when none is.
     *
     * @return the download the caller waits for, or `null` when there is none and no [own].
     */
    private fun enter(
        key: String,
        own: Deferred<Result<CommitOutcome>>?,
        progress: DownloadProgressRelay?,
        waiter: Waiter,
    ): Download? {
        var entered: Download? = null
        state.update { current ->
            val inFlight = current.joinable[key]?.takeIf { it.outcome.isRunning }
            val next =
                when {
                    inFlight != null ->
                        Download(inFlight.outcome, inFlight.waiters + waiter, inFlight.progress)
                    own != null && progress != null -> Download(own, listOf(waiter), progress)
                    else -> null
                }
            entered = next
            if (next == null) current
            else State(current.joinable + (key to next), current.forgotten)
        }
        return entered
    }

    /**
     * Waits for [download], handing [onProgress] its progress meanwhile, and takes [waiter] off its
     * waiters however the wait ends.
     */
    private suspend fun awaitLeaving(
        download: Download,
        waiter: Waiter,
        joined: Boolean,
        onProgress: ((DownloadProgress) -> Unit)?,
    ): SharedOutcome =
        try {
            val result =
                if (onProgress == null) download.outcome.await()
                else download.progress.watching(onProgress) { download.outcome.await() }
            result.fold(
                onSuccess = { outcome -> SharedOutcome.Ended(outcome, joined) },
                onFailure = { failure -> SharedOutcome.Failed(failure, joined) },
            )
        } finally {
            withContext(NonCancellable) { leave(download.outcome, waiter) }
        }

    /**
     * Throws a [CancellationException] when no caller waiting for [download] is still uncanceled,
     * and moves the download where no caller joins it, so none joins a download that is stopping;
     * the canceled callers keep their place, to leave it. A download no caller waits for any more —
     * the last one has left, and canceled it — throws too.
     */
    private fun ensureWanted(download: Job) {
        var wanted = false
        state.update { current ->
            val key = current.keyOf(download)
            val held =
                if (key != null) current.joinable.getValue(key)
                else current.forgotten.firstOrNull { forgotten -> forgotten.outcome === download }
            wanted = held != null && held.waiters.any { waiter -> waiter.isWaiting }
            // A joinable download no caller wants goes where none joins it; one already there, or
            // gone from the register, stays as it is
            if (wanted || key == null || held == null) current
            else State(current.joinable - key, current.forgotten + held)
        }
        if (!wanted) {
            throw CancellationException("Every caller waiting for this download was canceled")
        }
    }

    /**
     * Takes [waiter] off [download]'s waiters, wherever the register holds it. The last one to
     * leave a download still running — every caller canceled, then — cancels it and waits for it to
     * stop.
     */
    private suspend fun leave(download: Deferred<Result<CommitOutcome>>, waiter: Waiter) {
        var last = false
        state.update { current ->
            val key = current.keyOf(download)
            if (key != null) {
                val others = current.joinable.getValue(key).waiters - waiter
                last = others.isEmpty()
                val held = current.joinable.getValue(key)
                return@update if (last) State(current.joinable - key, current.forgotten)
                else
                    State(
                        current.joinable + (key to Download(download, others, held.progress)),
                        current.forgotten,
                    )
            }
            val index = current.forgotten.indexOfFirst { held -> held.outcome === download }
            if (index < 0) {
                last = false
                return@update current
            }
            val held = current.forgotten[index]
            val others = held.waiters - waiter
            last = others.isEmpty()
            val rest = current.forgotten.filterIndexed { position, _ -> position != index }
            State(
                current.joinable,
                if (last) rest else rest + Download(download, others, held.progress),
            )
        }
        if (last && !download.isCompleted) download.cancelAndJoin()
    }

    /** The key [download] is joinable under, or `null` when it is not joinable. */
    private fun State.keyOf(download: Job): String? =
        joinable.entries.firstOrNull { (_, held) -> held.outcome === download }?.key

    /**
     * Whether this download may still give an outcome: not ended, and not being canceled. A
     * download registered but not yet started counts, so no caller starts a second one beside it.
     */
    private val Deferred<*>.isRunning: Boolean
        get() = !isCompleted && !isCancelled
}
