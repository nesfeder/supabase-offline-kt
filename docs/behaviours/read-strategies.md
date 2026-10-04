# One-shot reads

How a one-shot `select` chooses between the network and the cache, strategy by strategy.

> **Not written yet.** This page is a placeholder in the planned site. The headings below are its
> outline; the content will be written for 0.1.0, once the API it describes has settled.

## Choosing a strategy

Under every strategy, a read with two top-level filters on one column (`gte("age", 1);
lte("age", 10)`) throws `IllegalArgumentException` before anything is sent: supabase-kt would send
only the first. Write them as one `and { }` group, which sends both.

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
| **Online** | The server's rows with the overlay; written to the cache.<br><small>`QueryReaderStrategyTest.networkOnly_successfulNetwork_returnsNetworkData`, `ResponseTrustTest.a_one_shot_read_without_auth_caches_an_empty_result`</small> | As `NetworkOnly`.<br><small>`QueryReaderStrategyTest.networkFirst_successfulNetwork_returnsNetworkDataAndCaches`, `QueryReaderStrategyTest.networkFirst_success_overlaysPendingWrites_andRetiresSyncedOnes`</small> | A cached payload within `cacheDuration`: that payload with the overlay, no request. Otherwise as `NetworkOnly`. Age is the time of the last response: a local write does not refresh it. A payload marked stale — an accepted write may have changed rows it cannot show — is fetched again.<br><small>`QueryReaderStrategyTest.cacheFirst_foundInCache_returnsCacheImmediately`, `QueryReaderStrategyTest.cacheFirst_expiredInCache_fetchesFromNetwork`, `OverlayFreshnessTest.a_local_write_does_not_make_a_day_old_payload_fresh`, `StaleQueriesTest.the_mark_survives_a_restart_and_clears_once_the_list_is_fetched`</small> | The cached payload with the overlay; never a request.<br><small>`QueryReaderStrategyTest.cacheOnly_foundInCache_returnsCache`</small> |
| **Offline, warm** | Throws the connectivity failure, after supabase-kt's own retries (four attempts, 7 s with the default `maxRetries`).<br><small>`RetryDelayTest.a_network_only_read_keeps_upstreams_retries`, `QueryReaderStrategyTest.networkOnly_networkFailure_evenWithCachedData_throws`</small> | The cached payload with the overlay, at once, marked stale or not: one attempt, no retry wait. Older than `cacheDuration`: throws. A 503 or 520 is still retried on supabase-kt's schedule.<br><small>`RetryDelayTest.a_network_first_read_with_a_warm_cache_falls_back_as_soon_as_the_network_fails`, `QueryReaderStrategyTest.networkFirst_networkFailure_cacheFallback_overlaysSyncedWritesToo`, `StaleQueriesTest.a_stale_list_is_still_served_offline_by_every_strategy`, `QueryReaderStrategyTest.networkFirst_networkFailure_expiredCache_throws`, `RetryDelayTest.a_network_first_read_with_a_warm_cache_still_rides_out_a_server_that_is_briefly_unavailable`</small> | Within `cacheDuration`: the cached payload, no request; marked stale, the request fails and the payload is served. Older than `cacheDuration`: throws.<br><small>`QueryReaderStrategyTest.cacheFirst_foundInCache_returnsCacheImmediately`, `StaleQueriesTest.a_stale_list_is_still_served_offline_by_every_strategy`, `QueryReaderStrategyTest.cacheFirst_expiredInCache_networkFailure_throws`</small> | The cached payload with the overlay, marked stale or not. Older than `cacheDuration`: `NoSuchElementException`.<br><small>`QueryReaderStrategyTest.cacheOnly_foundInCache_returnsCache`, `StaleQueriesTest.a_stale_list_is_still_served_offline_by_every_strategy`, `QueryReaderStrategyTest.cacheOnly_expiredInCache_throws`</small> |
| **Offline, cold** | Throws the connectivity failure, after supabase-kt's retries.<br><small>`QueryReaderStrategyTest.networkOnly_networkFailure_throws`</small> | Throws the connectivity failure, after supabase-kt's retries (four attempts, 7 s).<br><small>`RetryDelayTest.a_network_first_read_with_a_cold_cache_keeps_upstreams_retries`</small> | Throws the connectivity failure.<br><small>`QueryReaderStrategyTest.cacheFirst_noCache_networkFailure_throws`</small> | `NoSuchElementException`; no request.<br><small>`QueryReaderStrategyTest.cacheOnly_noCache_logsError`</small> |
| **Session restoring** | Sends, then waits for the session (300, 900 and 2,400 ms, woken early when it settles) and asks again. Settled as the stored user: the user's answer, cached. Settled signed out: the anonymous answer; the rows cached for the last user are dropped and never served. Never settles: the last answer, uncached.<br><small>`ReadsWhileRestoringTest.a_read_that_needs_the_network_waits_for_the_session_and_caches_only_the_users_answer`, `CacheBelongsToOneIdentityTest.a_read_waiting_on_a_restore_that_settles_signed_out_gets_the_anonymous_answer`, `ReadsWhileRestoringTest.a_read_whose_session_never_settles_returns_the_last_answer_uncached`</small> | As `NetworkOnly`.<br><small>`ReadsWhileRestoringTest.a_read_that_needs_the_network_waits_for_the_session_and_caches_only_the_users_answer`, `CacheBelongsToOneIdentityTest.a_read_waiting_on_a_restore_that_settles_signed_out_gets_the_anonymous_answer`</small> | Warm with the stored user's cache: the cached payload, no request. Cold, or no stored user: as `NetworkOnly`.<br><small>`ReadsWhileRestoringTest.a_read_the_cache_answers_sends_nothing_while_the_session_restores`, `ReadsWhileRestoringTest.a_read_that_needs_the_network_waits_for_the_session_and_caches_only_the_users_answer`, `CacheBelongsToOneIdentityTest.a_read_waiting_on_a_restore_that_settles_signed_out_gets_the_anonymous_answer`</small> | The cached payload, no request.<br><small>`ReadsWhileRestoringTest.a_read_the_cache_answers_sends_nothing_while_the_session_restores`</small> |

**A read of another shape.** `single()`, `maybeSingle()` (supabase-kt 3.8), `head`, a `count` (with
rows or without), `csv()`, `geojson()`, `stripNulls()` and `explain()` do not return a plain list of
rows, so the cache does not hold their answer. Under every strategy but `CacheOnly` such a read goes
to the network, is not stored and is not overlaid; offline it throws as `NetworkOnly` does, after
supabase-kt's retries. Under `CacheOnly` it sends nothing and throws `NoSuchElementException`. On
supabase-kt 3.8, `maybeSingle()` with no row returns supabase-kt's empty result at once, even while
the session restores. The shape is read from the request supabase-kt builds, before anything is
sent. A read whose request cannot be built (no token to be had) fails as sending it would, and is
never answered from the list.
<small>`ReadShapeTest.each_shape_is_answered_by_the_network_and_never_stored_over_the_list`,
`ReadShapeTest.each_shape_goes_to_the_network_even_when_asked_for_the_cache`,
`ReadShapeTest.offline_each_shape_fails_as_a_network_only_read_does`,
`ReadShapeTest.a_cache_only_read_of_each_shape_sends_nothing_and_says_the_cache_cannot_answer_it`,
`MaybeSingleWhileRestoringTest.maybe_single_with_no_row_returns_the_empty_result_at_once_while_restoring`
(from supabase-kt 3.8),
`ReadShapeTest.a_client_that_cannot_resolve_a_token_never_answers_a_shape_from_the_list`</small>

Two consequences to plan for. A paginated list that also asks for a total (`count`) is not cached,
so it has no offline answer: read the count separately. And to read one row offline, read the list
(`eq` on its key) and take its first row, instead of `single()`.

**Whose cache it is.** With the Auth plugin, the cache belongs to one identity at a time: a user,
or nobody signed in. A read never returns what was cached for another identity, and when the
session settles into a different one, the cache is dropped; queued writes are kept. See
[Signing out and switching users](sign-out.md#switching-accounts).

**The request lambda runs more than once.** A read runs its `request` lambda once to learn the
read's shape and again each time it is sent, so a cache hit runs it once and a network read at
least twice. It must configure the same request every time: no side effects, and no clock or
counter read inside it.
<small>`RequestLambdaRunsTest.a_cache_hit_runs_the_request_once`,
`RequestLambdaRunsTest.a_network_read_runs_the_request_once_to_learn_its_shape_and_once_to_send_it`</small>

**Schemas.** A table in another schema is cached apart from the default schema's table of the
same name. Name the schema with `from(schema, table)` or the request's `schema`: a raw
`Accept-Profile` header reaches the server but not the cache key.
<small>`NonDefaultSchemaTest.the_same_table_in_two_schemas_is_cached_apart`,
`NonDefaultSchemaTest.a_schema_set_in_the_request_is_cached_apart_too`</small>

**Sessions without a user id.** Under the default trust rule, only an answer given to a settled
identity is cached. A signed-in session whose user carries no id (no `user`, and a token without
`sub`) is not one, so nothing it reads is cached, and a reactive read made with it never settles.

**A debug line to ignore.** With supabase-kt's own logging at DEBUG, each read logs a failed `GET`
"stopped by the offline engine's read-shape check". That request was never sent: it is how the
engine learns the read's shape.

## What a cache hit returns

## Exceptions
