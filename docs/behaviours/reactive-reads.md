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
| **Offline, warm** | The cached payload with the overlay. The refresh retries for the life of the collection (300 ms, 900 ms, 2.4 s, 5 s, 15 s, then every 60 s), `Stalled` after four failures; the flow stays live.<br><small>`CacheFirstWithParallelRefreshTest.refresh_failure_does_not_terminate_flow`, `CacheFirstWithParallelRefreshTest.exhausted_fast_ladder_keeps_flow_alive_and_reports_stalled`</small> | The cached payload with the overlay at once, `Idle`: one attempt, no retry wait. The flow stays live.<br><small>`NetworkFirstThenCacheFallbackTest.with_a_warm_cache_and_no_network_it_shows_the_cache_at_once_and_stays_live`, `QueryReaderStrategyTest.selectAsFlow_networkFirstThenCache_networkFailure_fallsBackToCache`</small> | The cached payload with the overlay.<br><small>`QueryReaderStrategyTest.selectAsFlow_cacheOnly_emitsCachedData`</small> |
| **Offline, cold** | `null`, `InFlight`, then `Stalled`; the flow stays live and settles when the server answers.<br><small>`QueryReaderRecoveryTest.with_state_reports_no_data_then_stalled_then_network_idle_on_late_success`</small> | The flow fails with the connectivity failure.<br><small>`NetworkFirstThenCacheFallbackTest.with_a_cold_cache_and_no_network_it_fails`</small> | `null`, or the queued rows the query admits.<br><small>`OfflineStoreEmptyCacheReactivityTest.substrate_reports_no_data_when_no_cache_and_no_mutations`, `OfflineStoreEmptyCacheReactivityTest.queued_offline_insert_is_visible_via_substrate_without_writing_a_room_row`</small> |
| **Session restoring** | The cached payload. Nothing a request returns is written, and the refresh does not settle, until the session has; then it settles on the user's answer.<br><small>`ResponseTrustTest.a_session_still_restoring_is_gated_then_the_read_settles_when_it_does`, `ResponseTrustTest.a_narrowed_response_while_restoring_does_not_overwrite_the_users_cache`</small> | Nothing until the session settles, as a one-shot read waits; then the user's rows, `Idle`.<br><small>`ReadsWhileRestoringTest.a_network_first_reactive_read_waits_for_the_session_before_it_emits`</small> | The cached payload, no request.<br><small>`ReadsWhileRestoringTest.a_read_the_cache_answers_sends_nothing_while_the_session_restores`</small> |

A server's own error (4xx or 5xx) is a refresh failure for `CacheFirstThenNetwork`, and fails a
`NetworkFirstThenCache` flow even with a warm cache.
<small>`CacheFirstWithParallelRefreshTest.refresh_failure_does_not_terminate_flow`,
`NetworkFirstThenCacheFallbackTest.with_a_warm_cache_a_servers_own_error_still_fails_it`</small>

A read of a shape the cache cannot hold (see the [one-shot matrix](read-strategies.md#behaviour-matrix))
emits the network's answer once, `Idle`, and completes; offline it fails.
<small>`ReadShapeTest.a_reactive_read_of_each_shape_emits_the_network_answer_once`,
`ReadShapeTest.offline_a_reactive_read_of_each_shape_fails`</small>
