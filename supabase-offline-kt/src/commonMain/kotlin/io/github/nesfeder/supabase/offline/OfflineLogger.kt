// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * How severe a log message of the library is.
 *
 * Ordered by severity: [DEBUG] < [INFO] < [WARNING] < [ERROR]. [Offline.Config.minLogLevel] sets
 * the least severe level an [OfflineLogger] receives.
 */
public enum class OfflineLogLevel {
    /** Detail for development, which may carry rows, request bodies and filter values. */
    DEBUG,

    /** What the library did, such as a write queued or a read served from the cache. */
    INFO,

    /** Something unexpected the library recovered from. */
    WARNING,

    /** A failure the library could not recover from on its own. */
    ERROR,
}

/**
 * Where the library's log messages go: set one as [Offline.Config.logger] to pass them on to the
 * app's own logging.
 *
 * A `fun interface`, so a lambda will do:
 * ```kotlin
 * install(Offline) {
 *     logger = OfflineLogger { level, message -> println("[$level] $message") }
 * }
 * ```
 */
public fun interface OfflineLogger {
    /**
     * Receives one log message. Only levels at or above [Offline.Config.minLogLevel] reach it.
     *
     * @param level the message's severity.
     * @param message the message text; it carries row data only at [OfflineLogLevel.DEBUG].
     */
    public fun log(level: OfflineLogLevel, message: String)
}
