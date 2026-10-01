// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * The handler every engine scope carries, so a background failure that reaches no caller never
 * crashes the process. It is logged by type, never by its message, which can carry row data.
 *
 * @param engineLogger where to log, or `null` to only contain the failure.
 * @return the handler.
 */
internal fun engineExceptionHandler(engineLogger: EngineLogger?): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, failure ->
        engineLogger?.error {
            "Offline engine background work failed unexpectedly (${failure::class.simpleName})"
        }
    }

/**
 * A supervised engine scope on [Dispatchers.Default] carrying [engineExceptionHandler].
 *
 * @param engineLogger where the handler logs.
 */
internal fun newEngineScope(engineLogger: EngineLogger?): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default + engineExceptionHandler(engineLogger))

/**
 * A supervised child of [parent] on [parent]'s dispatcher, carrying [engineExceptionHandler]:
 * cancelling it stops everything launched in it and leaves [parent] running.
 */
internal fun childEngineScope(parent: CoroutineScope, engineLogger: EngineLogger?): CoroutineScope =
    CoroutineScope(
        parent.coroutineContext +
            SupervisorJob(parent.coroutineContext[Job]) +
            engineExceptionHandler(engineLogger)
    )
