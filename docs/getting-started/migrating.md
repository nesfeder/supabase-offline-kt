# Coming from supabase-kt

A call written against `supabase.from(table)` compiles against `supabase.offline.from(table)` with
only the receiver changed and the accessor imported. Writes return a `MutationOutcome` instead of
the server's result, and a few call shapes need an edit, listed below.

## The swap

=== "supabase-kt"

    ```kotlin
    --8<-- "GettingStartedExamples.kt:before"
    ```

=== "supabase-offline-kt"

    ```kotlin
    --8<-- "GettingStartedExamples.kt:after"
    ```

Import `io.github.nesfeder.supabase.offline.offline`. Storage works the same way:
`supabase.storage.from(bucket)` becomes `supabase.offline.storage.from(bucket)`.

## What stays the same

- **The verbs and their overloads.** `select`, `selectAsFlow`, `insert`, `upsert`, `update` and
  `delete`, with JSON bodies, typed values and lists, and the update DSL (`set`, `setTo`,
  `setToNull`). Typed values are encoded by your PostgREST plugin's serializer, as supabase-kt
  encodes them.
- **The request DSL.** Filters, `order`, `range`, `limit`, `select()` on a write, `onConflict`,
  `ignoreDuplicates` and `defaultToNull`, and `count`.
- **The schema.** `from(schema, table)` and the request's `schema` work as in supabase-kt.
- **Storage.** Every `BucketApi` operation is there under its name; downloads are cached.
- **The errors.** When the server refuses a request, or a request cannot be sent and the library
  does not answer it from the cache or the queue, you get supabase-kt's own exception
  (`PostgrestRestException`, `RestException`, `HttpRequestException`) unchanged. A write sent at
  once that the server refuses throws, as it does in supabase-kt. The library adds three of its
  own: `MutationNotQueueableException`, for a write the queue cannot hold that cannot be sent (its
  `cause` is supabase-kt's connectivity failure); `NetworkDisabledException`, while network access
  is off; and `IllegalArgumentException`, for two top-level filters on one column, a pattern
  list the server would misread, or an `in` element or group value holding `"` or `\` and none of
  `,.:()`, which supabase-kt would send escaped but unquoted
  ([Supported features](../reference/supported-features.md#postgrest)). Test for being offline with
  `isNetworkOrConnectivityFailure()`, not by catching `HttpRequestException`.

The offline choices are named parameters with defaults, placed before the trailing lambda:
`readStrategy` on reads, `mutationStrategy` on writes. You add them only where you want something
other than the default.

## What a write returns

A write returns `MutationOutcome`: `Applied(result)` when the server applied it,
`Queued(pendingMutation)` when it waits in the mutation queue, with no answer from the server yet.
Ignore it (a screen built on a live read shows the write either way, at once), take the result if
there is one with `resultOrNull()`, or match both cases with an exhaustive `when`.

## Call shapes that need an edit

supabase-kt's verbs are `inline`; these are not, because the library keeps the request lambda and
runs it again: to learn the read's shape, to queue a write and to replay it. That, and the return
type, make these call shapes differ:

| Call | What to change |
| --- | --- |
| A write whose result is read, as in `insert(row) { select() }.decodeSingle<Row>()` | Read it through `resultOrNull()`, and decide what to show while the write is queued |
| A suspending call, or an early `return`, inside a request lambda | Make the call, or return, before the verb; use its result inside the lambda |
| A request lambda inside the parentheses, as in `insert(row, { select() })` | The second parameter is `mutationStrategy` (the first, for `delete`): name the lambda `request = { … }`, or move it after the parentheses |
| Realtime's `selectAsFlow(…)` on a table | The builder's own `selectAsFlow` is the offline one, and it follows the server once the table is named in `realtime { }`; keep broadcast and presence calls on supabase-kt's channels |
| The typed `rpc(function, parameters)` | Import `io.github.nesfeder.supabase.offline.rpc` in place of `io.github.jan.supabase.postgrest.rpc` |
| Bucket management (`createBucket`, `listBuckets`, `emptyBucket` and the rest) | Keep it on `supabase.storage` |

A write whose result is read:

=== "supabase-kt"

    ```kotlin
    --8<-- "GettingStartedExamples.kt:write-result-before"
    ```

=== "supabase-offline-kt"

    ```kotlin
    --8<-- "GettingStartedExamples.kt:write-result-after"
    ```

A suspending call inside a request lambda:

```kotlin
--8<-- "GettingStartedExamples.kt:suspend-in-lambda"
```

A request lambda inside the parentheses:

```kotlin
--8<-- "GettingStartedExamples.kt:named-request"
```

The typed `rpc`, with the library's `rpc` imported:

```kotlin
--8<-- "GettingStartedExamples.kt:typed-rpc"
```

**The request lambda must build the same request every time.** A read runs it once to learn its
shape and once for each request it sends; a write that may be queued runs it up to three times.
Keep side effects out of it, and read no clock or counter inside it.

## Two filters on one column

supabase-kt sends only the first value of each top-level query parameter, so
`filter { gte("age", 1); lte("age", 10) }` sends `age=gte.1` and nothing else: every row from 1
up. The library refuses such a call with an `IllegalArgumentException`, before anything is sent
or queued, for reads, updates and deletes. A read would show the wrong rows; a queued delete would
replay later and remove more than you asked. Write the conditions as one `and { }` group, which
supabase-kt sends whole:

From the samples: [TaskQueries.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/TaskQueries.kt) · [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskQuery.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskQuery.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/TaskQueries.kt:same-column"

    --8<-- "data/supabase/OfflineTaskRepository.kt:two-filters-refused"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskQuery.swift:same-column"
    ```

    Swift sends two conditions on one column together, in one `and` group, so nothing is refused.

Two cases the library cannot see, because supabase-kt keeps one value by replacing the first
rather than adding a second: two top-level `or { }` groups (only the last is sent), and a text
search on a column after a filter on the same column (only the text search is sent). Combine
those into one group too.

## What supabase-kt 3.6 does not have

The library is built against supabase-kt 3.6 ([why](../project/compatibility.md#supabase-kt)),
which lacks a few things 3.8 has:

- **`dryRun()`.** Add the header yourself, `headers.append("Prefer", "tx=rollback")` in the
  request block (supabase-kt marks it experimental). The library never queues such a write, and on
  a hosted Supabase project a dry run commits
  ([Writes and the offline queue](../behaviors/mutations.md#dry-runs)).
- **`maybeSingle()`**, the `isdistinct` filter operator and `purgeCache`.
- **Storage object names with `%`, `?` or `#`**, which supabase-kt 3.6 puts in the URL as written,
  so a `?` or `#` can reach a different object. The library never caches such a name, and
  `ensureCachedFile` refuses it ([Storage caching](../behaviors/storage.md#paths-that-are-never-cached)).

## What is never cached or queued

The library caches PostgREST lists and Storage downloads, and queues PostgREST writes; everything
else goes to the server as supabase-kt sends it, and offline fails as there. That is `rpc` (a
function may change data, so it is neither cached nor queued), Auth and Functions, Realtime's
broadcast and presence (the tables `realtime { }` names are kept current by the plugin, and another
channel's records go into the cache with [`applyServerRecord`](../behaviors/realtime.md)),
Storage writes, and reads of another shape (a `count`, `head`, `csv()`, `geojson()`,
`stripNulls()`, `explain()`; `single()` is cached as its list, one row long). The samples' two
database functions say so offline: Mark all done takes no parameters, the Sync tab's Server
section takes them typed and as JSON, and a function's changes reach the device's lists as another
device's do, through Realtime or the next read:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository+Server.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+Server.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:rpc-no-params"

    --8<-- "data/supabase/OfflineTaskRepository.kt:rpc-typed"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository+Server.swift:rpc-no-params"

    --8<-- "Data/TaskRepository+Server.swift:rpc-typed"
    ```

The full table is [Supported features](../reference/supported-features.md).

## Behavior to expect

`select` falls back by default: `NetworkFirst` reads as supabase-kt does online and serves the
cached rows offline instead of throwing. A write made while an earlier one of the same user waits is
queued behind it, online too, returns `Queued`, and is sent a moment later. A queued write the
server refuses on replay is not thrown but goes to the rejected list for your app to show ([Failures
and the rejected list](../behaviors/failures.md)). The client holds a database: one client per
process, and `clearLocalData()` on sign-out.

supabase-kt sends a write when you call it, and the last write to reach the server wins. Here a
write made offline reaches the server later, so name a version column for the tables where that
matters ([Conflicts and merging](../behaviors/conflicts.md)): a queued update or delete of one
cached row then replays with one more filter, `updated_at=eq.<the version the device saw>`, and
`select=` the key, the version and the columns it sets, with `Prefer: return=representation`. A
replayed request therefore needs SELECT on those columns, and its row must pass the table's SELECT
policy, as a supabase-kt write with `select()` does. The request sent at once, while nothing waits
ahead of it, is still exactly the one you built.

## Checklist

1. Add the dependency and install the plugin last ([Project setup](setup.md)).
2. Import `io.github.nesfeder.supabase.offline.offline`, and swap `supabase.from` for
   `supabase.offline.from` where a read or write should work offline.
3. Fix the call shapes above; the compiler finds all but two filters on one column.
4. Make new rows' ids on the device.
5. Call `clearLocalData()` when a user signs out.
6. Show the sync status and the rejected list somewhere in the app.
7. For a table several devices edit, name its version column, and show the conflicted list too.
