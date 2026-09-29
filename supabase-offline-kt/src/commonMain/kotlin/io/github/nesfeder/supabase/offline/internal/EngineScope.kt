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
 */
internal fun newEngineScope(sdkLogger: SdkLogger?): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default + engineExceptionHandler(sdkLogger))

/**
 * A supervised child of [parent] on [parent]'s dispatcher, carrying [engineExceptionHandler]:
 * cancelling it stops everything launched in it and leaves [parent] running.
 */
internal fun childEngineScope(parent: CoroutineScope, sdkLogger: SdkLogger?): CoroutineScope =
    CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + engineExceptionHandler(sdkLogger))
