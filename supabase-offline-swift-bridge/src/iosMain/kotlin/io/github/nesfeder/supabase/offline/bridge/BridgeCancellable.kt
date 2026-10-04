// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import kotlinx.coroutines.Job

/**
 * The handle every asynchronous bridge call returns: cancelling it cancels the Kotlin work behind
 * the call, whose completion then fires with [BridgeErrorKind.CANCELLED] unless it already fired.
 */
public class BridgeCancellable
internal constructor(private val job: Job?, private val reportFailure: (Throwable) -> Unit) {

    /** Cancels the call; safe to call more than once, from any thread, and after it finished. */
    public fun cancel() {
        try {
            job?.cancel()
        } catch (failure: Throwable) {
            reportFailure(failure)
        }
    }
}
