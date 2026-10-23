# Reactive reads

`selectAsFlow` reads a query as it changes: it emits the rows, with the user's queued writes laid
over them, and again whenever a write, a refresh or a server record changes them. It needs no
Realtime channel: it follows the device's cache, and refreshes it from the server as its strategy
says.

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift) · [TasksModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Tasks/TasksModel.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:first-observed-read"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:first-observed-read"

    --8<-- "Tasks/TasksModel.swift:live-read"
    ```

## The three strategies

The sample's list takes the first two from its **Prefer fresh data** switch, and a task's photos
take the third while Simulate offline is on:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift) · [TaskDetailModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Detail/TaskDetailModel.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:reactive-strategies"

    --8<-- "data/supabase/OfflineTaskRepository.kt:cache-only-photos"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:reactive-strategies"

    --8<-- "Detail/TaskDetailModel.swift:cache-only-photos"
    ```

- **`CacheFirstThenNetwork`**, the default, emits the cached rows at once and asks the server in
  parallel. A refresh that fails leaves the rows on screen and is tried again for as long as the
  flow is collected. Use it for almost every screen.
- **`NetworkFirstThenCache`** asks the server first, then emits the cached rows as they change.
  Offline with the query cached, it emits them at once and keeps asking until the server answers;
  with nothing cached, or when the server answers with an error, the flow fails.
- **`CacheOnly`** never sends a request. For a query that is not cached it emits `null` rows, or
  only the queued writes the query admits, rather than failing.

## What each emission says

Each emission is a `QuerySnapshot`, and its fields are true together:

| Field | Meaning |
| --- | --- |
| `result` | The rows, as supabase-kt's `PostgrestResult`, with the user's queued writes applied. `null` while nothing is known for the query: nothing cached and no queued write it admits. A body of `[]` means the server returned no rows |
| `isFromCache` | Whether the rows are the device's copy rather than an answer this read had from the server: `true` until the read's refresh succeeds, always under `CacheOnly`, and again once a write marks them stale |
| `fetchedAt` | When the server sent the rows, by the device's clock; never the time of a local change applied to them |
| `hasPendingMutations` | Whether a queued write added, changed or removed a row shown |
| `refresh` | How the refresh behind the read stands; see [Refresh lifecycle](refresh-lifecycle.md) |

`result == null` is a loading state; an empty body is an empty list. Next to rows already on
screen, `refresh` says whether to show a note ([Refresh lifecycle](refresh-lifecycle.md) has the
sample's mapping). To mark each row whose change has not reached the server yet, combine the read
with `pendingMutations(table)` and its rows' keys:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TasksModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Tasks/TasksModel.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:pending-marks"
    ```

=== "iOS"

    ```swift
    --8<-- "Tasks/TasksModel.swift:pending-marks"
    ```

## Filters, search and pages

A live read takes supabase-kt's whole request DSL, and each request is cached on its own. The
sample's chips, search and Recent chip are filters the server applies; a row's photo count reads
the photos of the tasks shown with `in`; the Photos grid reads a page more each time:

From the samples: [TaskQueries.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/TaskQueries.kt) · [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskQuery.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskQuery.swift) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/TaskQueries.kt:filter-chips"

    --8<-- "data/supabase/TaskQueries.kt:search"

    --8<-- "data/supabase/OfflineTaskRepository.kt:photo-rows-in"

    --8<-- "data/supabase/OfflineTaskRepository.kt:photos-page"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskQuery.swift:filter-chips"

    --8<-- "Data/TaskQuery.swift:search"

    --8<-- "Data/TaskRepository.swift:photo-rows-in"

    --8<-- "Data/TaskRepository.swift:photos-page"
    ```

A queued change to `done` moves a task between Open and Done at once, but a task made offline shows
in every search until the list is fetched again ([Optimistic overlay](overlay.md)).

## When it fetches again

A `CacheFirstThenNetwork` read refreshes when it starts and tries again until a refresh succeeds.
After that it fetches again only when local data is cleared or the cache passes to another user,
when a write the cache could not follow exactly marks the list stale, and when a new collection
starts after every earlier collector has gone. It does not poll: to pick up changes made
elsewhere, read the same query with `readStrategy = ReadStrategy.NetworkOnly` (a pull to refresh),
whose answer replaces the cached list for every live reader of it, or apply Realtime records
([Applying Realtime events](realtime.md)).

## Sharing and closing

Collectors of one `CacheFirstThenNetwork` query share one subscription to the cache and one
refresh. A failure the read cannot recover from ends the flow after the values before it; a
refresh that fails is not one, it is reported in `refresh`. When the client closes, a collection
still running completes: `first()` on one that has not emitted yet throws
`NoSuchElementException` (`firstOrNull()` returns `null`), and a flow collected after `close()`
fails with `OfflineClientClosedException`.

## Behavior matrix

Facts about the engine, one row per condition; the conditions are those of the
[one-shot matrix](read-strategies.md#behavior-matrix).

| Condition | `CacheFirstThenNetwork` | `NetworkFirstThenCache` | `CacheOnly` |
|---|---|---|---|
| **Online** | The cached payload with the overlay at once (`null` when nothing is cached), `Refreshing`; then the server's rows, `Idle`. Collectors of one query share one refresh. | The server's rows, `Idle`; then the cache as it changes. | The cached payload with the overlay, or `null`; `Idle`; never a request. |
| **Offline, warm** | The cached payload with the overlay. The refresh retries for the life of the collection (300 ms, 900 ms, 2.4 s, 5 s, 15 s, then every 60 s), `Stalled` after four failures, about 3.6 s: each attempt fails at once, without supabase-kt's retries. The flow stays live. | The cached payload with the overlay at once, `Stalled`: the cache is not confirmed. It fetches again on the schedule `CacheFirstThenNetwork` retries on, and is `Idle` once the server answers; the flow stays live. | The cached payload with the overlay. |
| **Offline, cold** | `null`, `Refreshing`, then `Stalled`; the flow stays live and settles when the server answers. | The flow fails with the connectivity failure. | `null`, or the queued rows the query admits. |
| **Network known down** (the monitor says offline, or `disableNetwork()`) | The cached payload, `Paused`, not `Stalled`: waiting for the network, not failing. While access is off nothing is sent; when the network comes back the refresh runs at once and is `Idle`. | The cached payload, from the cache, `Paused`. | As offline. |
| **Session restoring** | The cached payload. Nothing a request returns is written, and the refresh does not settle (it reads `Refreshing`), until the session has. Settled as the stored user: the user's answer. Settled signed out: a stored user's rows show while the session restores and are dropped when it settles signed out; with no stored session they are never shown. It then settles on the anonymous answer. | Nothing until the session settles, as a one-shot read waits; then the user's rows, `Idle`. A session that never settles: the cache, still `Refreshing`, since waiting for the session is not a failure. | The cached payload, no request. |
| **Session refresh failed** (the stored session expired and supabase-kt could not refresh it; it tries again, every ten seconds by default) | As **Session restoring**, until the refresh lands. | As **Session restoring**: nothing until the refresh lands, as a one-shot read waits; a refresh that does not land within the wait: the cache, still `Refreshing`. | The cached payload, no request. |

A server's own error (4xx or 5xx) is a refresh failure for `CacheFirstThenNetwork`, and fails a
`NetworkFirstThenCache` flow even with a warm cache.

A read of a shape the cache cannot hold (`single()`, a `count`, `csv()`, …; see the
[one-shot matrix](read-strategies.md#behavior-matrix)) emits the network's answer once, `Idle`,
and completes; offline it fails. While the session restores, or after its refresh failed, it waits
for the session first, as a one-shot read does. Under `CacheOnly` it fails at once with
`CacheMissException` and sends nothing.

A collector slow to take each value is not handed every list in between: under
`CacheFirstThenNetwork` it gets the latest, under the other two a few superseded lists at most. It
ends on the latest, and never holds back another collector of the same query.

A read live while the session settles into another identity drops what it showed for the one
before and ends on the new identity's rows alone.

Each snapshot field is true of its own emission, as the table above says; a refresh that keeps
failing while the network is believed up is `Stalled`.
