// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.logging

/**
 * The engine's logger: it passes a message on to [sink] only at [minLevel] or above, and builds the
 * message only then, so a filtered-out level costs no string building.
 */
internal class EngineLogger(
    private val sink: EngineLogSink?,
    private val minLevel: EngineLogLevel,
) {
    inline fun log(level: EngineLogLevel, message: () -> String) {
        if (sink != null && level.ordinal >= minLevel.ordinal) {
            sink.log(level, message())
        }
    }

    inline fun debug(message: () -> String) = log(EngineLogLevel.DEBUG, message)

    inline fun info(message: () -> String) = log(EngineLogLevel.INFO, message)

    inline fun warning(message: () -> String) = log(EngineLogLevel.WARNING, message)

    inline fun error(message: () -> String) = log(EngineLogLevel.ERROR, message)
}
