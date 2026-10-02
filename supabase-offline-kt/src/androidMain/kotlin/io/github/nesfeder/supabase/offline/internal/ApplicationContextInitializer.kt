// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import android.content.Context
import androidx.startup.Initializer

/** The application context androidx.startup handed [ApplicationContextInitializer]. */
@Volatile private var recordedApplicationContext: Context? = null

/**
 * Records the application context when the app starts, so `install(Offline)` needs none, as
 * supabase-kt's Storage plugin gets its own. androidx.startup runs it from the library's manifest.
 */
internal class ApplicationContextInitializer : Initializer<Context> {

    override fun create(context: Context): Context =
        context.applicationContext.also { recordedApplicationContext = it }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

/**
 * The application context [ApplicationContextInitializer] recorded.
 *
 * @throws IllegalStateException when androidx.startup did not run it, as when an app removes its
 *   `InitializationProvider` from the merged manifest.
 */
internal fun recordedApplicationContext(): Context =
    checkNotNull(recordedApplicationContext) {
        "The Offline plugin has no application context: androidx.startup did not run its " +
            "initializer. Keep androidx.startup's InitializationProvider in the app's manifest."
    }
