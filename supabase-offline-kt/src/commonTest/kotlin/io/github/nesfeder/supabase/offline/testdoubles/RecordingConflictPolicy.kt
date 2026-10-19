// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.internal.mutationqueue.ConflictCase
import io.github.nesfeder.supabase.offline.internal.mutationqueue.ConflictDecision
import io.github.nesfeder.supabase.offline.internal.mutationqueue.ConflictPolicy
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultConflictPolicy

/**
 * A [ConflictPolicy] that records every case it is asked about and answers with [answer]: the
 * default policy's decision unless a test scripts another, or a throw or a hang.
 */
internal class RecordingConflictPolicy(
    var answer: suspend (ConflictCase) -> ConflictDecision = { case ->
        DefaultConflictPolicy.decide(case)
    }
) : ConflictPolicy {

    /** Every case asked about, in order. */
    val cases: MutableList<ConflictCase> = mutableListOf()

    override suspend fun decide(case: ConflictCase): ConflictDecision {
        cases += case
        return answer(case)
    }
}
