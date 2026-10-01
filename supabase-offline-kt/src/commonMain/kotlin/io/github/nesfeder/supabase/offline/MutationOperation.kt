// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * What a write does to its table, in PostgREST's terms.
 *
 * The set is closed: these four are every write the mutation queue holds, so a `when` over them
 * stays exhaustive.
 */
public enum class MutationOperation {
    /** Adds rows (`POST`). */
    Insert,

    /** Adds rows, or merges them into the rows they conflict with (`POST` with a resolution). */
    Upsert,

    /** Changes the rows a filter selects (`PATCH`). */
    Update,

    /** Removes the rows a filter selects (`DELETE`). */
    Delete,
}
