package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.result.PostgrestResult

/**
 * Lifecycle of the background network refresh that backs a `CacheFirstThenNetwork` read.
 *
 * The refresh is self-healing: it retries for the life of the subscription (a fast ladder, then
 * capped exponential backoff) and short-circuits its current sleep when an internal recovery
 * accelerator (auth session restored, connectivity regained) fires. Consumers never have to drive
 * recovery; this state only *reports* it.
 */
public sealed interface RefreshState {

    /**
     * The refresh has succeeded (or is not applicable to this read strategy). No further network
     * attempt is pending for this subscription.
     */
    public data object Idle : RefreshState

    /** A refresh attempt is in progress or the fast retry ladder has not yet been exhausted. */
    public data object InFlight : RefreshState

    /**
     * A full fast retry ladder exhausted without success; the subscription keeps serving whatever
     * data it has (cache, mutation overlay, or nothing) while capped backoff continues in the
     * background.
     *
     * @property cause The failure from the attempt that exhausted the fast ladder.
     */
    public data class Stalled(val cause: Throwable) : RefreshState
}

/**
 * A single emission of the in-band read+refresh state for a `selectAsFlow` subscription.
 *
 * Consumers that want a Loading state until data is known observe [result] directly (a `null`
 * result is the typed "no data yet" state); consumers that want today's instant-empty-state
 * rendering map the no-data case to `emptyList()` in their repository. [refresh] is available for a
 * future UI affordance ("refresh failed, showing stale/empty cache") — consumers may ignore it.
 *
 * @property result The cached/network payload, or `null` when no data is available yet for this
 *   signature (no cached payload and no client-side mutation overlay). A `null` result is the
 *   typed "no data yet" state that replaces the old fabricated empty-array cache seed; a present
 *   result whose body is `[]` means the server genuinely returned no rows.
 * @property refresh The current [RefreshState] of this subscription's background refresh.
 */
public data class OfflineReadResult(
    val result: PostgrestResult?,
    val refresh: RefreshState,
)
