// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.feedback

import io.github.nesfeder.offlinetasks.data.AppSyncStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * The failures of work the screens started, waiting to be shown. There is one for the whole app: a
 * write can fail after the screen that made it has closed, so the app shows each failure, once, on
 * whichever screen is in front by then.
 */
class FailureReports {
    private val failures = Channel<Throwable>(Channel.UNLIMITED)

    /** Each reported failure, delivered once. */
    val reported: Flow<Throwable> = failures.receiveAsFlow()

    /** Reports [failure], to be shown to the user. */
    fun report(failure: Throwable) {
        failures.trySend(failure)
    }
}

/**
 * Launches [block]; a failure is reported to [failures], to be shown as a message, instead of
 * crashing the app or vanishing. Cancellation is not a failure.
 */
fun CoroutineScope.launchReporting(
    failures: FailureReports,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch {
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        failures.report(failure)
    }
}

/**
 * This read, with its failure reported to [failures], to be shown as a message, instead of crashing
 * the app: the read ends after its last value, so a screen built on it keeps what it last showed.
 * Cancellation is not a failure, and an [Error] is not caught.
 */
fun <T> Flow<T>.reportingFailures(failures: FailureReports): Flow<T> = catch { failure ->
    when (failure) {
        is CancellationException -> throw failure
        is Exception -> failures.report(failure)
        else -> throw failure
    }
}

/**
 * This live read, which a failure does not end: the failure is reported to [failures] (null when
 * another read of the same rows reports it), and the read starts again once [readAgain] returns, so
 * a list built on it keeps updating. Cancellation is not a failure, and an [Error] is not caught.
 */
fun <T> Flow<T>.readingAgainAfterFailures(
    failures: FailureReports?,
    readAgain: suspend () -> Unit,
): Flow<T> = retryWhen { failure, _ ->
    val recovers = failure is Exception && failure !is CancellationException
    if (recovers) {
        failures?.report(failure)
        readAgain()
    }
    recovers
}

/**
 * Returns once the network's state differs from what it is now: offline to online, Simulate offline
 * switched off, or the other way, when a read that failed is worth starting again.
 */
suspend fun StateFlow<AppSyncStatus>.awaitNetworkChange() {
    val now = value.network
    first { it.network != now }
}
