// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * An app scope's exception handler in a test: it records every exception that reached the scope
 * uncaught, where an app would have crashed, instead of letting the test runner report it later.
 */
internal class RecordingUncaughtExceptions :
    AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {

    private val failures = MutableStateFlow<List<Throwable>>(emptyList())

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        failures.update { recorded -> recorded + exception }
    }

    /** Every exception recorded so far, oldest first. */
    fun recorded(): List<Throwable> = failures.value
}
