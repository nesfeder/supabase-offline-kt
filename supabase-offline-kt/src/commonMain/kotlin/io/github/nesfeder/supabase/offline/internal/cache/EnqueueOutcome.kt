// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue

/** What [MutationQueue.enqueueOwnedBy] did with a write. */
internal sealed interface EnqueueOutcome {

    /**
     * The write is in the queue.
     *
     * @property id Its mutation id.
     * @property row The mutation row as stored, with its [id].
     */
    data class Queued(val id: Long, val row: MutationRow) : EnqueueOutcome

    /** Local data was cleared after the write started; it was not queued. */
    data object LocalDataCleared : EnqueueOutcome
}
