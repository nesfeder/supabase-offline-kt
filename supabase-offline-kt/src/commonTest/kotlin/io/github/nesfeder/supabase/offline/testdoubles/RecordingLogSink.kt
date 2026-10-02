// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.logging.SupabaseLoggingProcessor
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogSink
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.logging.LOGGING_TAG
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Records every message the engine logs, in order, for assertions on what was logged and at which
 * level. Safe to log to from several threads at once.
 *
 * It takes messages two ways: as the [EngineLogSink] of an engine built directly, and as the
 * logging processor of a supabase-kt client ([supabaseLoggingFactory]), where it keeps only the
 * plugin's own messages, as an app's processor receives them.
 */
internal class RecordingLogSink : EngineLogSink, SupabaseLoggingProcessor {

    /** One recorded log call. */
    data class Entry(val level: EngineLogLevel, val message: String)

    private val recorded = AtomicReference<List<Entry>>(emptyList())

    /** Every message received so far, in call order. */
    val entries: List<Entry>
        get() = recorded.load()

    override fun log(level: EngineLogLevel, message: String) {
        val entry = Entry(level, message)
        while (true) {
            val current = recorded.load()
            if (recorded.compareAndSet(current, current + entry)) return
        }
    }

    override fun isEnabled(level: LogLevel): Boolean = level != LogLevel.NONE

    override fun processLog(level: LogLevel, tag: String, throwable: Throwable?, message: String) {
        if (tag != LOGGING_TAG) return
        val engineLevel =
            when (level) {
                LogLevel.DEBUG -> EngineLogLevel.DEBUG
                LogLevel.INFO -> EngineLogLevel.INFO
                LogLevel.WARNING -> EngineLogLevel.WARNING
                LogLevel.ERROR -> EngineLogLevel.ERROR
                LogLevel.NONE -> return
            }
        log(engineLevel, message)
    }

    /** Wraps this recorder in the engine's level-gating [EngineLogger]. */
    fun asEngineLogger(minLevel: EngineLogLevel = EngineLogLevel.DEBUG): EngineLogger =
        EngineLogger(this, minLevel)

    /** A supabase-kt logging factory that hands every client logger this recorder. */
    val supabaseLoggingFactory: (LogLevel) -> SupabaseLoggingProcessor = { this }

    /** The recorded entries at exactly [level]. */
    fun entriesAt(level: EngineLogLevel): List<Entry> = entries.filter { it.level == level }
}
