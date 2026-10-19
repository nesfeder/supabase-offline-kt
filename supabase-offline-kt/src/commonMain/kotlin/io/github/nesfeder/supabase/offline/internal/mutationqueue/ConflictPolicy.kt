// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.ConflictResolver
import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject

/**
 * How long a [ConflictPolicy] may take to decide before its answer reads as
 * [ConflictDecision.Hold].
 */
internal val CONFLICT_POLICY_TIMEOUT: Duration = 30.seconds

/** What a replay found when a guarded write's precondition no longer held on the server. */
internal sealed interface ConflictCase {

    /** The queued write. */
    val mutation: MutationRow

    /** The write's columns and the version column, as the device last saw them. */
    val base: JsonObject

    /** The columns the write sets now; `null` for a DELETE. */
    val local: JsonObject?

    /**
     * The row is still on the server with another version.
     *
     * @property server The server's row as the replay read it.
     * @property serverVersion Its version, as the queue stores one.
     * @property changedOnServer The write's columns whose server value differs from [base].
     * @property conflictingColumns Of those, the ones [local] sets to another value than the
     *   server's.
     */
    data class Moved(
        override val mutation: MutationRow,
        override val base: JsonObject,
        override val local: JsonObject?,
        val server: JsonObject,
        val serverVersion: String,
        val changedOnServer: Set<String>,
        val conflictingColumns: Set<String>,
    ) : ConflictCase

    /** An UPDATE's row is gone from the server, or hidden from this user by a policy. */
    data class Gone(
        override val mutation: MutationRow,
        override val base: JsonObject,
        override val local: JsonObject?,
    ) : ConflictCase
}

/** How a [ConflictPolicy] settles a [ConflictCase]. */
internal sealed interface ConflictDecision {

    /** Send the write again, preconditioned on the server's version now. */
    data object KeepLocal : ConflictDecision

    /** Drop the write; the server's row stands. */
    data object KeepServer : ConflictDecision

    /** Send [columns] as the UPDATE's body instead; only for a row still on the server. */
    data class Apply(val columns: JsonObject) : ConflictDecision

    /** Park the write on the conflicted list. */
    data object Hold : ConflictDecision
}

/**
 * Decides each conflict a replay finds. Called outside every transaction and lock except the pass's
 * own send; a decision that throws or takes longer than [CONFLICT_POLICY_TIMEOUT] reads as Hold.
 */
internal fun interface ConflictPolicy {

    /** How to settle [case]. */
    suspend fun decide(case: ConflictCase): ConflictDecision
}

/**
 * The policy without an app's own: the local change wins a moved row (an UPDATE sends only the
 * columns it set, a DELETE still deletes), and an UPDATE of a row that is gone is dropped.
 */
internal val DefaultConflictPolicy: ConflictPolicy = ConflictPolicy { case ->
    when (case) {
        is ConflictCase.Moved -> ConflictDecision.KeepLocal
        is ConflictCase.Gone -> ConflictDecision.KeepServer
    }
}

/**
 * The policy that asks the app's [resolver], each case and answer mapped to the public types; the
 * default policy when the app set none. Anything the resolver throws but a cancellation, an `Error`
 * included, reads as Hold and is logged with the table and its type only.
 *
 * @param primaryKeys Where each table's key columns come from, for the row key the resolver sees.
 */
internal fun conflictPolicyOf(
    resolver: ConflictResolver?,
    primaryKeys: PrimaryKeys,
    engineLogger: EngineLogger,
): ConflictPolicy =
    if (resolver == null) DefaultConflictPolicy
    else
        ConflictPolicy { case ->
            val conflict = case.toMutationConflict(EngineJson, primaryKeys)
            try {
                conflictDecisionOf(resolver.resolve(conflict))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                engineLogger.warning {
                    "The conflict resolver for ${conflict.table} failed " +
                        "(${failure::class.simpleName}); holding the write"
                }
                ConflictDecision.Hold
            }
        }
