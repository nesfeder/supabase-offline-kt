# One-shot reads

A `select` reads its rows once, from the server or from the device's cache, as its read strategy
says. Every list the server returns is kept for the next read, and the user's queued writes are
laid over every answer. Pull to refresh, the launch badge and Start over's lookup each use a
strategy of their own:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:first-read"

    --8<-- "data/supabase/OfflineTaskRepository.kt:badge-read"

    --8<-- "data/supabase/OfflineTaskRepository.kt:network-only"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:first-read"

    --8<-- "Data/TaskRepository.swift:badge-read"

    --8<-- "Data/TaskRepository.swift:network-only"
    ```

## Choosing a strategy

| You want | Use | What it does |
| --- | --- | --- |
| The server's rows, and the device's when the server cannot be reached | `NetworkFirst()`, the default | Falls back to the cached rows on a connectivity failure only: a server's error is thrown even with the query cached. With the query cached, a transport failure falls back at once, skipping supabase-kt's retries, while a `503` or `520` is still retried on supabase-kt's schedule; with nothing cached it retries as supabase-kt does, then throws |
| The same, but never rows older than some age | `NetworkFirst(maxAge = …)` | Cached rows older than `maxAge` count as none: the connectivity failure is thrown instead |
| A screen that opens without waiting: the device's rows while they are fresh enough, the server's otherwise | `CacheFirst(maxAge = …)` | The cached rows within `maxAge`, without a request; otherwise it asks the server. A list a write marked stale (a write the cache could not follow exactly) is fetched again, and when the server cannot be reached the stale rows are served after all |
| Only what is on the device, and never a request | `CacheOnly()` | The cached rows; with nothing it may serve, `CacheMissException` |
| The server's rows or the server's failure, as supabase-kt reads | `NetworkOnly` | Fails as supabase-kt does when the server cannot be reached; its answer is still cached, for the next read of another strategy |

A list's age is the time the server sent it, by the device's clock: your edit laid over a day-old
list leaves it a day old. `maxAge = null`, the default, accepts any age. The sample's photo lookup
reads `CacheOnly`:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:cache-only"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:cache-only"
    ```

## Where the rows came from

Three extension properties on supabase-kt's `PostgrestResult` say whether the rows came from the
cache, when they were fetched, and whether a queued write changed them; the sample's pull to
refresh turns them into the caption under the list's title ("Updated just now", "From this
device, 3 minutes ago"):

From the samples: [LibraryMappings.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LibraryMappings.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift) · [QueueWording.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Sync/QueueWording.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/LibraryMappings.kt:provenance"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:provenance"

    --8<-- "Sync/QueueWording.swift:provenance-caption"
    ```

The library stamps them in response headers PostgREST never sends, so a result supabase-kt
returned itself reads `false`, `null` and `false`, and a read the cache never holds, such as a
`count`, keeps the server's own headers.
<small>`QuerySnapshotTest.a_one_shot_read_says_where_its_rows_came_from`,
`QuerySnapshotTest.a_read_the_cache_never_holds_keeps_the_servers_own_headers`</small>

## Edge cases

**One row offline.** `single()` is never cached. Read the list and pick the row out on the device,
as the [sample app](../samples/android.md)'s task detail does:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskDetailModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Detail/TaskDetailModel.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:row-from-list"
    ```

=== "iOS"

    ```swift
    --8<-- "Detail/TaskDetailModel.swift:row-from-list"
    ```

**A list with a total.** A read that asks for a `count` is never cached, rows and all, so a
paginated screen with a total has nothing offline: read the rows and the count separately. The
samples count only where a connection is expected anyway, in the Sync tab's Server section and a
Try it item with the generic `filter(column, operator, value)`:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository+Server.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+Server.swift) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:count"

    --8<-- "data/supabase/OfflineTaskRepository.kt:operator-filter"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository+Server.swift:count"

    --8<-- "Data/TaskRepository.swift:operator-filter"
    ```

- **Two filters on one column** are refused with an `IllegalArgumentException` before anything is
  sent, since supabase-kt would send only the first: see
  [Coming from supabase-kt](../getting-started/migrating.md#two-filters-on-one-column).
- **While a stored session restores**, supabase-kt sends requests on the anon key, which row-level
  security can answer with fewer rows, or none, so nothing such a request returns is cached. A
  read the cache can answer for the stored user sends nothing; one that needs the server waits for
  the session to settle, a few seconds at most, and asks again. If it never settles, the read
  returns the anon key's answer uncached, with a warning; a `NetworkFirst` read with the query
  cached returns that answer too, not the cached rows.
- **An expired session offline.** A `NetworkFirst` read throws supabase-kt's
  `TokenExpiredException`, since supabase-kt cannot refresh the token, instead of serving the
  cache; `CacheFirst` and `CacheOnly` reads the cache can answer send nothing and are served.
- **A server that answers `503` for good** is not offline: a `NetworkFirst` read waits out
  supabase-kt's retries and throws, with no fallback to the cache.
- **A session without a user id** (no `user`, and a token without `sub`) is not a settled identity
  under the default trust rule: nothing it reads is cached, and a reactive read made with it never
  settles.
- **Another schema** is cached apart from the default schema's table of the same name. Name it
  with `from(schema, table)` or the request's `schema`: a raw `Accept-Profile` header reaches the
  server but not the cache key.
- **The request lambda runs more than once**: once to learn the read's shape, and once for each
  request sent, so it must build the same request every time
  ([Coming from supabase-kt](../getting-started/migrating.md#call-shapes-that-need-an-edit)). At
  supabase-kt's `DEBUG` log level, the first run logs a failed `GET` "stopped by the offline
  engine's read-shape check": that request was never sent.
- **Another identity's cache** is never read
  ([Signing out and switching users](sign-out.md#switching-accounts)).

## Behavior matrix

Facts about the engine, one row per condition; each cell names the test that pins it. **Online**:
the server answers. **Offline**: the request fails to connect or times out; a server's own error
(4xx or 5xx) is not offline, and every strategy that sent the request throws it. **Known down**:
the connectivity monitor says the network is unavailable; the read is still sent, once, with
supabase-kt's retries off. **Warm / cold**: the query's rows are cached / are not. **Session
restoring**: the Auth plugin is still loading a stored session, so a request goes out on the anon
key, and under the default trust rule nothing it returns is written to the cache. **Overlay**: the
writes still queued, and those replayed but not yet seen in a response, laid over what a read
returns.

| Condition | `NetworkOnly` | `NetworkFirst(maxAge)` | `CacheFirst(maxAge)` | `CacheOnly(maxAge)` |
|---|---|---|---|---|
| **Online** | The server's rows with the overlay; written to the cache.<br><small>`QueryReaderStrategyTest.networkOnly_successfulNetwork_returnsNetworkData`, `ResponseTrustTest.a_one_shot_read_without_auth_caches_an_empty_result`</small> | As `NetworkOnly`.<br><small>`QueryReaderStrategyTest.networkFirst_successfulNetwork_returnsNetworkDataAndCaches`, `QueryReaderStrategyTest.networkFirst_success_overlaysPendingWrites_andRetiresSyncedOnes`</small> | A cached payload within `maxAge`: that payload with the overlay, no request. Otherwise as `NetworkOnly`. Age is the time of the last response: a local write does not refresh it. A payload marked stale — an accepted write may have changed rows it cannot show — is fetched again.<br><small>`QueryReaderStrategyTest.cacheFirst_foundInCache_returnsCacheImmediately`, `QueryReaderStrategyTest.cacheFirst_expiredInCache_fetchesFromNetwork`, `OverlayFreshnessTest.a_local_write_does_not_make_a_day_old_payload_fresh`, `StaleQueriesTest.the_mark_survives_a_restart_and_clears_once_the_list_is_fetched`</small> | The cached payload with the overlay; never a request.<br><small>`QueryReaderStrategyTest.cacheOnly_foundInCache_returnsCache`</small> |
| **Offline, warm** | Throws the connectivity failure, after supabase-kt's own retries (four attempts, 7 s with the default `maxRetries`); in one attempt while the network is known down.<br><small>`RetryDelayTest.a_network_only_read_keeps_upstreams_retries`, `QueryReaderStrategyTest.networkOnly_networkFailure_evenWithCachedData_throws`</small> | The cached payload with the overlay, at once, marked stale or not: one attempt, no retry wait. Older than `maxAge`: throws. A 503 or 520 is still retried on supabase-kt's schedule.<br><small>`RetryDelayTest.a_network_first_read_with_a_warm_cache_falls_back_as_soon_as_the_network_fails`, `QueryReaderStrategyTest.networkFirst_networkFailure_cacheFallback_overlaysSyncedWritesToo`, `StaleQueriesTest.a_stale_list_is_still_served_offline_by_every_strategy`, `QueryReaderStrategyTest.networkFirst_networkFailure_expiredCache_throws`, `RetryDelayTest.a_network_first_read_with_a_warm_cache_still_rides_out_a_server_that_is_briefly_unavailable`</small> | Within `maxAge`: the cached payload, no request; marked stale, the request fails and the payload is served. Older than `maxAge`: throws.<br><small>`QueryReaderStrategyTest.cacheFirst_foundInCache_returnsCacheImmediately`, `StaleQueriesTest.a_stale_list_is_still_served_offline_by_every_strategy`, `QueryReaderStrategyTest.cacheFirst_expiredInCache_networkFailure_throws`</small> | The cached payload with the overlay, marked stale or not. Older than `maxAge`: `CacheMissException`.<br><small>`QueryReaderStrategyTest.cacheOnly_foundInCache_returnsCache`, `StaleQueriesTest.a_stale_list_is_still_served_offline_by_every_strategy`, `QueryReaderStrategyTest.cacheOnly_expiredInCache_throws`</small> |
| **Offline, cold** | Throws the connectivity failure, after supabase-kt's retries; in one attempt while the network is known down.<br><small>`QueryReaderStrategyTest.networkOnly_networkFailure_throws`, `ReadsWhileKnownOfflineTest.a_network_only_read_is_sent_once`</small> | Throws the connectivity failure, after supabase-kt's retries (four attempts, 7 s); in one attempt while the network is known down.<br><small>`RetryDelayTest.a_network_first_read_with_a_cold_cache_keeps_upstreams_retries`, `ReadsWhileKnownOfflineTest.a_cold_network_first_read_is_sent_once`</small> | Throws the connectivity failure.<br><small>`QueryReaderStrategyTest.cacheFirst_noCache_networkFailure_throws`</small> | `CacheMissException`; no request.<br><small>`QueryReaderStrategyTest.cacheOnly_noCache_logsInfo`</small> |
| **Session restoring** | Sends, then waits for the session (300, 900 and 2,400 ms, woken early when it settles) and asks again. Settled as the stored user: the user's answer, cached. Settled signed out: the anonymous answer; the rows cached for the last user are dropped and never served. Never settles: the last answer, uncached.<br><small>`ReadsWhileRestoringTest.a_read_that_needs_the_network_waits_for_the_session_and_caches_only_the_users_answer`, `CacheBelongsToOneIdentityTest.a_read_waiting_on_a_restore_that_settles_signed_out_gets_the_anonymous_answer`, `ReadsWhileRestoringTest.a_read_whose_session_never_settles_returns_the_last_answer_uncached`</small> | As `NetworkOnly`.<br><small>`ReadsWhileRestoringTest.a_read_that_needs_the_network_waits_for_the_session_and_caches_only_the_users_answer`, `CacheBelongsToOneIdentityTest.a_read_waiting_on_a_restore_that_settles_signed_out_gets_the_anonymous_answer`</small> | Warm with the stored user's cache: the cached payload, no request. Cold, or no stored user: as `NetworkOnly`.<br><small>`ReadsWhileRestoringTest.a_read_the_cache_answers_sends_nothing_while_the_session_restores`, `ReadsWhileRestoringTest.a_read_that_needs_the_network_waits_for_the_session_and_caches_only_the_users_answer`, `CacheBelongsToOneIdentityTest.a_read_waiting_on_a_restore_that_settles_signed_out_gets_the_anonymous_answer`</small> | The cached payload, no request.<br><small>`ReadsWhileRestoringTest.a_read_the_cache_answers_sends_nothing_while_the_session_restores`</small> |

**A read of another shape.** `single()`, `maybeSingle()` (supabase-kt 3.8), `head`, a `count`
(with rows or without), `csv()`, `geojson()`, `stripNulls()` and `explain()` do not return a plain
list, so their answer is neither stored nor overlaid. Under every strategy but `CacheOnly` such a
read goes to the network, and offline throws as `NetworkOnly` does; under `CacheOnly` it sends
nothing and throws `CacheMissException`. On supabase-kt 3.8, `maybeSingle()` with no row returns
supabase-kt's empty result at once, even while the session restores. The shape is read from the
request supabase-kt builds, before anything is sent; a read whose request cannot be built (no
token to be had) fails as sending it would, and is never answered from the list.
<small>`ReadShapeTest.each_shape_is_answered_by_the_network_and_never_stored_over_the_list`,
`ReadShapeTest.each_shape_goes_to_the_network_even_when_asked_for_the_cache`,
`ReadShapeTest.offline_each_shape_fails_as_a_network_only_read_does`,
`ReadShapeTest.a_cache_only_read_of_each_shape_sends_nothing_and_says_the_cache_cannot_answer_it`,
`MaybeSingleWhileRestoringTest.maybe_single_with_no_row_returns_the_empty_result_at_once_while_restoring`
(from supabase-kt 3.8),
`ReadShapeTest.a_client_that_cannot_resolve_a_token_never_answers_a_shape_from_the_list`</small>

**The request lambda and schemas.**
<small>`RequestLambdaRunsTest.a_cache_hit_runs_the_request_once`,
`RequestLambdaRunsTest.a_network_read_runs_the_request_once_to_learn_its_shape_and_once_to_send_it`,
`NonDefaultSchemaTest.the_same_table_in_two_schemas_is_cached_apart`,
`NonDefaultSchemaTest.a_schema_set_in_the_request_is_cached_apart_too`</small>
