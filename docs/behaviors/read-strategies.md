# One-shot reads

A `select` reads its rows once, from the server or from the device's cache, as its read strategy
says. Only a list of rows is cached, and every list the server returns is kept for the next read,
with the user's queued writes laid over it whichever way it is answered.

*From the samples: [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)*

=== "Android"

    ```kotlin
    --8<-- "ReadExamples.kt:strategies"
    ```

=== "iOS"

    ```swift
    --8<-- "samples/ios/OfflineTasks/Data/TaskRepository.swift:first-read"

    --8<-- "samples/ios/OfflineTasks/Data/TaskRepository.swift:badge-read"

    --8<-- "samples/ios/OfflineTasks/Data/TaskRepository.swift:network-only-read"
    ```

## Choosing a strategy

| You want | Use |
| --- | --- |
| The server's rows, and the device's when the server cannot be reached | `NetworkFirst()`, the default |
| The same, but never rows older than some age | `NetworkFirst(maxAge = …)` |
| A screen that opens without waiting: the device's rows while they are fresh enough, the server's otherwise | `CacheFirst(maxAge = …)` |
| Only what is on the device, and never a request | `CacheOnly()` |
| The server's rows or the server's failure, as supabase-kt reads | `NetworkOnly` |

A screen that should update as its rows change uses `selectAsFlow` instead; see
[Reactive reads](reactive-reads.md).

## `NetworkFirst`

It asks the server, and serves the cached rows when the server cannot be reached. Only a
connectivity failure falls back: a server's error is thrown even with the query cached, so the
cache never hides a refused request. With the query cached, a transport failure falls back at
once, skipping supabase-kt's retries of it; a `503` or `520` is still retried on supabase-kt's
schedule. With nothing cached it retries as supabase-kt does, then throws.

`maxAge` bounds what the fallback may serve: cached rows older than it count as none, and the
connectivity failure is thrown instead.

## `CacheFirst`

It serves the cached rows when there are any within `maxAge`, and asks the server otherwise. A
cached list that a write marked stale (a write the cache could not follow exactly) is fetched
again; when the server cannot be reached, the stale rows are served after all.

## `CacheOnly`

It serves the cached rows and never sends a request. With nothing it may serve, it throws
`CacheMissException`:

*From the samples: [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)*

=== "Android"

    ```kotlin
    --8<-- "ReadExamples.kt:cache-only"
    ```

=== "iOS"

    ```swift
    --8<-- "samples/ios/OfflineTasks/Data/TaskRepository.swift:cache-only"
    ```

## `NetworkOnly`

It asks the server and fails as supabase-kt does when the server cannot be reached. Its answer is
still cached, for the next read of another strategy.

## How old the rows are

A cached list's age is the time the server sent it, by the device's clock. A local write applied
to it does not make it younger: a day-old list with your edit laid over it is still a day old.
`maxAge = null`, the default, accepts any age.

## Where the rows came from

A result says whether it came from the cache, when its rows were fetched, and whether a queued
write changed them, through three extension properties on supabase-kt's `PostgrestResult`:

*From the samples: [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift) · [QueueWording.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Sync/QueueWording.swift)*

=== "Android"

    ```kotlin
    --8<-- "ReadExamples.kt:provenance"
    ```

=== "iOS"

    ```swift
    --8<-- "samples/ios/OfflineTasks/Data/TaskRepository.swift:provenance"

    --8<-- "samples/ios/OfflineTasks/Sync/QueueWording.swift:provenance-caption"
    ```

A result supabase-kt returned itself reads `false`, `null` and `false`. The library stamps these
in response headers PostgREST never sends; a read the cache never holds, such as a `count`, keeps
the server's own headers.

## Edge cases

**One row offline.** `single()` is never cached. Read the list with the row's key and take its
first row; the [sample app](../samples/android.md) reads its list once and finds the row in it,
so a task never opened before still opens offline.

*From the samples: [TaskDetailModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Detail/TaskDetailModel.swift)*

=== "Android"

    ```kotlin
    --8<-- "ReadExamples.kt:one-row"
    ```

=== "iOS"

    ```swift
    --8<-- "samples/ios/OfflineTasks/Detail/TaskDetailModel.swift:row-from-list"
    ```

**A list with a total.** A read that asks for a `count` is never cached, rows and all, so a
paginated screen with a total has nothing offline. Read the rows and the count separately.

**Two filters on one column** are refused with an `IllegalArgumentException` before anything is
sent: supabase-kt sends only the first, so the read would return every row the first admits.
Write them as one `and { }` group:

*From the samples: [TaskQuery.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskQuery.swift)*

=== "Android"

    ```kotlin
    --8<-- "ReadExamples.kt:same-column"
    ```

=== "iOS"

    ```swift
    --8<-- "samples/ios/OfflineTasks/Data/TaskQuery.swift:same-column"
    ```

**While a stored session restores.** supabase-kt sends requests on the anon key until the stored
session is restored, and row-level security can answer such a request with fewer rows, or none.
So nothing such a request returns is cached. A read the cache can answer for the stored user is
answered from it and sends nothing. A read that needs the server waits for the session to settle,
a few seconds at most, and asks again; if the session never settles, it returns the anon key's
answer uncached, with a warning, which under row-level security can hold fewer rows. A
`NetworkFirst` read with the query cached returns that answer too, not the cached rows.

**An expired session offline.** With a signed-in session whose token has expired, a `NetworkFirst`
read offline throws supabase-kt's `TokenExpiredException`, because supabase-kt cannot refresh the
token, instead of serving the cache. `CacheFirst` and `CacheOnly` reads the cache can answer send
nothing and are served.

**A server that answers `503` for good.** A `503` is the server's answer, not a connectivity
failure, so a `NetworkFirst` read waits out supabase-kt's retries and throws, with no fallback to
the cache.

**A session without a user id.** Under the default trust rule only an answer to a settled
identity is cached. A signed-in session whose user carries no id (no `user`, and a token without
`sub`) is not one, so nothing it reads is cached, and a reactive read made with it never settles.

**Another schema.** A table in another schema is cached apart from the default schema's table of
the same name. Name the schema with `from(schema, table)` or the request's `schema`: a raw
`Accept-Profile` header reaches the server but not the cache key.

**The request lambda runs more than once:** once to learn the read's shape, and once for each
request sent. It must build the same request every time: no side effects, and no clock or counter
read inside it.

**A debug line to ignore.** With supabase-kt's own logging at `DEBUG`, each read logs a failed
`GET` "stopped by the offline engine's read-shape check". That request was never sent: it is how
the library learns the read's shape without asking for a token.

## Behavior matrix

Facts about the engine, one row per condition. Each cell names the test that pins it.

- **Online:** the server answers.
- **Offline:** the request fails to connect or times out. A server's own error (4xx or 5xx) is
  not offline: every strategy that sent the request throws it.
- **Known down:** the connectivity monitor says the network is unavailable. The read is still
  sent, once, with supabase-kt's retries off.
- **Warm / cold:** the query's rows are cached / are not.
- **Session restoring:** the Auth plugin is still loading a stored session, so a request goes out
  on the anon key. Under the default trust rule nothing such a request returns is written to the
  cache.
- **Overlay:** writes still in the queue, and those replayed but not yet seen in a response, are
  applied on top of what a read returns.

| Condition | `NetworkOnly` | `NetworkFirst(maxAge)` | `CacheFirst(maxAge)` | `CacheOnly(maxAge)` |
|---|---|---|---|---|
| **Online** | The server's rows with the overlay; written to the cache.<br><small>`QueryReaderStrategyTest.networkOnly_successfulNetwork_returnsNetworkData`, `ResponseTrustTest.a_one_shot_read_without_auth_caches_an_empty_result`</small> | As `NetworkOnly`.<br><small>`QueryReaderStrategyTest.networkFirst_successfulNetwork_returnsNetworkDataAndCaches`, `QueryReaderStrategyTest.networkFirst_success_overlaysPendingWrites_andRetiresSyncedOnes`</small> | A cached payload within `maxAge`: that payload with the overlay, no request. Otherwise as `NetworkOnly`. Age is the time of the last response: a local write does not refresh it. A payload marked stale — an accepted write may have changed rows it cannot show — is fetched again.<br><small>`QueryReaderStrategyTest.cacheFirst_foundInCache_returnsCacheImmediately`, `QueryReaderStrategyTest.cacheFirst_expiredInCache_fetchesFromNetwork`, `OverlayFreshnessTest.a_local_write_does_not_make_a_day_old_payload_fresh`, `StaleQueriesTest.the_mark_survives_a_restart_and_clears_once_the_list_is_fetched`</small> | The cached payload with the overlay; never a request.<br><small>`QueryReaderStrategyTest.cacheOnly_foundInCache_returnsCache`</small> |
| **Offline, warm** | Throws the connectivity failure, after supabase-kt's own retries (four attempts, 7 s with the default `maxRetries`); in one attempt while the network is known down.<br><small>`RetryDelayTest.a_network_only_read_keeps_upstreams_retries`, `QueryReaderStrategyTest.networkOnly_networkFailure_evenWithCachedData_throws`</small> | The cached payload with the overlay, at once, marked stale or not: one attempt, no retry wait. Older than `maxAge`: throws. A 503 or 520 is still retried on supabase-kt's schedule.<br><small>`RetryDelayTest.a_network_first_read_with_a_warm_cache_falls_back_as_soon_as_the_network_fails`, `QueryReaderStrategyTest.networkFirst_networkFailure_cacheFallback_overlaysSyncedWritesToo`, `StaleQueriesTest.a_stale_list_is_still_served_offline_by_every_strategy`, `QueryReaderStrategyTest.networkFirst_networkFailure_expiredCache_throws`, `RetryDelayTest.a_network_first_read_with_a_warm_cache_still_rides_out_a_server_that_is_briefly_unavailable`</small> | Within `maxAge`: the cached payload, no request; marked stale, the request fails and the payload is served. Older than `maxAge`: throws.<br><small>`QueryReaderStrategyTest.cacheFirst_foundInCache_returnsCacheImmediately`, `StaleQueriesTest.a_stale_list_is_still_served_offline_by_every_strategy`, `QueryReaderStrategyTest.cacheFirst_expiredInCache_networkFailure_throws`</small> | The cached payload with the overlay, marked stale or not. Older than `maxAge`: `CacheMissException`.<br><small>`QueryReaderStrategyTest.cacheOnly_foundInCache_returnsCache`, `StaleQueriesTest.a_stale_list_is_still_served_offline_by_every_strategy`, `QueryReaderStrategyTest.cacheOnly_expiredInCache_throws`</small> |
| **Offline, cold** | Throws the connectivity failure, after supabase-kt's retries; in one attempt while the network is known down.<br><small>`QueryReaderStrategyTest.networkOnly_networkFailure_throws`, `ReadsWhileKnownOfflineTest.a_network_only_read_is_sent_once`</small> | Throws the connectivity failure, after supabase-kt's retries (four attempts, 7 s); in one attempt while the network is known down.<br><small>`RetryDelayTest.a_network_first_read_with_a_cold_cache_keeps_upstreams_retries`, `ReadsWhileKnownOfflineTest.a_cold_network_first_read_is_sent_once`</small> | Throws the connectivity failure.<br><small>`QueryReaderStrategyTest.cacheFirst_noCache_networkFailure_throws`</small> | `CacheMissException`; no request.<br><small>`QueryReaderStrategyTest.cacheOnly_noCache_logsInfo`</small> |
| **Session restoring** | Sends, then waits for the session (300, 900 and 2,400 ms, woken early when it settles) and asks again. Settled as the stored user: the user's answer, cached. Settled signed out: the anonymous answer; the rows cached for the last user are dropped and never served. Never settles: the last answer, uncached.<br><small>`ReadsWhileRestoringTest.a_read_that_needs_the_network_waits_for_the_session_and_caches_only_the_users_answer`, `CacheBelongsToOneIdentityTest.a_read_waiting_on_a_restore_that_settles_signed_out_gets_the_anonymous_answer`, `ReadsWhileRestoringTest.a_read_whose_session_never_settles_returns_the_last_answer_uncached`</small> | As `NetworkOnly`.<br><small>`ReadsWhileRestoringTest.a_read_that_needs_the_network_waits_for_the_session_and_caches_only_the_users_answer`, `CacheBelongsToOneIdentityTest.a_read_waiting_on_a_restore_that_settles_signed_out_gets_the_anonymous_answer`</small> | Warm with the stored user's cache: the cached payload, no request. Cold, or no stored user: as `NetworkOnly`.<br><small>`ReadsWhileRestoringTest.a_read_the_cache_answers_sends_nothing_while_the_session_restores`, `ReadsWhileRestoringTest.a_read_that_needs_the_network_waits_for_the_session_and_caches_only_the_users_answer`, `CacheBelongsToOneIdentityTest.a_read_waiting_on_a_restore_that_settles_signed_out_gets_the_anonymous_answer`</small> | The cached payload, no request.<br><small>`ReadsWhileRestoringTest.a_read_the_cache_answers_sends_nothing_while_the_session_restores`</small> |

**A read of another shape.** `single()`, `maybeSingle()` (supabase-kt 3.8), `head`, a `count` (with
rows or without), `csv()`, `geojson()`, `stripNulls()` and `explain()` do not return a plain list of
rows, so the cache does not hold their answer. Under every strategy but `CacheOnly` such a read goes
to the network, is not stored and is not overlaid; offline it throws as `NetworkOnly` does. Under
`CacheOnly` it sends nothing and throws `CacheMissException`. On supabase-kt 3.8, `maybeSingle()`
with no row returns supabase-kt's empty result at once, even while the session restores. The shape
is read from the request supabase-kt builds, before anything is sent. A read whose request cannot
be built (no token to be had) fails as sending it would, and is never answered from the list.
<small>`ReadShapeTest.each_shape_is_answered_by_the_network_and_never_stored_over_the_list`,
`ReadShapeTest.each_shape_goes_to_the_network_even_when_asked_for_the_cache`,
`ReadShapeTest.offline_each_shape_fails_as_a_network_only_read_does`,
`ReadShapeTest.a_cache_only_read_of_each_shape_sends_nothing_and_says_the_cache_cannot_answer_it`,
`MaybeSingleWhileRestoringTest.maybe_single_with_no_row_returns_the_empty_result_at_once_while_restoring`
(from supabase-kt 3.8),
`ReadShapeTest.a_client_that_cannot_resolve_a_token_never_answers_a_shape_from_the_list`</small>

**Whose cache it is.** With the Auth plugin, the cache belongs to one identity at a time: a user,
or nobody signed in. A read never returns what was cached for another identity, and when the
session settles into a different one, the cache is dropped; queued writes are kept. See
[Signing out and switching users](sign-out.md#switching-accounts).

**The request lambda.**
<small>`RequestLambdaRunsTest.a_cache_hit_runs_the_request_once`,
`RequestLambdaRunsTest.a_network_read_runs_the_request_once_to_learn_its_shape_and_once_to_send_it`</small>

**Schemas.**
<small>`NonDefaultSchemaTest.the_same_table_in_two_schemas_is_cached_apart`,
`NonDefaultSchemaTest.a_schema_set_in_the_request_is_cached_apart_too`</small>
