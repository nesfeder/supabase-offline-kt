// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalNativeApi::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.logging.SupabaseLoggingProcessor
import io.github.nesfeder.supabase.offline.bridge.BridgeLogLevel
import io.github.nesfeder.supabase.offline.bridge.BridgeLogSink
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

/** The tag the bridge's own messages carry. */
internal const val BRIDGE_LOG_TAG: String = "Supabase-Offline-Bridge"

/**
 * Delivers supabase-kt's log messages, the library's included, to the app's [sink] at [minimum]
 * and above. A failure is named by its type; only at [LogLevel.DEBUG] does its message follow,
 * since supabase-kt's messages quote URLs and filter values.
 */
internal class SinkLoggingProcessor(
    private val sink: BridgeLogSink?,
    private val minimum: BridgeLogLevel,
) : SupabaseLoggingProcessor {

    /** Whether a message at [level] reaches the sink. */
    override fun isEnabled(level: LogLevel): Boolean =
        sink != null &&
            minimum != BridgeLogLevel.NONE &&
            level != LogLevel.NONE &&
            level.toBridgeLevel().ordinal >= minimum.ordinal

    /** Hands one message to the sink; a sink that throws is never let through. */
    override fun processLog(level: LogLevel, tag: String, throwable: Throwable?, message: String) {
        if (!isEnabled(level)) return
        val bridgeLevel = level.toBridgeLevel()
        val text =
            when {
                throwable == null -> message
                bridgeLevel == BridgeLogLevel.DEBUG -> "$message (${throwable.describedInFull()})"
                else -> "$message (${throwable::class.simpleName})"
            }
        logSafely(sink, bridgeLevel, tag, text)
    }
}

/** [this] as the bridge's level; supabase-kt's `NONE` never reaches a sink. */
internal fun LogLevel.toBridgeLevel(): BridgeLogLevel =
    when (this) {
        LogLevel.DEBUG -> BridgeLogLevel.DEBUG
        LogLevel.INFO -> BridgeLogLevel.INFO
        LogLevel.WARNING -> BridgeLogLevel.WARNING
        LogLevel.ERROR -> BridgeLogLevel.ERROR
        LogLevel.NONE -> BridgeLogLevel.NONE
    }

/** [this] as supabase-kt's level, for the client's default log level. */
internal fun BridgeLogLevel.toSupabaseLevel(): LogLevel =
    when (this) {
        BridgeLogLevel.DEBUG -> LogLevel.DEBUG
        BridgeLogLevel.INFO -> LogLevel.INFO
        BridgeLogLevel.WARNING -> LogLevel.WARNING
        BridgeLogLevel.ERROR -> LogLevel.ERROR
        BridgeLogLevel.NONE -> LogLevel.NONE
    }

/** Calls [sink]; anything it throws is dropped, since there is nowhere left to report it. */
internal fun logSafely(sink: BridgeLogSink?, level: BridgeLogLevel, tag: String, message: String) {
    if (sink == null) return
    try {
        sink.log(level, tag, message)
    } catch (_: Throwable) {
        // The sink is the last place a failure can be reported; one that throws reports nothing
    }
}

/** The failure's type and message, for a DEBUG line. */
private fun Throwable.describedInFull(): String = "${this::class.simpleName}: $message"

/**
 * The process-wide last resort: an exception that escapes every coroutine handler, such as one
 * thrown in a scope supabase-kt owns, is reported to the latest sink instead of ending the app.
 */
internal object UnhandledFailureReporter {

    /** The sink of the latest client opened with one. */
    private val sink = AtomicReference<BridgeLogSink?>(null)

    /** Whether the hook is installed; once per process. */
    private val installed = AtomicBoolean(false)

    /** Reports escaped failures to [latestSink] from now on, installing the hook the first time. */
    fun reportTo(latestSink: BridgeLogSink?) {
        if (latestSink != null) sink.store(latestSink)
        if (installed.compareAndSet(expectedValue = false, newValue = true)) {
            setUnhandledExceptionHook { failure -> report(failure) }
        }
    }

    /** Logs [failure] at ERROR, by type, to the latest sink. */
    fun report(failure: Throwable) {
        logSafely(
            sink.load(),
            BridgeLogLevel.ERROR,
            BRIDGE_LOG_TAG,
            "An unhandled failure was caught (${failure::class.simpleName})",
        )
    }
}
