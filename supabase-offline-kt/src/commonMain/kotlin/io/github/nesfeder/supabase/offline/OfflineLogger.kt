// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * Log levels for the Supabase Offline SDK.
 *
 * Ordered by severity: [DEBUG] < [INFO] < [WARNING] < [ERROR]. The SDK's
 * [SupabaseOfflineConfig.minLogLevel] controls which levels are emitted to the consumer's
 * [OfflineLogger].
 */
public enum class OfflineLogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
}

/**
 * Callback interface for receiving log messages from the SDK.
 *
 * Implement this to bridge SDK logs into your preferred logging framework (e.g. Timber on Android,
 * os.Logger on iOS).
 *
 * This is a **fun interface**, so Android consumers can use SAM conversion:
 * ```kotlin
 * OfflineLogger { level, message -> Timber.log(level.priority, message) }
 * ```
 *
 * On iOS/Swift this exports as a protocol with a single method.
 */
public fun interface OfflineLogger {
    /**
     * Receives one SDK log message. Only levels at or above [SupabaseOfflineConfig.minLogLevel]
     * reach this method.
     *
     * @param level the message's severity.
     * @param message the message text; it carries row data only at [OfflineLogLevel.DEBUG].
     */
    public fun log(level: OfflineLogLevel, message: String)
}
