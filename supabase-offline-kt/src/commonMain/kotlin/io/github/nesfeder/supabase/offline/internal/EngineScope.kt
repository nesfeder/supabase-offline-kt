package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.SdkLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * The handler every engine scope carries, so a background failure that reaches no caller is logged
 * by type (never its message, which can carry row data) instead of crashing the process.
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
