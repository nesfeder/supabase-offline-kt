// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
 * Downloads are keyed by their cache entry's key, which for the untransformed object is the
 * object's own row key, so the cache forgets a written object's download by that key.
 *
 * @param onRegistered runs once a caller has registered a download of its own and before it starts
 *   it, with the entry key; a test acts there, in the window another caller may land in.
 */
internal class InFlightDownloads(private val onRegistered: (key: String) -> Unit = {}) {

    /**
     * One download in flight.
     *
     * @property outcome the download, whose result is every waiter's.
     * @property waiters how many callers are waiting for it.
     */
    private class Download(val outcome: Deferred<Result<CommitOutcome>>, val waiters: Int)

    /**
     * The register. Replaced whole, so a look at it needs no lock.
     *
     * @property joinable entry key → the download in flight for it, which a caller joins.
     * @property forgotten the forgotten downloads with callers still waiting, which none joins.
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
        state.load().joinable[key]?.takeIf { it.outcome.isRunning }?.waiters ?: 0

    /**
     * Waits for the download of the entry keyed [key] in flight or, when there is none, runs
     * [download] as that download in [scope] and waits for it. A cancellation of the download — the
     * scope's, as no one waiter's reaches it — is thrown; any other failure is handed back.
     *
     * @return how the download ended for this caller.
     */
    suspend fun joinOrStart(
        key: String,
        scope: CoroutineScope,
        download: suspend () -> CommitOutcome,
    ): SharedOutcome {
        // Not started before it is registered, so no download runs unregistered; registered and
        // not yet started, it is joined all the same. Its failure is handed back as a value, so
        // it never fails the scope, whatever its job
        val own =
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    Result.success(download())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
            }
        val entered = enter(key, own) ?: error("A download to start was neither started nor joined")
        if (entered === own) {
            onRegistered(key)
            own.start()
        } else {
            own.cancel()
        }
        return awaitLeaving(entered, joined = entered !== own)
    }

    /**
     * Waits for the download of the entry keyed [key] in flight, when there is one.
     *
     * @return how the download ended, or `null` when no download was in flight.
     */
    suspend fun join(key: String): SharedOutcome? {
        val entered = enter(key, own = null) ?: return null
        return awaitLeaving(entered, joined = true)
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
     * Counts the caller among the waiters of the download in flight for [key], or of [own] when
     * none is.
     *
     * @return the download the caller waits for, or `null` when there is none and no [own].
     */
    private fun enter(
        key: String,
        own: Deferred<Result<CommitOutcome>>?,
    ): Deferred<Result<CommitOutcome>>? {
        var entered: Download? = null
        state.update { current ->
            val inFlight = current.joinable[key]?.takeIf { it.outcome.isRunning }
            val next =
                when {
                    inFlight != null -> Download(inFlight.outcome, inFlight.waiters + 1)
                    own != null -> Download(own, 1)
                    else -> null
                }
            entered = next
            if (next == null) current
            else State(current.joinable + (key to next), current.forgotten)
        }
        return entered?.outcome
    }

    /** Waits for [download], and leaves its waiters however the wait ends. */
    private suspend fun awaitLeaving(
        download: Deferred<Result<CommitOutcome>>,
        joined: Boolean,
    ): SharedOutcome =
        try {
            download
                .await()
                .fold(
                    onSuccess = { outcome -> SharedOutcome.Ended(outcome, joined) },
                    onFailure = { failure -> SharedOutcome.Failed(failure, joined) },
                )
        } finally {
            withContext(NonCancellable) { leave(download) }
        }

    /**
     * Takes the caller off [download]'s waiters, wherever the register holds it. The last one to
     * leave a download still running — every caller canceled, then — cancels it and waits for it to
     * stop.
     */
    private suspend fun leave(download: Deferred<Result<CommitOutcome>>) {
        var last = false
        state.update { current ->
            val key =
                current.joinable.entries.firstOrNull { (_, held) -> held.outcome === download }?.key
            if (key != null) {
                val held = current.joinable.getValue(key)
                last = held.waiters == 1
                return@update if (last) State(current.joinable - key, current.forgotten)
                else
                    State(
                        current.joinable + (key to Download(download, held.waiters - 1)),
                        current.forgotten,
                    )
            }
            val index = current.forgotten.indexOfFirst { held -> held.outcome === download }
            if (index < 0) {
                last = false
                return@update current
            }
            val held = current.forgotten[index]
            last = held.waiters == 1
            val others = current.forgotten.filterIndexed { position, _ -> position != index }
            State(
                current.joinable,
                if (last) others else others + Download(download, held.waiters - 1),
            )
        }
        if (last && !download.isCompleted) download.cancelAndJoin()
    }

    /**
     * Whether this download may still give an outcome: not ended, and not being canceled. A
     * download registered but not yet started counts, so no caller starts a second one beside it.
     */
    private val Deferred<*>.isRunning: Boolean
        get() = !isCompleted && !isCancelled
}
