// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.withContext

/**
 * An upstream that emits 1, then — deaf to cancellation, signaling [betweenValues] — waits for
 * [closeReturned] and emits 2. A `flow {}` would refuse that second emission once cancelled.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class FakeUpstreamPastItsCancellation(
    private val betweenValues: CompletableDeferred<Unit>,
    private val closeReturned: CompletableDeferred<Unit>,
) : Flow<Int> {
    override suspend fun collect(collector: FlowCollector<Int>) {
        collector.emit(1)
        withContext(NonCancellable) {
            betweenValues.complete(Unit)
            closeReturned.await()
            collector.emit(2)
        }
    }
}
