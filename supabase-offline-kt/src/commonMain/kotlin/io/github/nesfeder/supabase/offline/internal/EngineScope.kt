// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlinx.coroutines.CoroutineDispatcher
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
 * A supervised engine scope carrying [engineExceptionHandler]: every scope the engine runs work in
 * is made here. With a [parent] it is the parent's child, on the parent's dispatcher, so canceling
 * the parent stops it while canceling it leaves the parent running; without one, it runs on
 * [dispatcher].
 *
 * @param engineLogger where the handler logs.
 * @param parent the scope it belongs to, or `null` for a scope of its own.
 * @param dispatcher where a scope of its own runs; ignored with a [parent].
 */
internal fun newEngineScope(
    engineLogger: EngineLogger?,
    parent: CoroutineScope? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): CoroutineScope =
    if (parent == null) {
        CoroutineScope(SupervisorJob() + dispatcher + engineExceptionHandler(engineLogger))
    } else {
        CoroutineScope(
            parent.coroutineContext +
                SupervisorJob(parent.coroutineContext[Job]) +
                engineExceptionHandler(engineLogger)
        )
    }
