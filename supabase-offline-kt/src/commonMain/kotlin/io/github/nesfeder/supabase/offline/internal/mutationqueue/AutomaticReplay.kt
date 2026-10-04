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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the replay passes the engine starts on its own, coalesced and backed off: after a write is
 * queued behind pending ones, after a write sent, refused or discarded leaves more pending, after a
 * rejected write is retried, when the session settles into an identity with mutations queued, when
 * the network comes back, and once when a client without Auth opens with mutations queued.
 *
 * - **Coalesced:** requests are conflated. At most one pass runs and at most one more is waiting,
 *   however many requests arrive meanwhile, so a burst of writes queued behind the head starts one
 *   pass, not one per write.
 * - **One attempt:** a pass makes one attempt at the head of the queue; nothing here retries it. A
 *   pass runs only when something asked for one, and not while the network is known to be down: the
 *   reconnection asks again.
 * - **Backed off:** after a pass that stopped on a failure that may pass (a transient failure, or a
 *   write held behind an earlier one), the next pass waits — [initialBackoff], doubling up to
 *   [maxBackoff] — and a pass that ends any other way resets the wait. So a write the server keeps
 *   failing is not hammered by every write queued behind it. A `Retry-After` the server sent with
 *   the failure makes the wait at least that long, up to [maxRetryAfter].
 * - **Cut short by an edge:** a reconnection or a settled session ([requestPassOnEdge]) ends the
 *   wait at once and resets it, but never before a server's `Retry-After` has passed.
 *
 * The app's `autoSync` switch stops the session-settle, connectivity and open triggers, never the
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
 * @property isNetworkKnownDown Whether the network is known to be down now: a pass asked for then
 *   is skipped, and grows no wait.
 * @property pass Runs one replay pass.
 */
internal class AutomaticReplay(
    private val scope: CoroutineScope,
    private val engineLogger: EngineLogger,
    private val initialBackoff: Duration = 1.seconds,
    private val maxBackoff: Duration = 60.seconds,
    private val maxRetryAfter: Duration = 1.hours,
    private val clock: Clock = Clock.System,
    private val isNetworkKnownDown: () -> Boolean = { false },
    private val pass: suspend () -> SyncReport,
) {

    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** A reconnection or a settled session since the last pass began: it cuts a wait short. */
    private val edges = Channel<Unit>(Channel.CONFLATED)

    private val worker = scope.launch(start = CoroutineStart.LAZY) { runPasses() }

    /** Asks for a pass. Returns at once; the pass runs in [scope]. */
    fun requestPass() {
        requests.trySend(Unit)
        worker.start()
    }

    /**
     * Asks for a pass as the network comes back or the session settles. It ends at once the wait a
     * failed pass left, never one a server's `Retry-After` asked for. Returns at once.
     */
    fun requestPassOnEdge() {
        edges.trySend(Unit)
        requestPass()
    }

    private suspend fun runPasses() {
        var backoff = Duration.ZERO
        while (true) {
            requests.receive()
            // The pass about to run answers any edge so far, and the wait starts over after it
            if (edges.tryReceive().isSuccess) backoff = Duration.ZERO
            if (isNetworkKnownDown()) continue
            val report = passOrNull()
            backoff =
                if (report == null || report.stop.isWorthWaitingOut()) {
                    if (backoff == Duration.ZERO) initialBackoff else minOf(backoff * 2, maxBackoff)
                } else {
                    Duration.ZERO
                }
            val serverAskedFor = report?.retryAfterOrNull()?.let { minOf(it, maxRetryAfter) }
            if (serverAskedFor != null) delay(serverAskedFor)
            val rest = backoff - (serverAskedFor ?: Duration.ZERO)
            if (rest > Duration.ZERO) {
                val cut = withTimeoutOrNull(rest) { edges.receive() } != null
                if (cut) backoff = Duration.ZERO
            }
        }
    }

    /** One pass's report, or `null` when it threw: logged, and waited out as a failure. */
    private suspend fun passOrNull(): SyncReport? =
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

    /** The wait the server asked for with the failure that stopped this pass, if any. */
    private fun SyncReport.retryAfterOrNull(): Duration? =
        (stop as? SyncStop.TransientFailure)?.cause?.retryAfterOrNull {
            clock.now().toEpochMilliseconds()
        }

    /** Whether a pass that stopped this way stopped on a failure worth waiting out. */
    private fun SyncStop?.isWorthWaitingOut(): Boolean =
        this is SyncStop.TransientFailure || this is SyncStop.HeldBehindEarlierMutations
}
