package io.github.nesfeder.supabase

/**
 * Log levels for the Supabase Offline SDK.
 *
 * Ordered by severity: [DEBUG] < [INFO] < [WARNING] < [ERROR].
 * The SDK's [SupabaseOfflineConfig.minLogLevel] controls which levels
 * are emitted to the consumer's [OfflineLogger].
 */
enum class OfflineLogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

/**
 * Callback interface for receiving log messages from the SDK.
 *
 * Implement this to bridge SDK logs into your preferred logging
 * framework (e.g. Timber on Android, os.Logger on iOS).
 *
 * This is a **fun interface**, so Android consumers can use SAM conversion:
 * ```kotlin
 * OfflineLogger { level, message -> Timber.log(level.priority, message) }
 * ```
 *
 * On iOS/Swift this exports as a protocol with a single method.
 */
fun interface OfflineLogger {
    fun log(level: OfflineLogLevel, message: String)
}

/**
 * Internal logger wrapper that gates on [minLevel] and uses lazy message
 * construction so filtered-out levels incur zero string-building overhead.
 */
internal class SdkLogger(
    private val logger: OfflineLogger?,
    private val minLevel: OfflineLogLevel
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
