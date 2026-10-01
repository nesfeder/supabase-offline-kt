// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.OfflineLogger
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * [OfflineLogger] that records every message it receives, in order, for assertions on what the SDK
 * logged and at which level. Safe to log to from several threads at once.
 */
internal class RecordingOfflineLogger : OfflineLogger {

    /** One recorded log call. */
    data class Entry(val level: OfflineLogLevel, val message: String)

    private val recorded = AtomicReference<List<Entry>>(emptyList())

    /** Every message received so far, in call order. */
    val entries: List<Entry>
        get() = recorded.load()

    override fun log(level: OfflineLogLevel, message: String) {
        val entry = Entry(level, message)
        while (true) {
            val current = recorded.load()
            if (recorded.compareAndSet(current, current + entry)) return
        }
    }

    /** Wraps this recorder in the SDK's level-gating [EngineLogger]. */
    fun asEngineLogger(minLevel: OfflineLogLevel = OfflineLogLevel.DEBUG): EngineLogger =
        EngineLogger(this, minLevel)

    /** The recorded entries at exactly [level]. */
    fun entriesAt(level: OfflineLogLevel): List<Entry> = entries.filter { it.level == level }
}
