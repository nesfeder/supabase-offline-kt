// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.logging

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.logging.SupabaseLogger
import io.github.jan.supabase.logging.createLogger

/** The tag the plugin's messages carry in supabase-kt's logging, as `Supabase-Postgrest` does. */
internal const val LOGGING_TAG: String = "Supabase-Offline"

/** How severe one of the engine's log messages is: [DEBUG] < [INFO] < [WARNING] < [ERROR]. */
internal enum class EngineLogLevel {
    /** Detail for development, which may carry rows, request bodies and filter values. */
    DEBUG,

    /** What the engine did, such as a write queued or a read served from the cache. */
    INFO,

    /** Something unexpected the engine recovered from. */
    WARNING,

    /** A failure the engine could not recover from on its own. */
    ERROR,
}

/** Where the engine's log messages go: supabase-kt's logger, or a test's recorder. */
internal fun interface EngineLogSink {

    /**
     * Receives one log message that passed the [EngineLogger]'s level.
     *
     * @param level the message's severity.
     * @param message the message text; it carries row data only at [EngineLogLevel.DEBUG].
     */
    fun log(level: EngineLogLevel, message: String)
}

/** supabase-kt's level for [this]. */
internal fun EngineLogLevel.toLogLevel(): LogLevel =
    when (this) {
        EngineLogLevel.DEBUG -> LogLevel.DEBUG
        EngineLogLevel.INFO -> LogLevel.INFO
        EngineLogLevel.WARNING -> LogLevel.WARNING
        EngineLogLevel.ERROR -> LogLevel.ERROR
    }

/**
 * The engine's logger over [this] client's logging, at [logLevel] or, when `null`, the client's
 * default level: the messages reach the logging processor the client was built with, tagged
 * [LOGGING_TAG], as supabase-kt's own plugins log.
 */
internal fun SupabaseClient.engineLogger(logLevel: LogLevel?): EngineLogger =
    createLogger(LOGGING_TAG, logLevel, loggingFactory = null).toEngineLogger()

/** An [EngineLogger] that hands each message passing [SupabaseLogger.level] to [this]. */
internal fun SupabaseLogger.toEngineLogger(): EngineLogger {
    val minLevel =
        when (level) {
            LogLevel.DEBUG -> EngineLogLevel.DEBUG
            LogLevel.INFO -> EngineLogLevel.INFO
            LogLevel.WARNING -> EngineLogLevel.WARNING
            LogLevel.ERROR -> EngineLogLevel.ERROR
            LogLevel.NONE -> return EngineLogger(sink = null, minLevel = EngineLogLevel.ERROR)
        }
    return EngineLogger(
        sink = { messageLevel, message -> log(messageLevel.toLogLevel(), message = message) },
        minLevel = minLevel,
    )
}
