// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.inmemory

import kotlinx.coroutines.flow.StateFlow

/**
 * Failures a server can be made to have on purpose, so the app can show what each looks like. Only
 * the in-memory repository has them.
 */
interface ServerFaults {
    /** True while every request to the server fails. */
    val isServerOutage: StateFlow<Boolean>

    /** True while Storage refuses every upload, download and delete. */
    val isStorageFailing: StateFlow<Boolean>

    /** Starts or ends the outage. */
    fun setServerOutage(active: Boolean)

    /** Starts or stops Storage's refusals. */
    fun setStorageFailing(active: Boolean)
}
