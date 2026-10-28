// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.cache.CachePatcher
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mayPassWhenSentAgain
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
import io.github.nesfeder.supabase.offline.internal.postgrest.RemoteTable
import io.github.nesfeder.supabase.offline.internal.postgrest.SupabaseRemoteTable
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

/**
 * Sends the mutation queue to Supabase: replay passes, and the immediate send of a mutation that is
 * at the head of its identity's queue when it is made. Exactly one sender at a time per mutation. A
 * guarded write needs SELECT on its key, version and written columns, and its returned row must
 * pass the table's SELECT policy, as any supabase-kt write with `select()` does.
 *
 * It builds the parts that do the work and hands each what it needs; they share one [SendState]:
 * the send lock, the writes on the wire and the passes running. [ReplayPass] runs a pass,
 * [HeadSender] sends a write at once, [ReplayRequests] builds a row write's request,
 * [ReplayAttempts] sends one request under the owner check, and [GuardedReplay] settles a guarded
 * write's conflict rounds. Where a failed request leaves its write is decided by
 * [classifyReplayFailure].
 */
internal class MutationQueueReplayer(
    /** The queue it sends, and records each send's outcome in. */
    private val mutationQueue: MutationQueue,
    /** Where a schema-cache miss's tick is read from. */
    private val freshness: CacheFreshness,
    private val supabase: SupabaseClient,
    private val engineLogger: EngineLogger = EngineLogger(null, EngineLogLevel.INFO),
    /** Whether the client may reach the network; nothing is sent while it is off. */
    private val networkAccess: NetworkAccess = NetworkAccess(),
    private val remoteTableFor: (schema: String, table: String) -> RemoteTable = { schema, table ->
        SupabaseRemoteTable(supabase.postgrest.from(schema, table), networkAccess = networkAccess)
    },
    /**
     * Waits, bounded, for a settled session; `false` means it did not settle and nothing is sent.
     */
    private val awaitSessionSettled: suspend () -> Boolean = { supabase.awaitSettledSession() },
    /** The identity a request sent now would go out as, or `null` while that cannot be told. */
    private val currentIdentity: suspend () -> QueueIdentity? = {
        supabase.currentReplayIdentity()
    },
    /** Whether the session is settled now, without waiting; see [awaitSessionSettled]. */
    private val isSessionSettledNow: () -> Boolean = { supabase.currentReplayIdentity() != null },
    /**
     * Whose a request's bearer token is, for a client that tells users apart by their access token;
     * `null` when the Auth plugin's session, or nothing, decides it. See [ReplayOwner].
     */
    private val identityOfToken: ((token: String?) -> QueueIdentity?)? = null,
    private val primaryKeys: PrimaryKeys = DefaultPrimaryKeys,
    /** Told of each pass's report as it ends, with the table it covered (`null` for all). */
    private val passEnded: suspend (table: String?, report: SyncReport) -> Unit = { _, _ -> },
    /**
     * Patches the rows a guarded replay finds on the server into the cache; `null` patches none.
     */
    private val cachePatcher: CachePatcher? = null,
    /** Settles a guarded write whose row moved on the server, or is gone. */
    private val conflictPolicy: ConflictPolicy = DefaultConflictPolicy,
    /** How long [conflictPolicy] may take before its answer reads as Hold; tests shorten it. */
    private val conflictPolicyTimeout: Duration = CONFLICT_POLICY_TIMEOUT,
    /** Where [conflictPolicy] runs; `null` runs it on the pass's own dispatcher. */
    private val conflictPolicyDispatcher: CoroutineDispatcher? = null,
    /** Sends the queued Storage uploads; `null` refuses each one it meets to the rejected list. */
    private val uploadSends: QueuedUploadSends? = null,
) {

    /** What the replay passes and the sends at the head share; see [SendState]. */
    private val sendState =
        SendState(
            mutationQueue = mutationQueue,
            freshness = freshness,
            currentIdentity = currentIdentity,
            engineLogger = engineLogger,
        )

    /** [SendState.sendLock]. */
    private val sendLock = sendState.sendLock

    /** [SendState.inFlight]. */
    private val inFlight = sendState.inFlight

    /** [SendState.clearGeneration]. */
    private val clearGeneration = sendState.clearGeneration

    /** [SendState.activePasses]. */
    private val activePasses = sendState.activePasses

    private val attempts =
        ReplayAttempts(
            mutationQueue = mutationQueue,
            sendState = sendState,
            identityOfToken = identityOfToken,
            engineLogger = engineLogger,
        )

    private val replayPass =
        ReplayPass(
            mutationQueue = mutationQueue,
            sendState = sendState,
            networkAccess = networkAccess,
            remoteTableFor = remoteTableFor,
            awaitSessionSettled = awaitSessionSettled,
            currentIdentity = currentIdentity,
            isSessionSettledNow = isSessionSettledNow,
            primaryKeys = primaryKeys,
            uploadSends = uploadSends,
            requests =
                ReplayRequests(
                    versionColumns = mutationQueue.versionColumns,
                    primaryKeys = primaryKeys,
                    engineLogger = engineLogger,
                ),
            attempts = attempts,
            guardedReplay =
                GuardedReplay(
                    freshness = freshness,
                    attempts = attempts,
                    cachePatcher = cachePatcher,
                    conflictPolicy = conflictPolicy,
                    conflictPolicyTimeout = conflictPolicyTimeout,
                    conflictPolicyDispatcher = conflictPolicyDispatcher,
                    engineLogger = engineLogger,
                ),
            engineLogger = engineLogger,
        )

    private val headSender =
        HeadSender(
            mutationQueue = mutationQueue,
            sendState = sendState,
            networkAccess = networkAccess,
            currentIdentity = currentIdentity,
            identityOfToken = identityOfToken,
            engineLogger = engineLogger,
        )

    /** Whether a pass is running, as it changes. */
    val passRunning: Flow<Boolean> =
        activePasses.map { passes -> passes.isNotEmpty() }.distinctUntilChanged()

    init {
        // Before anything is sent: a mutation goes out with its owner's token, or not at all
        supabase.installReplayOwnerCheck()
    }

    /**
     * Runs one replay pass for the current identity: its mutations in insertion order, stopping at
     * the first failure that may pass, one attempt at the head. Returns promptly when offline.
     *
     * @param table The table to replay, or `null` for every table.
     * @return What the pass synced, rejected and left pending, and why it stopped early if it did.
     */
    suspend fun sync(
        table: String? = null,
        schema: String = supabase.postgrest.config.defaultSchema,
    ): SyncReport = replayPass.run(table, schema).also { report -> passEnded(table, report) }

    /**
     * Runs passes as [sync] does until one ends for a reason other than another sender holding the
     * head of the queue, waiting between them for that sender to let go: a pass until it ends, a
     * send at the head until its write is off the wire. So it returns once the queue is empty or a
     * pass stopped on something else, and its counts are its own passes'. Behind a pass sending N
     * writes it runs one more pass, not one per write.
     *
     * @param table The table to replay, or `null` for every table.
     * @return What its passes synced and rejected, and what the last one left pending.
     */
    suspend fun syncWaitingOutOtherSenders(table: String? = null): SyncReport {
        var total: SyncReport? = null
        while (true) {
            val report = sync(table = table)
            total = total?.followedBy(report) ?: report
            if (report.stop != SyncStop.HeadInFlight) return total
            sendState.awaitOtherSenders()
        }
    }

    /**
     * Wipes local data with [clear] once no pass can send anything more: passes in flight are
     * canceled and [clear] runs under the send lock. An outcome arriving afterward is discarded.
     */
    suspend fun clearLocalData(clear: suspend () -> Unit) {
        clearGeneration.update { generation -> generation + 1 }
        activePasses.value.forEach { pass ->
            pass.cancel(CancellationException("Local data cleared"))
        }
        sendLock.withLock {
            clear()
            sendState.forgetSchemaCacheMisses()
        }
    }

    /**
     * Runs [block] under the send lock, as [clearLocalData] runs its clear, but lets every pass and
     * queued write go on: for a drop of the cache that keeps the mutation queue.
     */
    suspend fun <T> holdingSends(block: suspend () -> T): T = sendLock.withLock { block() }

    /**
     * What happened to a mutation offered for sending at the head of its queue, whose request
     * answers with a [T]: PostgREST's result for a row write, Storage's response for an upload.
     */
    sealed interface HeadSendOutcome<out T> {

        /**
         * It stays queued unsent: something is ahead of it, it is being sent, or the session moved
         * on.
         */
        data object NotAtHead : HeadSendOutcome<Nothing>

        /** The server accepted it and it left the queue. */
        data class Sent<T>(val result: T) : HeadSendOutcome<T>

        /**
         * It may not have run on the server, or may pass when sent again, and stays queued: no
         * connection or no answer, an answer a replay keeps queued too (a `408`, `429`, `502`,
         * `503`, `504`, `520`, `522` or `524`, or a `401` for a token PostgREST refused as expired
         * or unreadable), a table or column PostgREST's schema cache does not know yet, or a
         * session token that could not be refreshed.
         */
        data class StaysQueued(val cause: Exception) : HeadSendOutcome<Nothing>

        /** The server refused it, or answered with another failure; it left the queue. */
        data class Refused(val cause: Exception) : HeadSendOutcome<Nothing>

        /** Local data was cleared while it was on the wire; nothing about it was recorded. */
        data class Cleared<T>(val result: T?, val cause: Exception?) : HeadSendOutcome<T>
    }

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
    ): HeadSendOutcome<T> =
        headSender.sendAtHead(
            mutationId = mutationId,
            identity = identity,
            send = send,
            staysQueuedOnAnswer = staysQueuedOnAnswer,
            onLeftQueue = onLeftQueue,
            onAccepted = onAccepted,
        )

    /**
     * Discards the pending mutation [id] of [identity] unsent. A mutation being sent cannot be.
     *
     * @param identity The asking identity, or `null` to skip the ownership check.
     * @return `true` when it was pending, not in flight, and [identity]'s.
     */
    suspend fun discardPendingMutation(id: Long, identity: QueueIdentity?): Boolean =
        sendLock.withLock {
            if (id in inFlight.value) false
            else mutationQueue.discardPendingMutation(id, identity?.storedValue)
        }

    /**
     * [discardPendingMutation], waiting first for a sender that has [id] on the wire to let go:
     * once it returns, the mutation is not pending, unless it is another identity's.
     *
     * @return `true` when it was discarded; `false` when it was not pending by then, or not
     *   [identity]'s.
     */
    suspend fun discardPendingMutationOnceUnsent(id: Long, identity: QueueIdentity?): Boolean {
        while (true) {
            val discarded: Boolean? = sendLock.withLock {
                if (id in inFlight.value) null
                else mutationQueue.discardPendingMutation(id, identity?.storedValue)
            }
            if (discarded != null) return discarded
            inFlight.first { current -> id !in current }
        }
    }
}
