@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.logging.SupabaseLoggingProcessor
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * supabase-kt's log lines, recorded from any thread: install with `defaultLoggingFactory = { logs }`
 * and `defaultLogLevel = LogLevel.DEBUG` on a test client.
 */
internal class RecordingSupabaseLogs : SupabaseLoggingProcessor {

    private val recorded = AtomicReference<List<Pair<LogLevel, String>>>(emptyList())

    /** Every line so far, with its level. */
    val entries: List<Pair<LogLevel, String>> get() = recorded.load()

    override fun isEnabled(level: LogLevel): Boolean = true

    override fun processLog(level: LogLevel, tag: String, throwable: Throwable?, message: String) {
        while (true) {
            val current = recorded.load()
            if (recorded.compareAndSet(current, current + (level to "$tag: $message"))) return
        }
    }

    /** The lines logged at [level]. */
    fun at(level: LogLevel): List<String> = entries.filter { (entryLevel, _) -> entryLevel == level }.map { (_, line) -> line }
}
