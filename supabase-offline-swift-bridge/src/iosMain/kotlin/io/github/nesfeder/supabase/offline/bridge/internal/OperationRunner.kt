// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.nesfeder.supabase.offline.bridge.BridgeCancellable
import io.github.nesfeder.supabase.offline.bridge.BridgeError
import io.github.nesfeder.supabase.offline.bridge.BridgeErrorKind
import io.github.nesfeder.supabase.offline.bridge.BridgeLogLevel
import io.github.nesfeder.supabase.offline.bridge.BridgeLogSink
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs every asynchronous bridge call in the bridge's own scope and holds the boundary's promises:
 * each completion fires exactly once, a cancelled handle cancels the work, and no failure, a
 * caller's completion that throws included, ever leaves Kotlin.
 *
 * @param clientClosing whether the client is closing, which turns a cancellation into
 *   [BridgeErrorKind.CLIENT_CLOSED] and refuses new calls.
 */
internal class OperationRunner(
    private val logSink: BridgeLogSink?,
    private val clientClosing: () -> Boolean,
) {
    /** The scope every call runs in; a failure that escapes a call is logged, never thrown. */
    private val scope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, failure -> report(failure, "A bridge call") }
        )

    /**
     * Called inside every call, before its work, with the call's name: the fault-injection tests
     * make it throw to prove each path turns a failure into a value. Always `null` in production.
     */
    @Volatile var faultInjector: ((operation: String) -> Unit)? = null

    /** Logs [failure] at ERROR, by type, as having escaped [where]. */
    fun report(failure: Throwable, where: String) {
        logSafely(
            logSink,
            BridgeLogLevel.ERROR,
            BRIDGE_LOG_TAG,
            "$where failed unexpectedly (${failure::class.simpleName})",
        )
    }

    /** Logs [error], an answer no completion could take, at ERROR as having come from [where]. */
    fun logFailure(error: BridgeError, where: String) {
        logSafely(
            logSink,
            BridgeLogLevel.ERROR,
            BRIDGE_LOG_TAG,
            "$where failed (${error.kind}, ${error.debugType})",
        )
    }

    /**
     * Runs [work] and hands its value, or its failure as an error, to [completion] exactly once.
     *
     * @param operation the call's name, for the fault injector and the log.
     */
    fun <T> runOnce(
        operation: String,
        completion: (T?, BridgeError?) -> Unit,
        work: suspend () -> T,
    ): BridgeCancellable {
        val delivery = OneTimeDelivery(completion) { report(it, "The completion of $operation") }
        if (clientClosing()) {
            delivery.deliver(null, clientClosedError())
            return BridgeCancellable(null, ::reportCancelFailure)
        }
        val job =
            launchCatching(
                delivery,
                endingOf = { cause -> errorOf(cause, ownCancellation = true) },
            ) {
                val value =
                    try {
                        faultInjector?.invoke(operation)
                        work()
                    } catch (failure: Throwable) {
                        delivery.deliver(null, errorOf(failure, ownCancellation = !isActive))
                        if (failure is CancellationException && !isActive) throw failure
                        return@launchCatching
                    }
                delivery.deliver(value, null)
            }
        return BridgeCancellable(job, ::reportCancelFailure)
    }

    /** [runOnce] for work with no value: [completion] gets `null` on success. */
    fun runToCompletion(
        operation: String,
        completion: (BridgeError?) -> Unit,
        work: suspend () -> Unit,
    ): BridgeCancellable = runOnce<Unit>(operation, { _, error -> completion(error) }) { work() }

    /**
     * Collects [values], handing each to [onValue], and ends with [onCompletion] exactly once:
     * `null` when the flow completes or the client closes under it, an error when it fails or the
     * handle is cancelled.
     */
    fun <T> subscribe(
        operation: String,
        onValue: (T) -> Unit,
        onCompletion: (BridgeError?) -> Unit,
        values: () -> Flow<T>,
    ): BridgeCancellable {
        val delivery =
            OneTimeDelivery<Unit>({ _, error -> onCompletion(error) }) {
                report(it, "The completion of $operation")
            }
        if (clientClosing()) {
            delivery.deliver(null, clientClosedError())
            return BridgeCancellable(null, ::reportCancelFailure)
        }
        val job =
            launchCatching(delivery, endingOf = ::subscriptionEndingOf) {
                try {
                    faultInjector?.invoke(operation)
                    values().collect { value ->
                        try {
                            onValue(value)
                        } catch (failure: Throwable) {
                            report(failure, "A value handler of $operation")
                        }
                    }
                } catch (failure: Throwable) {
                    val ownCancellation = failure is CancellationException && !isActive
                    delivery.deliver(
                        null,
                        if (ownCancellation) subscriptionEndingOf(failure)
                        else errorOf(failure, ownCancellation = false),
                    )
                    if (ownCancellation) throw failure
                    return@launchCatching
                }
                delivery.deliver(null, null)
            }
        return BridgeCancellable(job, ::reportCancelFailure)
    }

    /**
     * Answers a synchronous call: [work]'s value, or [onFailure] with the failure as an error.
     * Nothing it runs can throw past it.
     */
    fun <T> answerNow(operation: String, onFailure: (BridgeError) -> T, work: () -> T): T =
        try {
            faultInjector?.invoke(operation)
            work()
        } catch (failure: Throwable) {
            onFailure(errorOf(failure, ownCancellation = false))
        }

    /** Cancels every call still running; their completions fire with the closed error. */
    fun cancelAll() {
        scope.cancel()
    }

    /**
     * Launches [block] in [scope]. A job cancelled before it ran answers through [delivery] with
     * [endingOf] its cause, and one that ended without an answer with an error, once.
     */
    private fun <T> launchCatching(
        delivery: OneTimeDelivery<T>,
        endingOf: (cause: Throwable) -> BridgeError?,
        block: suspend CoroutineScope.() -> Unit,
    ): Job? =
        try {
            scope.launch(block = block).also { job ->
                job.invokeOnCompletion { cause ->
                    val error =
                        if (cause == null) {
                            bridgeError(
                                BridgeErrorKind.UNEXPECTED,
                                "The call ended without an answer",
                                "MissingAnswer",
                            )
                        } else {
                            endingOf(cause)
                        }
                    delivery.deliver(null, error)
                }
            }
        } catch (failure: Throwable) {
            delivery.deliver(null, errorOf(failure, ownCancellation = false))
            null
        }

    /**
     * How a subscription cancelled by [cause] ends: with no error when the client closed under it,
     * as the library ends a live read at close, and as [errorOf] says otherwise.
     */
    private fun subscriptionEndingOf(cause: Throwable): BridgeError? =
        if (cause is CancellationException && clientClosing()) null
        else errorOf(cause, ownCancellation = true)

    /**
     * [failure] as an error. A cancellation of the call itself ([ownCancellation]) is
     * [BridgeErrorKind.CANCELLED], or the closed error once the client is closing.
     */
    private fun errorOf(failure: Throwable, ownCancellation: Boolean): BridgeError {
        val closing = clientClosing()
        return when {
            failure is CancellationException && ownCancellation ->
                if (closing) clientClosedError("CancellationException") else cancelledError()
            else -> bridgeErrorOf(failure, closing)
        }
    }

    /** Reports a failure inside a cancel handle. */
    private fun reportCancelFailure(failure: Throwable) {
        report(failure, "Cancelling a call")
    }
}

/**
 * Hands a completion its answer once: later answers are dropped, and a completion that throws is
 * reported through [reportFailure] rather than let through.
 */
private class OneTimeDelivery<T>(
    private val completion: (T?, BridgeError?) -> Unit,
    private val reportFailure: (Throwable) -> Unit,
) {
    /** Whether the answer has been handed over. */
    private val delivered = AtomicBoolean(false)

    /** Hands over [value] or [error], unless an answer already went. */
    fun deliver(value: T?, error: BridgeError?) {
        if (!delivered.compareAndSet(expectedValue = false, newValue = true)) return
        try {
            completion(value, error)
        } catch (failure: Throwable) {
            reportFailure(failure)
        }
    }
}
