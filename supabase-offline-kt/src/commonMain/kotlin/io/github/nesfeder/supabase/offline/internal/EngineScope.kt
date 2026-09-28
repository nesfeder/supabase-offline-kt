package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.SdkLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * The exception handler every engine-owned coroutine scope carries.
 *
 * Failures inside the engine's background work are meant to reach whoever is waiting on it — a
 * shared refresh hands its failure to its collectors — but a scope with no handler turns anything
 * that slips past into an uncaught exception: a crash on Android, termination on iOS. This logs the
 * failure's type (never its message, which can carry row data) and keeps the process alive.
 *
 * @param sdkLogger where to log, or `null` to only contain the failure.
 * @return the handler.
 */
internal fun engineExceptionHandler(sdkLogger: SdkLogger?): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, failure ->
        sdkLogger?.error { "Offline engine background work failed unexpectedly (${failure::class.simpleName})" }
    }

/**
 * A supervised engine scope on [Dispatchers.Default] carrying [engineExceptionHandler].
 *
 * @param sdkLogger where the handler logs.
 * @param parent the job to cancel it with, or `null` for a root scope.
 * @return the scope.
 */
internal fun newEngineScope(sdkLogger: SdkLogger?, parent: Job? = null): CoroutineScope =
    CoroutineScope(SupervisorJob(parent) + Dispatchers.Default + engineExceptionHandler(sdkLogger))
