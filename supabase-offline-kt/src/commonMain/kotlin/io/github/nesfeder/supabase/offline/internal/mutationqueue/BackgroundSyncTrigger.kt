package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.SdkLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the replay passes the engine starts on its own — after a write is queued behind pending ones,
 * after a write sent at the head left more pending, and when the session settles into an identity
 * with mutations queued — coalesced and backed off.
 *
 * - **Coalesced:** requests are conflated. At most one pass runs and at most one more is waiting,
 *   however many requests arrive meanwhile, so a burst of writes queued behind the head starts one
 *   pass, not one per write.
 * - **One attempt:** a pass makes one attempt at the head of the queue; nothing here retries it. A
 *   pass runs only when something asked for one.
 * - **Backed off:** after a pass that stopped on a failure that may pass (a transient failure, or a
 *   write held behind an earlier one), the next pass waits — [initialBackoff], doubling up to
 *   [maxBackoff] — and a pass that ends any other way resets the wait. So a write the server keeps
 *   failing is not hammered by every write queued behind it.
 *
 * A switch that turns automatic replay off, when one is added, must stop the session-settle trigger
 * (and any connectivity trigger added with it), and must not stop the two write-path triggers: with
 * write-ahead an online write returns `Queued` whenever anything of its identity is pending ahead of
 * it, and only those passes send it. So the switch goes at each trigger, not in this class.
 *
 * @property scope Hosts the worker; cancelling it stops the trigger.
 * @property sdkLogger Where a pass that throws is logged.
 * @property initialBackoff The wait after the first failed pass.
 * @property maxBackoff The longest wait.
 * @property pass Runs one replay pass.
 */
internal class BackgroundSyncTrigger(
    private val scope: CoroutineScope,
    private val sdkLogger: SdkLogger,
    private val initialBackoff: Duration = 1.seconds,
    private val maxBackoff: Duration = 60.seconds,
    private val pass: suspend () -> SyncReport,
) {

    private val requests = Channel<Unit>(Channel.CONFLATED)

    private val worker = scope.launch(start = CoroutineStart.LAZY) { runPasses() }

    /** Asks for a pass. Returns at once; the pass runs in [scope]. */
    fun request() {
        requests.trySend(Unit)
        worker.start()
    }

    private suspend fun runPasses() {
        var backoff = Duration.ZERO
        while (true) {
            requests.receive()
            val report = try {
                pass()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                sdkLogger.warning { "Background mutation replay failed: ${failure::class.simpleName}" }
                null
            }
            backoff = if (report == null || report.stop.mayPass()) {
                if (backoff == Duration.ZERO) initialBackoff else minOf(backoff * 2, maxBackoff)
            } else {
                Duration.ZERO
            }
            if (backoff > Duration.ZERO) delay(backoff)
        }
    }

    /** Whether a pass that stopped this way stopped on a failure worth waiting out. */
    private fun SyncStop?.mayPass(): Boolean =
        this is SyncStop.TransientFailure || this is SyncStop.HeldBehindEarlierMutations
}
