// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.retryAfterOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs the replay passes the engine starts on its own, coalesced and backed off: after a write is
 * queued behind pending ones, after a write sent, refused or discarded leaves more pending, after a
 * rejected write is retried, when the session settles into an identity with mutations queued, and
 * when the network comes back.
 *
 * - **Coalesced:** requests are conflated. At most one pass runs and at most one more is waiting,
 *   however many requests arrive meanwhile, so a burst of writes queued behind the head starts one
 *   pass, not one per write.
 * - **One attempt:** a pass makes one attempt at the head of the queue; nothing here retries it. A
 *   pass runs only when something asked for one.
 * - **Backed off:** after a pass that stopped on a failure that may pass (a transient failure, or a
 *   write held behind an earlier one), the next pass waits — [initialBackoff], doubling up to
 *   [maxBackoff] — and a pass that ends any other way resets the wait. So a write the server keeps
 *   failing is not hammered by every write queued behind it. A `Retry-After` the server sent with
 *   the failure makes the wait at least that long, up to [maxRetryAfter].
 *
 * The app's `autoSync` switch stops the session-settle and connectivity triggers, never the
 * write-path ones: with write-ahead an online write returns `Queued` whenever anything of its
 * identity is pending ahead of it, and only those passes send it. So the switch is at each trigger,
 * not in this class.
 *
 * @property scope Hosts the worker; cancelling it stops the trigger.
 * @property engineLogger Where a pass that throws is logged.
 * @property initialBackoff The wait after the first failed pass.
 * @property maxBackoff The longest wait.
 * @property maxRetryAfter The longest wait a server's `Retry-After` can ask for.
 * @property clock The time a `Retry-After` date is read against.
 * @property pass Runs one replay pass.
 */
internal class AutomaticReplay(
    private val scope: CoroutineScope,
    private val engineLogger: EngineLogger,
    private val initialBackoff: Duration = 1.seconds,
    private val maxBackoff: Duration = 60.seconds,
    private val maxRetryAfter: Duration = 1.hours,
    private val clock: Clock = Clock.System,
    private val pass: suspend () -> SyncReport,
) {

    private val requests = Channel<Unit>(Channel.CONFLATED)

    private val worker = scope.launch(start = CoroutineStart.LAZY) { runPasses() }

    /** Asks for a pass. Returns at once; the pass runs in [scope]. */
    fun requestPass() {
        requests.trySend(Unit)
        worker.start()
    }

    private suspend fun runPasses() {
        var backoff = Duration.ZERO
        while (true) {
            requests.receive()
            val report =
                try {
                    pass()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    engineLogger.warning {
                        "Background mutation replay failed: ${failure::class.simpleName}"
                    }
                    null
                }
            backoff =
                if (report == null || report.stop.isWorthWaitingOut()) {
                    if (backoff == Duration.ZERO) initialBackoff else minOf(backoff * 2, maxBackoff)
                } else {
                    Duration.ZERO
                }
            val serverAskedFor =
                (report?.stop as? SyncStop.TransientFailure)?.cause?.retryAfterOrNull {
                    clock.now().toEpochMilliseconds()
                }
            val wait =
                if (serverAskedFor == null) backoff
                else maxOf(backoff, minOf(serverAskedFor, maxRetryAfter))
            if (wait > Duration.ZERO) delay(wait)
        }
    }

    /** Whether a pass that stopped this way stopped on a failure worth waiting out. */
    private fun SyncStop?.isWorthWaitingOut(): Boolean =
        this is SyncStop.TransientFailure || this is SyncStop.HeldBehindEarlierMutations
}
