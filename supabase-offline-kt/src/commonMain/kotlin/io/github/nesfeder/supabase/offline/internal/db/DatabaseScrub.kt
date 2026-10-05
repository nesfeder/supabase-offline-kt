// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.Transactor
import androidx.room.execSQL
import androidx.room.useWriterConnection
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Rewrites the database without the rows a clear deleted (`VACUUM`), then tries once to empty its
 * write-ahead log; see [emptyWriteAheadLog].
 *
 * @return whether the write-ahead log was emptied.
 */
internal suspend fun OfflineDatabase.rewriteWithoutDeletedRows(): Boolean =
    useWriterConnection { connection ->
        connection.execSQL("VACUUM")
        connection.tryTruncatingCheckpoint()
    }

/**
 * Tries once to copy the write-ahead log into the database and empty it, which an open read holds
 * off. It never waits for that read: Room's busy timeout would otherwise hold its writer for
 * seconds.
 *
 * @return whether the log was emptied.
 */
internal suspend fun OfflineDatabase.emptyWriteAheadLog(): Boolean =
    useWriterConnection { connection ->
        connection.tryTruncatingCheckpoint()
    }

/**
 * One `TRUNCATE` checkpoint with the connection's busy timeout set to zero for its length, so that
 * a read holding the log fails it at once instead of after the timeout.
 */
private suspend fun Transactor.tryTruncatingCheckpoint(): Boolean {
    val busyTimeout =
        usePrepared("PRAGMA busy_timeout") { statement ->
            statement.step()
            statement.getLong(0)
        }
    execSQL("PRAGMA busy_timeout = 0")
    try {
        val blocked =
            usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { statement ->
                statement.step()
                statement.getLong(0)
            }
        return blocked == 0L
    } finally {
        execSQL("PRAGMA busy_timeout = $busyTimeout")
    }
}

/**
 * Makes sure the rows a clear or a cache drop deleted cannot be read back from the database's
 * files, best effort: what an open read holds is finished outside the clear's locks, then in the
 * background, then at close. It never fails its caller and never waits without bound.
 *
 * @param scope where the background retries run; canceling it stops them.
 * @param engineLogger where a read holding the log, and a failure, are reported.
 * @param rewrite rewrites the database and tries once to empty the log; whether it emptied it.
 * @param emptyLog tries once to empty the log, waiting for no read; whether it emptied it.
 */
internal class DatabaseScrub(
    private val scope: CoroutineScope,
    private val engineLogger: EngineLogger,
    private val rewrite: suspend () -> Boolean,
    private val emptyLog: suspend () -> Boolean,
    private val briefWait: Duration = BRIEF_WAIT,
    private val retryInterval: Duration = RETRY_INTERVAL,
    private val longestRetryInterval: Duration = LONGEST_RETRY_INTERVAL,
) {

    /** What is left to do so the deleted rows cannot be read back. */
    private enum class Remaining {
        NOTHING,
        EMPTY_LOG,
        REWRITE,
    }

    private val remaining = AtomicReference(Remaining.NOTHING)

    /** Taken by the one background retry running. */
    private val retrying = AtomicBoolean(false)

    /** Taken by the first failure since the last rewrite, the one that is logged. */
    private val failureReported = AtomicBoolean(false)

    /**
     * Rewrites the database once rows are deleted, under the caller's locks: one rewrite and one
     * try at the log, which waits for no read. Throws only a cancellation.
     */
    suspend fun rewriteAfterDelete() {
        failureReported.store(false)
        remaining.store(Remaining.REWRITE)
        step()
    }

    /**
     * Outside the caller's locks: waits, for at most a second, for what [rewriteAfterDelete] left,
     * then leaves the rest to background retries, its caller canceled or not. Throws only a
     * cancellation.
     */
    suspend fun finishSoon() {
        if (remaining.load() == Remaining.NOTHING) return
        var done = false
        try {
            done =
                withTimeoutOrNull(briefWait) {
                    while (!step()) delay(retryInterval)
                    true
                } != null
        } finally {
            if (!done) {
                engineLogger.info {
                    "An open read holds the database's write-ahead log after a clear; the " +
                        "deleted rows are removed from it once the read ends"
                }
                retryInBackground()
            }
        }
    }

    /**
     * Tries once more, at close, once nothing else uses the database; logs what it could not do.
     * Throws only a cancellation.
     */
    suspend fun finishBeforeClose() {
        if (remaining.load() == Remaining.NOTHING || step()) return
        engineLogger.warning {
            "An open read held the database's write-ahead log until close; the rows a clear deleted " +
                "may stay in it until its next checkpoint"
        }
    }

    /**
     * Retries in [scope], each wait doubling up to [longestRetryInterval], until nothing is left.
     */
    private fun retryInBackground() {
        if (!retrying.compareAndSet(expectedValue = false, newValue = true)) return
        scope.launch {
            try {
                var wait = retryInterval
                while (!step()) {
                    delay(wait)
                    wait = minOf(wait * 2, longestRetryInterval)
                }
                engineLogger.debug { "The database's write-ahead log was emptied after the clear" }
            } finally {
                retrying.store(false)
            }
            // A clear that left work while this one was ending found it running
            if (remaining.load() != Remaining.NOTHING) retryInBackground()
        }
    }

    /** One try at what is left. Throws only a cancellation. */
    private suspend fun step(): Boolean {
        val before = remaining.load()
        val after =
            try {
                when (before) {
                    Remaining.NOTHING -> Remaining.NOTHING
                    Remaining.REWRITE -> if (rewrite()) Remaining.NOTHING else Remaining.EMPTY_LOG
                    Remaining.EMPTY_LOG ->
                        if (emptyLog()) Remaining.NOTHING else Remaining.EMPTY_LOG
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (failureReported.compareAndSet(expectedValue = false, newValue = true)) {
                    engineLogger.warning {
                        "Rewriting the database after a clear failed " +
                            "(${failure::class.simpleName}); it is tried again"
                    }
                }
                before
            }
        // A clear since this try began asked for a rewrite of its own, which this did not do
        remaining.compareAndSet(before, after)
        return remaining.load() == Remaining.NOTHING
    }

    private companion object {
        /** How long a clear waits, outside its locks, for a read that holds the log. */
        val BRIEF_WAIT: Duration = 1.seconds

        /** How long between tries, at first. */
        val RETRY_INTERVAL: Duration = 50.milliseconds

        /** The longest wait between background tries. */
        val LONGEST_RETRY_INTERVAL: Duration = 5.seconds
    }
}
