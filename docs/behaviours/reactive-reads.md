# Reactive reads

Observing a query as a `Flow` that updates as the cache changes.

> **Not written yet.** This page is a placeholder in the planned site. The headings below are its
> outline; the content will be written for 0.1.0, once the API it describes has settled.

## What a reactive read emits

## `CacheFirstThenNetwork`

## `NetworkFirstThenCache`

## `CacheOnly`

## Mapping to UI state

## When it fetches again

## Sharing and cancellation

## Behaviour matrix

Facts about the engine as it is, one row per condition; the conditions are those of the
[one-shot matrix](read-strategies.md#behaviour-matrix). Each cell names the test that pins it.
Every emission is an `OfflineReadResult`: a `result` (`null` means no data yet) and a `refresh`
state.

| Condition | `CacheFirstThenNetwork` | `NetworkFirstThenCache` | `CacheOnly` |
|---|---|---|---|
| **Online** | The cached payload with the overlay at once (`null` when nothing is cached), `InFlight`; then the server's rows, `Idle`. Collectors of one query share one refresh.<br><small>`QueryReaderStrategyTest.selectAsFlow_cacheFirstThenNetwork_emitsCacheWhileNetworkSuspended_thenNetwork`, `ResponseTrustTest.without_auth_a_row_deleted_elsewhere_leaves_the_cached_list`, `QueryReaderRecoveryTest.two_collectors_on_one_signature_share_a_single_refresh`</small> | The server's rows, `Idle`; then the cache as it changes.<br><small>`QueryReaderStrategyTest.selectAsFlow_networkFirstThenCache_emitsNetworkData`</small> | The cached payload with the overlay, or `null`; `Idle`; never a request.<br><small>`QueryReaderStrategyTest.selectAsFlow_cacheOnly_emitsCachedData`, `QueryReaderStrategyTest.selectAsFlow_cacheOnly_neverTriggersNetwork`</small> |
| **Offline, warm** | The cached payload with the overlay. The refresh retries for the life of the collection (300 ms, 900 ms, 2.4 s, 5 s, 15 s, then every 60 s), `Stalled` after four failures, about 3.6 s: each attempt fails at once, without supabase-kt's retries. The flow stays live.<br><small>`CacheFirstWithParallelRefreshTest.refresh_failure_does_not_terminate_flow`, `CacheFirstWithParallelRefreshTest.exhausted_fast_ladder_keeps_flow_alive_and_reports_stalled`, `ReactiveRefreshOfflineTest.offline_the_refresh_says_stalled_once_its_fast_ladder_is_spent`</small> | The cached payload with the overlay at once, `Stalled`: the cache is not confirmed. It fetches again on the schedule `CacheFirstThenNetwork` retries on, and is `Idle` once the server answers; the flow stays live.<br><small>`NetworkFirstThenCacheFallbackTest.with_a_warm_cache_and_no_network_it_shows_the_cache_at_once_and_stays_live`, `NetworkFirstThenCacheUnconfirmedTest.offline_it_shows_the_cache_as_not_confirmed_and_fetches_again_once_the_network_is_back`, `QueryReaderStrategyTest.selectAsFlow_networkFirstThenCache_networkFailure_fallsBackToCache`</small> | The cached payload with the overlay.<br><small>`QueryReaderStrategyTest.selectAsFlow_cacheOnly_emitsCachedData`</small> |
| **Offline, cold** | `null`, `InFlight`, then `Stalled`; the flow stays live and settles when the server answers.<br><small>`QueryReaderRecoveryTest.with_state_reports_no_data_then_stalled_then_network_idle_on_late_success`</small> | The flow fails with the connectivity failure.<br><small>`NetworkFirstThenCacheFallbackTest.with_a_cold_cache_and_no_network_it_fails`</small> | `null`, or the queued rows the query admits.<br><small>`OfflineStoreEmptyCacheReactivityTest.substrate_reports_no_data_when_no_cache_and_no_mutations`, `OfflineStoreEmptyCacheReactivityTest.queued_offline_insert_is_visible_via_substrate_without_writing_a_room_row`</small> |
| **Session restoring** | The cached payload. Nothing a request returns is written, and the refresh does not settle, until the session has. Settled as the stored user: the user's answer. Settled signed out: the rows cached before are dropped, never shown, and it settles on the anonymous answer.<br><small>`ResponseTrustTest.a_session_still_restoring_is_gated_then_the_read_settles_when_it_does`, `ResponseTrustTest.a_narrowed_response_while_restoring_does_not_overwrite_the_users_cache`, `CacheBelongsToOneIdentityTest.a_restore_that_settles_signed_out_never_shows_what_was_cached_before`</small> | Nothing until the session settles, as a one-shot read waits; then the user's rows, `Idle`. A session that never settles: the cache, `Stalled`.<br><small>`ReadsWhileRestoringTest.a_network_first_reactive_read_waits_for_the_session_before_it_emits`, `NetworkFirstThenCacheUnconfirmedTest.an_answer_that_does_not_speak_for_the_user_leaves_the_cache_not_confirmed`</small> | The cached payload, no request.<br><small>`ReadsWhileRestoringTest.a_read_the_cache_answers_sends_nothing_while_the_session_restores`</small> |

A server's own error (4xx or 5xx) is a refresh failure for `CacheFirstThenNetwork`, and fails a
`NetworkFirstThenCache` flow even with a warm cache.
<small>`CacheFirstWithParallelRefreshTest.refresh_failure_does_not_terminate_flow`,
`NetworkFirstThenCacheFallbackTest.with_a_warm_cache_a_servers_own_error_still_fails_it`</small>

A read of a shape the cache cannot hold (see the [one-shot matrix](read-strategies.md#behaviour-matrix))
emits the network's answer once, `Idle`, and completes; offline it fails. Under `CacheOnly` it
fails at once with `NoSuchElementException` and sends nothing.
<small>`ReadShapeTest.a_reactive_read_of_each_shape_emits_the_network_answer_once`,
`ReadShapeTest.offline_a_reactive_read_of_each_shape_fails`,
`ReadShapeTest.a_cache_only_read_of_each_shape_sends_nothing_and_says_the_cache_cannot_answer_it`</small>

A collector slow to take each value is not handed every list in between: under
`CacheFirstThenNetwork` it gets the latest, under the other two a few superseded lists at most. It
ends on the latest, and never holds back another collector of the same query.
<small>`SlowCollectorTest.each_collector_ends_on_the_latest_list_and_the_slow_one_does_not_hold_back_the_other`</small>

A read live while the session settles into another identity drops what it showed for the one
before and ends on the new identity's rows alone.
<small>`CacheBelongsToOneIdentityTest.a_reactive_read_live_across_a_change_of_user_ends_on_the_new_users_rows_alone`</small>
