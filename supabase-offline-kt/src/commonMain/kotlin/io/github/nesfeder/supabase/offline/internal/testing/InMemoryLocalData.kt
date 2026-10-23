// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalObjCRefinement::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.internal.db.OpenDatabaseRegistry
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The local data of in-memory clients, kept from one client to the next: the cached queries with
 * the engine's reserved rows (the rejected and conflicted lists among them), the mutation queue,
 * and the Storage file cache's rows and files. A client opened over it after an earlier one closed
 * starts where that one stopped, as an app relaunched over its database does. Nothing a client's
 * close does clears it.
 *
 * One client holds it at a time, as one holds a database ([OpenDatabaseRegistry]): two would each
 * replay the same queued writes.
 *
 * This is the hook behind the `supabase-offline-kt-testing` artifact's `InMemoryStore`, which is
 * the supported entry point. The opt-in gates Kotlin callers; [HiddenFromObjC] keeps it out of the
 * Objective-C export, where an opt-in cannot be enforced.
 */
@OfflineInternal
@HiddenFromObjC
public class InMemoryLocalData {

    /** The clock of the client that holds the data now, which stamps the files it writes. */
    private val holderClock = AtomicReference<Clock>(Clock.System)

    /** The client's claim on the data, or `null` while no client holds it. */
    private val holder = AtomicReference<Claim?>(null)

    /** The cached queries, and the engine's reserved rows beside them. */
    internal val queryCacheDao: InMemoryQueryCacheDao = InMemoryQueryCacheDao()

    /** The Storage file cache's rows. */
    internal val cachedFileDao: InMemoryCachedFileDao = InMemoryCachedFileDao()

    /** The mutation queue. */
    internal val mutationDao: InMemoryMutationDao = InMemoryMutationDao()

    /** The Storage file cache's files, stamped by the clock of the client that writes them. */
    internal val fileSystem: InMemoryFileSystem =
        InMemoryFileSystem(
            object : Clock {
                override fun now(): Instant = holderClock.load().now()
            }
        )

    /**
     * Claims the data for one client, whose clock is [clock].
     *
     * @return the claim; release it when the client closes.
     * @throws IllegalStateException when another client holds it, open or still closing.
     */
    internal fun claim(clock: Clock): Claim {
        val claim = Claim()
        while (!holder.compareAndSet(null, claim)) {
            val current = holder.load() ?: continue
            error(current.conflictMessage())
        }
        holderClock.store(clock)
        return claim
    }

    /** One client's claim on the data. */
    internal inner class Claim {

        /** Set by the first [release], so a second one frees nothing. */
        private val released = AtomicBoolean(false)

        /** Counts the closing client's calls in flight, once [markStillClosing] has run. */
        private val stillClosing = AtomicReference<(() -> Int)?>(null)

        /**
         * Records that the client's close() returned before the release, with [callsInFlight] still
         * using the data, so a client opened meanwhile is told so.
         */
        fun markStillClosing(callsInFlight: () -> Int) {
            stillClosing.store(callsInFlight)
        }

        /** Why another client cannot have the data while this claim holds it. */
        fun conflictMessage(): String {
            val callsInFlight =
                stillClosing.load()
                    ?: return "An offline client is already open on this in-memory store. " +
                        "Reuse that client, or close() it before creating another: two clients " +
                        "on one store would each replay the same queued writes."
            val stillRunning = callsInFlight()
            if (stillRunning == 0) {
                return "The offline client on this in-memory store is still closing: its last " +
                    "call has finished and it is releasing the store now. Call close() again on " +
                    "it to wait for the release, then create the new client."
            }
            return "The offline client on this in-memory store is still closing: $stillRunning " +
                "call(s) it had in flight have not finished. It releases the store as soon as " +
                "they do; call close() again on it to wait for that, then create the new client."
        }

        /** Frees the data for another client. Safe to call more than once. */
        fun release() {
            if (released.compareAndSet(expectedValue = false, newValue = true)) {
                holder.compareAndSet(this, null)
            }
        }
    }
}
