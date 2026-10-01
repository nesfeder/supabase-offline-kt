// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.logging

import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.OfflineLogger

/**
 * Internal logger wrapper that gates on [minLevel] and uses lazy message construction so
 * filtered-out levels incur zero string-building overhead.
 */
internal class EngineLogger(
    private val logger: OfflineLogger?,
    private val minLevel: OfflineLogLevel,
) {
    inline fun log(level: OfflineLogLevel, message: () -> String) {
        if (logger != null && level.ordinal >= minLevel.ordinal) {
            logger.log(level, message())
        }
    }

    inline fun debug(message: () -> String) = log(OfflineLogLevel.DEBUG, message)

    inline fun info(message: () -> String) = log(OfflineLogLevel.INFO, message)

    inline fun warning(message: () -> String) = log(OfflineLogLevel.WARNING, message)

    inline fun error(message: () -> String) = log(OfflineLogLevel.ERROR, message)
}
