# One-shot reads

How a one-shot `select` chooses between the network and the cache, strategy by strategy.

> **Not written yet.** This page is a placeholder in the planned site. The headings below are its
> outline; the content will be written for 0.1.0, once the API it describes has settled.

## Choosing a strategy

## `NetworkOnly`

## `NetworkFirst`

## `CacheFirst`

## `CacheOnly`

## When `cacheDuration` is measured

## Behaviour matrix

Facts about the engine as it is, one row per condition. Each cell names the test that pins it.

- **Online:** the server answers.
- **Offline:** the request fails to connect or times out. A server's own error (4xx or 5xx) is
  not offline: every strategy that sent the request throws it.
- **Warm / cold:** the query's payload is cached / is not.
- **Session restoring:** the Auth plugin is still loading a stored session, so a request goes out
  on the anon key. Under the default trust rule nothing such a request returns is written to the
  cache.
- **Overlay:** writes still in the queue, and those replayed but not yet seen in a response, are
  applied on top of what a read returns.

| Condition | `NetworkOnly` | `NetworkFirst(cacheDuration)` | `CacheFirst(cacheDuration)` | `CacheOnly(cacheDuration)` |
|---|---|---|---|---|
| **Online** | The server's rows with the overlay; written to the cache.<br><small>`QueryReaderStrategyTest.networkOnly_successfulNetwork_returnsNetworkData`, `ResponseTrustTest.a_one_shot_read_without_auth_caches_an_empty_result`</small> | As `NetworkOnly`.<br><small>`QueryReaderStrategyTest.networkFirst_successfulNetwork_returnsNetworkDataAndCaches`, `QueryReaderStrategyTest.networkFirst_success_overlaysPendingWrites_andRetiresSyncedOnes`</small> | A cached payload within `cacheDuration`: that payload with the overlay, no request. Otherwise as `NetworkOnly`. Age is the time of the last response: a local write does not refresh it.<br><small>`QueryReaderStrategyTest.cacheFirst_foundInCache_returnsCacheImmediately`, `QueryReaderStrategyTest.cacheFirst_expiredInCache_fetchesFromNetwork`, `OverlayFreshnessTest.a_local_write_does_not_make_a_day_old_payload_fresh`</small> | The cached payload with the overlay; never a request.<br><small>`QueryReaderStrategyTest.cacheOnly_foundInCache_returnsCache`</small> |
| **Offline, warm** | Throws the connectivity failure, after supabase-kt's own retries (four attempts, 7 s with the default `maxRetries`).<br><small>`RetryDelayTest.a_network_only_read_keeps_upstreams_retries`, `QueryReaderStrategyTest.networkOnly_networkFailure_evenWithCachedData_throws`</small> | The cached payload with the overlay, at once: one attempt, no retry wait. Older than `cacheDuration`: throws. A 503 or 520 is still retried on supabase-kt's schedule.<br><small>`RetryDelayTest.a_network_first_read_with_a_warm_cache_falls_back_as_soon_as_the_network_fails`, `QueryReaderStrategyTest.networkFirst_networkFailure_cacheFallback_overlaysSyncedWritesToo`, `QueryReaderStrategyTest.networkFirst_networkFailure_expiredCache_throws`, `RetryDelayTest.a_network_first_read_with_a_warm_cache_still_rides_out_a_server_that_is_briefly_unavailable`</small> | Within `cacheDuration`: the cached payload, no request. Older: throws; the stale payload is not served.<br><small>`QueryReaderStrategyTest.cacheFirst_foundInCache_returnsCacheImmediately`, `QueryReaderStrategyTest.cacheFirst_expiredInCache_networkFailure_throws`</small> | The cached payload with the overlay. Older than `cacheDuration`: `NoSuchElementException`.<br><small>`QueryReaderStrategyTest.cacheOnly_foundInCache_returnsCache`, `QueryReaderStrategyTest.cacheOnly_expiredInCache_throws`</small> |
| **Offline, cold** | Throws the connectivity failure, after supabase-kt's retries.<br><small>`QueryReaderStrategyTest.networkOnly_networkFailure_throws`</small> | Throws the connectivity failure, after supabase-kt's retries (four attempts, 7 s).<br><small>`RetryDelayTest.a_network_first_read_with_a_cold_cache_keeps_upstreams_retries`</small> | Throws the connectivity failure.<br><small>`QueryReaderStrategyTest.cacheFirst_noCache_networkFailure_throws`</small> | `NoSuchElementException`; no request.<br><small>`QueryReaderStrategyTest.cacheOnly_noCache_logsError`</small> |
| **Session restoring** | Sends, then waits for the session (300, 900 and 2,400 ms, woken early when it settles) and asks again as the user; the user's answer is cached. A session that never settles: the last answer, uncached.<br><small>`ReadsWhileRestoringTest.a_read_that_needs_the_network_waits_for_the_session_and_caches_only_the_users_answer`, `ReadsWhileRestoringTest.a_read_whose_session_never_settles_returns_the_last_answer_uncached`</small> | As `NetworkOnly`.<br><small>`ReadsWhileRestoringTest.a_read_that_needs_the_network_waits_for_the_session_and_caches_only_the_users_answer`</small> | Warm: the cached payload, no request. Cold: as `NetworkOnly`.<br><small>`ReadsWhileRestoringTest.a_read_the_cache_answers_sends_nothing_while_the_session_restores`, `ReadsWhileRestoringTest.a_read_that_needs_the_network_waits_for_the_session_and_caches_only_the_users_answer`</small> | The cached payload, no request.<br><small>`ReadsWhileRestoringTest.a_read_the_cache_answers_sends_nothing_while_the_session_restores`</small> |

**A read of another shape.** `single()`, `maybeSingle()`, `head`, a `count`, `csv()`, `geojson()`,
`stripNulls()` and `explain()` do not return a list of rows, so the cache cannot hold their
answer. Whatever the strategy, such a read goes to the network, is not stored and is not overlaid;
offline it throws as `NetworkOnly` does. `maybeSingle()` with no row returns supabase-kt's empty
result at once, even while the session restores.
<small>`ReadShapeTest.each_shape_is_answered_by_the_network_and_never_stored_over_the_list`,
`ReadShapeTest.each_shape_goes_to_the_network_even_when_asked_for_the_cache`,
`ReadShapeTest.offline_each_shape_fails_as_a_network_only_read_does`,
`ReadShapeTest.maybe_single_with_no_row_returns_the_empty_result_at_once_while_restoring`</small>

## What a cache hit returns

## Exceptions
