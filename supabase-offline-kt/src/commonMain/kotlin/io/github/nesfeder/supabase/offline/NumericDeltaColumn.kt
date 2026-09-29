// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import kotlinx.serialization.Serializable

/**
 * Declares that a queued UPDATE's write to [column] is a **relative** change, not the absolute
 * value the body happens to carry.
 *
 * A queued UPDATE freezes whatever the client believed the row held at enqueue time. For a column
 * that only the client ever moves that is faithful; for an *accumulating* column — a running total
 * several writers add to — it is a clobber: anything that moved the column server-side between
 * enqueue and replay (a second device, another queued write draining ahead of this one) is
 * overwritten by a number computed against a snapshot that is no longer true, and nothing later
 * heals it.
 *
 * Declaring a column here makes the queue store the user's *intent* instead:
 * ```
 * intent  = body[column] - snapshotValue
 * replay  = serverCurrent + intent
 * ```
 *
 * so an intervening change survives and the caller's correction still lands. The body keeps the
 * absolute value, which is what the online write sends and what the offline overlay shows locally
 * (offline, the snapshot *is* the freshest thing the device knows). The rebase happens once, at
 * replay, against a re-read of the targeted row.
 *
 * Only meaningful for `updateJson` / `updateWith` under
 * [MutationStrategy.QueueIfOffline][MutationStrategy.QueueIfOffline]: an online write applies
 * immediately, so there is no window to rebase across.
 *
 * @property column The column whose write is relative. Ignored when the UPDATE body does not carry
 *   it, or carries a non-numeric value.
 * @property snapshotValue What the caller believed [column] held when it computed the body — the
 *   base the intent is measured against.
 * @property minValue Floor the rebased value is clamped to, or `null` to allow any result. A rebase
 *   adds the caller's intent to a number the caller never saw, so a *downward* intent can land
 *   below the column's domain when the server moved down as well — a total the user corrected to
 *   zero while another device removed some of its own contributions rebases negative. The absolute
 *   body could never do that, so a column with a natural floor (a running total, a count) should
 *   state it here rather than let the rebase invent a value the column cannot hold.
 */
@Serializable
public data class NumericDeltaColumn(
    val column: String,
    val snapshotValue: Double,
    val minValue: Double? = null,
)
