package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.OfflineLogger
import io.github.nesfeder.supabase.offline.SdkLogger

/**
 * [OfflineLogger] that records every message it receives, in order, for assertions on what the
 * SDK logged and at which level.
 */
internal class RecordingOfflineLogger : OfflineLogger {

    /** One recorded log call. */
    data class Entry(val level: OfflineLogLevel, val message: String)

    /** Every message received, in call order. */
    val entries: MutableList<Entry> = mutableListOf()

    override fun log(level: OfflineLogLevel, message: String) {
        entries.add(Entry(level, message))
    }

    /** Wraps this recorder in the SDK's level-gating [SdkLogger]. */
    fun asSdkLogger(minLevel: OfflineLogLevel = OfflineLogLevel.DEBUG): SdkLogger =
        SdkLogger(this, minLevel)

    /** The recorded entries at exactly [level]. */
    fun entriesAt(level: OfflineLogLevel): List<Entry> = entries.filter { it.level == level }
}
