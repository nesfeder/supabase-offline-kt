// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.ConflictResolution
import io.github.nesfeder.supabase.offline.ConflictResolver
import io.github.nesfeder.supabase.offline.MutationConflict

/**
 * A [ConflictResolver] that records every conflict it is asked about and answers with [answer]: the
 * library's defaults unless a test scripts another, or a throw or a hang.
 */
internal class RecordingConflictResolver(
    var answer: suspend (MutationConflict) -> ConflictResolution = { conflict ->
        if (conflict.server == null) ConflictResolution.KeepServer else ConflictResolution.KeepLocal
    }
) : ConflictResolver {

    /** Every conflict asked about, in order. */
    val conflicts: MutableList<MutationConflict> = mutableListOf()

    override suspend fun resolve(conflict: MutationConflict): ConflictResolution {
        conflicts += conflict
        return answer(conflict)
    }
}
