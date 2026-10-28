// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/**
 * What every sender of the queue shares, the replay passes and the sends at the head: the send
 * lock, the mutations on the wire and whose they are, the passes in flight, the clear generation,
 * and the two checks both make, whether the session moved on and how many schema-cache misses a
 * write has met. One per replayer. See [MutationQueue] for where the send lock sits in the lock
 * order.
 */
internal class SendState(
    /** The queue whose sends this state guards. */
    private val mutationQueue: MutationQueue,
    /** Where a schema-cache miss's tick is read from. */
    private val freshness: CacheFreshness,
    /** The identity a request sent now would go out as, or `null` while that cannot be told. */
    private val currentIdentity: suspend () -> QueueIdentity?,
    private val engineLogger: EngineLogger,
) {

    /**
     * Guards choosing a mutation to send and recording its outcome. Never held across a request.
     * The outermost of the queue's locks: [MutationQueue] gives their order.
     */
    val sendLock = Mutex()

    /**
     * The mutations being sent right now, by id, each with the pass sending it, or `null` for a
     * send at the head. Changed under [sendLock]; observed by a caller waiting for a sender to let
     * go, which waits for a pass to end before it waits for the write, since a pass claims its next
     * write as soon as it records the last.
     */
    val inFlight = MutableStateFlow<Map<Long, Job?>>(emptyMap())

    /**
     * Bumped by [MutationQueueReplayer.clearLocalData]; an outcome recorded after it moved is
     * discarded.
     */
    val clearGeneration = MutableStateFlow(0L)

    /**
     * Passes in flight, so [MutationQueueReplayer.clearLocalData] can cancel them and a caller can
     * wait one out: a pass is here from before it claims anything until its caller's wait on it
     * ends. A pass whose caller was canceled leaves while its last request finishes on the wire, so
     * a waiter waits for the writes a pass held as well ([awaitOtherSenders]).
     */
    val activePasses = MutableStateFlow<Set<Job>>(emptySet())

    /**
     * When this process last counted a schema-cache miss of each mutation, as a
     * [CacheFreshness.tickNow] tick; see [schemaCacheMissReachesBound]. Guarded by [sendLock].
     */
    private val schemaCacheMissesCountedAt = mutableMapOf<Long, Long>()

    /** Whether this replayer has warned of a session whose token does not name its user. */
    private val warnedOfTokenNotNamingUser = MutableStateFlow(false)

    /**
     * Waits for the senders holding a mutation now to let go: each pass until it leaves
     * [activePasses], then every held mutation until it leaves [inFlight]. The second wait is the
     * whole wait for a send at the head, and what a pass needs whose caller was canceled: it leaves
     * [activePasses] at once while its request finishes on the wire, and a caller that ran again
     * before the outcome was recorded would meet the same write in flight, and run again. Returns
     * at once when nothing is held.
     */
    suspend fun awaitOtherSenders() {
        val holding = inFlight.value
        if (holding.isEmpty()) return
        val passes = holding.values.filterNotNullTo(HashSet())
        if (passes.isNotEmpty()) {
            activePasses.first { active -> passes.none { pass -> pass in active } }
        }
        inFlight.first { current -> holding.keys.none { id -> id in current } }
    }

    /**
     * Keeps a mutation whose request the owner check stopped queued for its owner's next session.
     * It never left the device, so a send recordOutcome this attempt made is cleared. When the
     * session is still [owner]'s, its token names another user or none, which holds the queue: said
     * once.
     */
    suspend fun stoppedForAnotherSession(
        mutationId: Long,
        newlyUnconfirmed: Boolean,
        owner: QueueIdentity,
    ) {
        if (newlyUnconfirmed) mutationQueue.clearUnconfirmedSend(mutationId)
        if (currentIdentity() == owner) {
            if (warnedOfTokenNotNamingUser.compareAndSet(expect = false, update = true)) {
                engineLogger.warning {
                    "The session's access token does not name the session's user; its queued " +
                        "mutations are held until the two agree"
                }
            }
            return
        }
        engineLogger.info {
            "The session changed before a queued mutation was sent; it stays queued for its owner"
        }
    }

    /**
     * Counts a schema-cache miss of mutation [mutationId], unless this process counted one less
     * than [SCHEMA_CACHE_MISS_SPACING] ago, and says whether its misses reached
     * [SCHEMA_CACHE_MISS_BOUND]. Caller holds [sendLock].
     */
    suspend fun schemaCacheMissReachesBound(mutationId: Long): Boolean {
        val now = freshness.tickNow()
        val last = schemaCacheMissesCountedAt[mutationId]
        if (last != null && now - last < SCHEMA_CACHE_MISS_SPACING.inWholeNanoseconds) return false
        schemaCacheMissesCountedAt[mutationId] = now
        val misses = mutationQueue.recordSchemaCacheMiss(mutationId)
        engineLogger.warning {
            "PostgREST's schema cache does not know a table or column yet; " +
                "miss $misses of $SCHEMA_CACHE_MISS_BOUND before the write is rejected"
        }
        return misses >= SCHEMA_CACHE_MISS_BOUND
    }

    /** Forgets the schema-cache misses counted for mutation [mutationId], which left the queue. */
    fun forgetSchemaCacheMisses(mutationId: Long) {
        schemaCacheMissesCountedAt -= mutationId
    }

    /** Forgets every schema-cache miss counted: local data was cleared. Caller holds [sendLock]. */
    fun forgetSchemaCacheMisses() {
        schemaCacheMissesCountedAt.clear()
    }
}

/**
 * Schema-cache misses after which a write is rejected: at [SCHEMA_CACHE_MISS_SPACING] apart,
 * minutes of misses, where a migration's cache reload takes seconds.
 */
private const val SCHEMA_CACHE_MISS_BOUND = 10

/** How long after a counted schema-cache miss the next one counts, in one process. */
private val SCHEMA_CACHE_MISS_SPACING = 30.seconds
