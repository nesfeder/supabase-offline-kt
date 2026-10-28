// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.auth.exception.TokenExpiredException
import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.internal.SCHEMA_CACHE_MISS_CODES
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mayPassWhenSentAgain
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer.HeadSendOutcome
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
import io.github.nesfeder.supabase.offline.internal.postgrest.SendOutcome
import io.github.nesfeder.supabase.offline.internal.postgrest.sendCapturingEngineFailure
import io.github.nesfeder.supabase.offline.internal.postgrestCodeOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sends a queued mutation at once when it is at the head of its identity's queue as it is made, the
 * immediate send of [MutationQueueReplayer.sendAtHead]. It shares the send lock and the mutations
 * on the wire with the replay passes ([SendState]), so exactly one sender sends a mutation.
 */
internal class HeadSender(
    /** The queue it sends from, and records each send's outcome in. */
    private val mutationQueue: MutationQueue,
    /** What it shares with the replay passes. */
    private val sendState: SendState,
    /** Whether the client may reach the network; nothing is sent while it is off. */
    private val networkAccess: NetworkAccess,
    /** The identity a request sent now would go out as, or `null` while that cannot be told. */
    private val currentIdentity: suspend () -> QueueIdentity?,
    /** Whose a request's bearer token is; `null` when the session decides it. */
    private val identityOfToken: ((token: String?) -> QueueIdentity?)?,
    private val engineLogger: EngineLogger,
) {

    /** [SendState.sendLock]. */
    private val sendLock = sendState.sendLock

    /** [SendState.inFlight]. */
    private val inFlight = sendState.inFlight

    /** [SendState.clearGeneration]. */
    private val clearGeneration = sendState.clearGeneration

    /**
     * Sends queued mutation [mutationId] now if it is at the head of [identity]'s queue, nobody
     * else is sending it, and the session is still [identity]. On success [onAccepted] runs before
     * the mutation leaves the queue; a failure of [onAccepted] is logged, since the server has the
     * write. [onLeftQueue] runs once it has left the queue, in the same hold of the send lock, so
     * no other sender meets it gone before [onLeftQueue] has run. A caller canceled while the
     * request is on the wire leaves the mutation queued.
     */
    suspend fun <T> sendAtHead(
        mutationId: Long,
        identity: QueueIdentity,
        send: suspend () -> T,
        staysQueuedOnAnswer: (Exception) -> Boolean = Throwable::mayPassWhenSentAgain,
        onLeftQueue: suspend () -> Unit = {},
        onAccepted: suspend (T) -> Unit,
    ): HeadSendOutcome<T> {
        // Nothing leaves the device while network access is off: it waits, queued
        if (networkAccess.isDisabled)
            return HeadSendOutcome.StaysQueued(
                NetworkDisabledException(NetworkAccess.NETWORK_DISABLED_MESSAGE)
            )
        val generation = clearGeneration.value
        val claimed = sendLock.withLock {
            val available =
                clearGeneration.value == generation &&
                    mutationId !in inFlight.value &&
                    currentIdentity() == identity &&
                    mutationQueue.isAtHead(mutationId, identity.storedValue)
            if (available) inFlight.update { sending -> sending + (mutationId to null) }
            available
        }
        if (!claimed) return HeadSendOutcome.NotAtHead
        try {
            val newlyUnconfirmed = mutationQueue.markSendUnconfirmed(mutationId)
            val owner = ReplayOwner(identity, identityOfToken = identityOfToken)
            val result =
                when (val sent = sendCapturingEngineFailure { withContext(owner) { send() } }) {
                    is SendOutcome.Succeeded -> sent.value
                    is SendOutcome.Failed ->
                        return sendLock.withLock {
                            if (owner.stoppedForAnotherSession) {
                                sendState.stoppedForAnotherSession(
                                    mutationId,
                                    newlyUnconfirmed,
                                    identity,
                                )
                                HeadSendOutcome.NotAtHead
                            } else {
                                headFailureOutcome(
                                    mutationId,
                                    sent,
                                    newlyUnconfirmed,
                                    generation,
                                    staysQueuedOnAnswer,
                                )
                            }
                        }
                }
            if (clearGeneration.value != generation)
                return HeadSendOutcome.Cleared(result = result, cause = null)
            try {
                onAccepted(result)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                engineLogger.warning {
                    "Patching the cache after an accepted write failed: ${failure::class.simpleName}"
                }
            } finally {
                // The server has the write: it leaves the queue whatever happened here, or a pass
                // would send it again.
                // Under the send lock, as a pass records a sent write: a pass reads the queue and
                // counts what remains under it too, so it never meets the write gone with
                // [onLeftQueue] still to run
                withContext(NonCancellable) {
                    sendLock.withLock { if (completeAcceptedMutation(mutationId)) onLeftQueue() }
                }
            }
            return HeadSendOutcome.Sent(result)
        } finally {
            withContext(NonCancellable) {
                sendLock.withLock { inFlight.update { sending -> sending - mutationId } }
            }
        }
    }

    /** Where a failed head send leaves its mutation. Caller holds [sendLock]. */
    private suspend fun <T> headFailureOutcome(
        mutationId: Long,
        failure: SendOutcome.Failed,
        newlyUnconfirmed: Boolean,
        generation: Long,
        staysQueuedOnAnswer: (Exception) -> Boolean,
    ): HeadSendOutcome<T> {
        val cause = failure.thrown
        return when {
            clearGeneration.value != generation ->
                HeadSendOutcome.Cleared(result = null, cause = cause)
            // The session's token expired and could not be refreshed: nothing left the device
            cause is TokenExpiredException -> {
                if (newlyUnconfirmed) mutationQueue.clearUnconfirmedSend(mutationId)
                HeadSendOutcome.StaysQueued(cause)
            }
            cause.postgrestCodeOrNull() in SCHEMA_CACHE_MISS_CODES &&
                !sendState.schemaCacheMissReachesBound(mutationId) -> {
                if (newlyUnconfirmed) mutationQueue.clearUnconfirmedSend(mutationId)
                HeadSendOutcome.StaysQueued(cause)
            }
            // No answer, or one a replay would keep queued too
            cause.isNetworkOrConnectivityFailure() || staysQueuedOnAnswer(cause) -> {
                if (newlyUnconfirmed && failure.provesWriteNotApplied())
                    mutationQueue.clearUnconfirmedSend(mutationId)
                HeadSendOutcome.StaysQueued(cause)
            }
            else -> {
                mutationQueue.abandonSentMutation(mutationId)
                HeadSendOutcome.Refused(cause)
            }
        }
    }

    /**
     * Removes an accepted mutation, logging rather than failing a write the server applied.
     *
     * @return whether it left the queue.
     */
    private suspend fun completeAcceptedMutation(mutationId: Long): Boolean =
        try {
            mutationQueue.completeSentMutation(mutationId)
            true
        } catch (failure: Exception) {
            engineLogger.error {
                "Could not remove an accepted mutation from the queue; it may be sent again (${failure::class.simpleName})"
            }
            false
        }
}
