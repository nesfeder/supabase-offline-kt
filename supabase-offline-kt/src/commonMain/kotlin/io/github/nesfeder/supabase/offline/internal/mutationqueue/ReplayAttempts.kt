// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.postgrest.SendOutcome
import io.github.nesfeder.supabase.offline.internal.postgrest.sendCapturingEngineFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * Sends one request of a replay under the owner check, and says where it leaves the write when it
 * fails: the one step the plain replay of a pass and the guarded replay's rounds share.
 */
internal class ReplayAttempts(
    /** The queue whose send records an attempt clears when its write provably did not run. */
    private val mutationQueue: MutationQueue,
    /** What it shares with the other senders: the session check. */
    private val sendState: SendState,
    /** Whose a request's bearer token is; `null` when the session decides it. */
    private val identityOfToken: ((token: String?) -> QueueIdentity?)?,
    private val engineLogger: EngineLogger,
) {

    /** One request of a replay: what it returned, or how the replay ends. */
    sealed interface Attempt<out T> {
        /** The server answered with [value]. */
        class Done<T>(val value: T) : Attempt<T>

        /** The request failed; the replay ends with [outcome]. */
        class Ended(val outcome: ReplayOutcome) : Attempt<Nothing>
    }

    /**
     * Sends [request] for [mutation] as [identity], the pass [pass] running it: stopped before it
     * leaves the device when the session is no longer [identity]'s or [pass] was canceled.
     *
     * @param newlyUnconfirmed whether this attempt recorded the mutation's send as unconfirmed.
     * @throws CancellationException when [pass] was canceled before the request left.
     */
    suspend fun <T> attempt(
        mutation: MutationRow,
        identity: QueueIdentity,
        pass: Job,
        newlyUnconfirmed: Boolean = false,
        request: suspend () -> T,
    ): Attempt<T> {
        val owner = ReplayOwner(identity, sender = pass, identityOfToken = identityOfToken)
        return when (val sent = sendCapturingEngineFailure { withContext(owner) { request() } }) {
            is SendOutcome.Succeeded -> Attempt.Done(sent.value)
            is SendOutcome.Failed ->
                if (owner.stoppedWithItsSender) {
                    // It never left the device: the pass ends here, canceled
                    if (newlyUnconfirmed) mutationQueue.clearUnconfirmedSend(mutation.id)
                    throw CancellationException("The replay pass was stopped")
                } else if (owner.stoppedForAnotherSession) {
                    sendState.stoppedForAnotherSession(mutation.id, newlyUnconfirmed, identity)
                    Attempt.Ended(ReplayOutcome.SessionChanged)
                } else {
                    if (newlyUnconfirmed && sent.provesWriteNotApplied())
                        mutationQueue.clearUnconfirmedSend(mutation.id)
                    Attempt.Ended(
                        classifyReplayFailure(
                            mutation,
                            sent.thrown,
                            identity,
                            mutationQueue,
                            engineLogger,
                        )
                    )
                }
        }
    }
}
