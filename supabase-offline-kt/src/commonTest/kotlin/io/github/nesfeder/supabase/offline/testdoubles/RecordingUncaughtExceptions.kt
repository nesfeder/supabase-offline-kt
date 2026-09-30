// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A scope's exception handler that records each exception reaching the scope uncaught, where an app
 * would crash, instead of leaving it for the test runner to report later.
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
