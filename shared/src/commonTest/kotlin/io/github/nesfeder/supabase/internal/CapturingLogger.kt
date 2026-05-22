package io.github.nesfeder.supabase.internal

import io.github.nesfeder.supabase.OfflineLogLevel
import io.github.nesfeder.supabase.OfflineLogger
import io.github.nesfeder.supabase.SdkLogger

/**
 * Test helper that records all log messages for assertion.
 */
internal class CapturingLogger : OfflineLogger {
    data class Entry(val level: OfflineLogLevel, val message: String)

    val entries = mutableListOf<Entry>()

    override fun log(level: OfflineLogLevel, message: String) {
        entries.add(Entry(level, message))
    }

    fun asSdkLogger(minLevel: OfflineLogLevel = OfflineLogLevel.DEBUG) =
        SdkLogger(this, minLevel)

    fun entriesAt(level: OfflineLogLevel) = entries.filter { it.level == level }
}
