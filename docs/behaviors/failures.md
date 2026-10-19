# Failures and the rejected list

A failure is either the network's or the server's. On the network's, the library serves the cache
or keeps the write queued; on the server's, it hands you supabase-kt's own exception, unchanged. A
queued write the server refuses later, on replay, is thrown at no one: it goes to the rejected
list, which your app shows; one whose row moved on the server meanwhile is a conflict
([Conflicts](#conflicts)). The samples turn every failure a call can throw into their own words in
one place, a branch per kind:

From the samples: [LibraryFailures.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LibraryFailures.kt) · [SampleFailure.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/SampleFailure.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/LibraryFailures.kt:failure-codes"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/SampleFailure.swift:write-failure"

    --8<-- "Data/SampleFailure.swift:failure-codes"
    ```

## How a failure is classified

- `isNetworkOrConnectivityFailure()`, which the library uses itself, is `true` for a timeout or an
  I/O failure, here or in a cause, such as supabase-kt's `HttpRequestException`; never for a
  cancellation, and never for anything carrying an HTTP status. A read falls back to the cache on
  such a failure only, and a reactive read reports a failing refresh in its snapshot instead of
  throwing ([Refresh lifecycle](refresh-lifecycle.md)).
- `httpStatusOrNull()` is the HTTP status the server answered with, read from supabase-kt's
  `RestException` (PostgREST's and Storage's errors), a Ktor `ResponseException` or a
  `SyncFailedException`, here or in a cause.

The library's own failures subclass `OfflineException`. The hierarchy is open, so match the ones
you handle and keep a branch for the rest; everything else is supabase-kt's or the server's
(`PostgrestRestException`, `RestException`, `HttpRequestException`, `TokenExpiredException`, …):

| Exception | When |
| --- | --- |
| `CacheMissException` | A one-shot `CacheOnly` read found nothing it may serve, or any `CacheOnly` read asked for a shape the cache never holds. Nothing was sent. A live `CacheOnly` read of a query that is not cached emits `null` rows, or only the queued writes it admits, instead |
| `MutationNotQueueableException` | A write the queue cannot hold could not be sent for want of a connection. Its `cause` is that failure, so it counts as a connectivity failure |
| `NetworkDisabledException` | A request that must reach the server was made after `disableNetwork()`. It counts as a connectivity failure |
| `SyncFailedException` | Why a sync pass stopped with writes still queued, as `syncStatus.value.lastSyncError` says. It is never thrown; it carries `httpStatus` and `code` only |

## Writes

| Failure | When |
| --- | --- |
| supabase-kt's `PostgrestRestException` | The write was sent at once and the server refused it, with any answer but those that keep it queued (below). It does not stay queued |
| `MutationNotQueueableException` | A write the queue cannot hold (a dry run, a filter it cannot record, another schema, an unknown user) could not be sent for want of a connection. Nothing was queued |
| The connectivity failure itself | An `OnlineOnly` write could not be sent |
| `NetworkDisabledException` | An `OnlineOnly` write was made while network access is off |
| `IllegalArgumentException` | An UPDATE or DELETE named two top-level filters on one column, or a pattern list the server would misread; nothing was sent or queued |

A `QueueIfOffline` write that may not have run is not thrown: it returns `Queued`
([What a write returns](mutations.md#what-a-write-returns)). When you show a refusal, show
PostgREST's own `error` and `code`, or your own words keyed on the code: the exception's `message`
carries the request's URL with its filter values, so keep it out of the screen and out of crash
reports ([Security and privacy](../help/security.md)).

## Replay failures

Which replay failures stop the pass and which move a write to the rejected list is on
[What happens to each write](sync.md#what-happens-to-each-write). A write the server keeps failing
with a `5xx` holds every later write of its user behind it: when it will never go through,
`discardPendingMutation(id)` removes it, unsent, and the writes behind it go.

## The rejected list

It is kept on the device across restarts until each entry is dismissed or retried. Each entry is a
`RejectedMutation`: the table, the operation, the rows' keys, the body, the server's answer as a
`ServerError` (`status`, `code`, `message`, `details`, `hint`), and whether it can be retried.

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [LibraryMappings.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LibraryMappings.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift) · [QueueWording.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Sync/QueueWording.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:rejected-list"

    --8<-- "data/supabase/LibraryMappings.kt:rejected-row"

    --8<-- "data/supabase/OfflineTaskRepository.kt:rejected-actions"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:rejected-list"

    --8<-- "Sync/QueueWording.swift:rejected-row"

    --8<-- "Data/TaskRepository.swift:rejected-actions"
    ```

Retry a write once its cause is fixed (a missing parent row, a policy): it rejoins the queue at the
end under a new id. Postgres puts the offending values in `details` (a duplicate key's value, for
one), so show your own words rather than the raw fields.

**Its bounds.** The newest 100 entries of every user together, within 512 K characters. A body
longer than 16 K characters is not kept, so that entry cannot be retried (`isRetryable` is
`false`); the server's message, details and hint are kept to 4 K characters each. When the list is
full the oldest entries go, and their writes are lost: nothing reports the loss yet.

## Conflicts

A table whose version column you name
([Configuration](../reference/configuration.md#versioncolumns)) has its queued updates and deletes
of one cached row checked when they replay: each is sent only while the server's row still has the
version the device saw, and a row that moved meanwhile is a conflict your resolver settles.
[Conflicts and merging](conflicts.md) walks through it, with a running example.

| The replay finds | An UPDATE | A DELETE |
| --- | --- | --- |
| The row at the version the device saw | Applied; the server's row is cached | Applied |
| No row | Gone: the resolver is asked with `server = null`; by default the write is dropped (`conflictsKeptServerCount`) | Already gone: synced (`alreadyOnServerCount`) |
| A row the rest of the write's filter no longer reaches | Synced, as the unguarded replay's empty answer was | The same |
| The row at that version, still meeting the filter, unchanged | A policy denies it: the rejected list | The same |
| The row moved, already holding the write's values | An earlier send landed: synced (`alreadyOnServerCount`) | — |
| The row moved on other columns only | Resent at the new version, without asking | Any move: a conflict, by default `KeepLocal` (deleted) |
| The row moved, otherwise | A conflict: the resolver, by default `KeepLocal` (resent at the new version) | The same as any move |
| No connection | The pass stops; the write stays queued | The same |

A resolver that answers `Hold`, throws or takes 30 seconds parks the write on
`conflictedMutations()`, for the app to resolve or dismiss; so does a third send that still finds
the row moved.

Everything else keeps the last write wins:

- **A replayed UPSERT**, any write to a table without a version column, a write sent at once, and
  an UPDATE or DELETE that pins no single cached row send what they were given; if a first send
  reached the server but the answer was lost, the replay overwrites what another writer changed in
  between, and an upsert recreates a row deleted elsewhere.
- **A value several devices change** (a counter, a running total) belongs in a server-side
  computation: a queued write replays exactly the value it set, so write the facts that feed it.
- **A policy change** under row-level security can turn writes already queued into refusals.
- **A parent row deleted elsewhere** makes a queued child's insert fail on its foreign key
  (`23503`), and it lands on the rejected list. Your own child queued after its parent is sent
  after it.
- **The same id twice.** A genuine duplicate id is refused (`23505`) and lands on the rejected list
  ([Upserts and ids](mutations.md#upserts-and-ids)).
- **On iOS**, a request that fails as the network path drops can read as never sent, and its
  replay may be refused as a duplicate of itself: a false rejection, never a lost write.

## A missing Storage object

Storage answers a request for a missing object with HTTP 400 and a 404 in its body, which
supabase-kt raises as `NotFoundRestException`, so `httpStatusOrNull()` reads 400: test for the type.
The offline bucket drops its cached copy when the server says so ([Storage caching](storage.md)).
