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

## Edge cases

**One row offline.** `single()` asks for the rows the list read of the same query does, as one
object, so it is cached as that list, one row long: a `single()` and the list read with the same
filters, columns, order and range share one cached payload, and either one fills it. Online,
`single()` returns the server's object as it came. Offline, or when the network fails, under
`NetworkFirst`, `CacheFirst` and `CacheOnly`, the cached list answers it when it holds exactly one
row, with the user's queued writes laid over it, as one object marked as from the cache;
`CacheFirst` answers from it first, without a request. No row or several answer nothing: the read
fails as it would with nothing cached, and `CacheOnly` throws `CacheMissException`.
`maybeSingle()` (supabase-kt 3.8) asks the server the same way and is cached and answered the same
way, but offline it cannot answer no row with `null`: nothing in its request tells it from
`single()`, and its blank answer online stands for no row and for several, so it is not stored. A
row never read with its own query is not answered from another cached list. Read the list and pick
the row out on the device, as the [sample app](../samples/android.md)'s task detail does:

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
- **While a stored session restores, or after its token refresh failed** (supabase-kt tries the
  refresh again, every ten seconds by default), supabase-kt sends requests on the anon key, which
  row-level security can answer with fewer rows, or none, so nothing such a request returns is
  cached. A read the cache can answer for the stored user sends nothing. A list read that needs
  the server waits for the session to settle, a few seconds at most, and asks again; `single()`
  and a read of a shape the cache cannot hold (a `count`, `head`, …) wait the same way before they
  are sent, once. If the session never settles, a `NetworkFirst` read with the query cached, or a
  `CacheFirst` read of a list marked stale, serves the cached rows, or the one cached row a
  `single()` asks for, with a warning; with nothing cached, or under `NetworkOnly`, the read
  returns the anon key's answer uncached, with a warning.
- **An expired session offline.** A `NetworkFirst` read throws supabase-kt's
  `TokenExpiredException`, since supabase-kt cannot refresh the token, instead of serving the
  cache; `CacheFirst` and `CacheOnly` reads the cache can answer send nothing and are served.
- **A server that answers `503` for good** is not offline: a `NetworkFirst` read waits out
  supabase-kt's retries and throws, with no fallback to the cache.
- **An answer that is not a list of rows** (a `2xx` whose body is not a JSON array, or not JSON: a
  captive portal's sign-in page, a proxy's block page, a self-hosted server's fallback page) is
  never written to the cache. A `NetworkFirst` or `CacheFirst` read with the query cached within
  its `maxAge` is served the cached rows, marked as from the cache; a `NetworkOnly` read, or one
  without such rows, is served the body as it came. A reactive read's refresh asks again, and reads
  `Stalled` after its fast retries if the pages keep coming. A blank `2xx` body is an empty list.
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

Facts about the engine, one row per condition. **Online**: the server answers. **Offline**: the
request fails to connect or times out; a server's own error (4xx or 5xx) is not offline, and every
strategy that sent the request throws it. **Known down**: the connectivity monitor says the network
is unavailable; the read is still sent, once, with supabase-kt's retries off. **Warm / cold**: the
query's rows are cached / are not. **Session restoring**: the Auth plugin is still loading a stored
session, so a request goes out on the anon key, and under the default trust rule nothing it returns
is written to the cache. **Session refresh failed**: the stored session expired and supabase-kt
could not refresh it (it tries again, every ten seconds by default), so a request goes out on the
anon key too. **Overlay**: the writes still queued, and those replayed but not yet seen in a
response, laid over what a read returns.

| Condition | `NetworkOnly` | `NetworkFirst(maxAge)` | `CacheFirst(maxAge)` | `CacheOnly(maxAge)` |
|---|---|---|---|---|
| **Online** | The server's rows with the overlay; written to the cache. | As `NetworkOnly`. | A cached payload within `maxAge`: that payload with the overlay, no request. Otherwise as `NetworkOnly`. Age is the time of the last response: a local write does not refresh it. A payload marked stale — an accepted write may have changed rows it cannot show — is fetched again. | The cached payload with the overlay; never a request. |
| **Offline, warm** | Throws the connectivity failure, after supabase-kt's own retries (four attempts, 7 s with the default `maxRetries`); in one attempt while the network is known down. | The cached payload with the overlay, at once, marked stale or not: one attempt, no retry wait. Older than `maxAge`: throws. A 503 or 520 is still retried on supabase-kt's schedule. | Within `maxAge`: the cached payload, no request; marked stale, the request fails and the payload is served. Older than `maxAge`: throws. | The cached payload with the overlay, marked stale or not. Older than `maxAge`: `CacheMissException`. |
| **Offline, cold** | Throws the connectivity failure, after supabase-kt's retries; in one attempt while the network is known down. | Throws the connectivity failure, after supabase-kt's retries (four attempts, 7 s); in one attempt while the network is known down. | Throws the connectivity failure. | `CacheMissException`; no request. |
| **Session restoring** | Sends, then waits for the session (300, 900 and 2,400 ms, woken early when it settles) and asks again. Settled as the stored user: the user's answer, cached. Settled signed out: the anonymous answer; the rows cached for the last user are dropped and never served. Never settles: the last answer, uncached. | As `NetworkOnly`; never settles with the query cached within `maxAge`: the cached payload with the overlay, marked stale or not. | A payload of the stored user's within `maxAge`, not marked stale: that payload, no request. Otherwise as `NetworkOnly`; never settles with a payload marked stale within `maxAge`: that payload with the overlay. | The cached payload, no request. |
| **Session refresh failed** | As **Session restoring**, the wait woken early when the refresh lands. | As **Session restoring**. | As **Session restoring**. | The cached payload, no request. |

**A read of another shape.** `head`, a `count` (with rows or without), `csv()`, `geojson()`,
`stripNulls()` and `explain()`, `single()` with `stripNulls()` among them, do not return a plain
list, so their answer is neither stored nor overlaid. Under every strategy but `CacheOnly` such a
read goes to the network, and offline throws as `NetworkOnly` does; under `CacheOnly` it sends
nothing and throws `CacheMissException`. While the session restores, or after its refresh failed,
it waits for the session as a list read does (300, 900 and 2,400 ms, woken early when the session
settles), then is sent once, settled or not. `single()` and `maybeSingle()` (supabase-kt 3.8) wait
the same way and are cached as their list, one row long (see **One row offline** above); on
supabase-kt 3.8, `maybeSingle()` with no row returns supabase-kt's empty result, which is not
stored. The shape is read from the request supabase-kt builds, before anything is sent; a read
whose request cannot be built (no token to be had) fails as sending it would, and is never
answered from the list.

**The request lambda and schemas.**
