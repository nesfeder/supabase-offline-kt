# Conflicts and failures

A failure is either the network's or the server's. On the network's, the library serves the cache
or keeps the write queued; on the server's, it hands you supabase-kt's own exception, unchanged. A
queued write the server refuses later, on replay, is thrown at no one: it goes to the rejected
list, which your app shows. The samples turn every failure a call can throw into their own words in
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

A failure that may pass (no connection, a `401`, `408`, `429` or any `5xx`, PostgREST's schema cache
not knowing the table or a column yet, a failure on the device) stops the pass with every
remaining write queued; `syncStatus.value.lastSyncError` says why, and a later pass tries again. A
refusal for good, any other `4xx`, moves the write to the rejected list, and the pass goes on. So
does the tenth schema-cache refusal of one write, each counted at least 30 seconds after the last
(at least four and a half minutes of a missing table or column), and a write the library can no
longer replay exactly as it was made, such as a stored filter it cannot read back, which has no
`serverError` ([What happens to each write](sync.md#what-happens-to-each-write)). A write the
server keeps failing with a `5xx` holds every later write of its user behind it: when it will
never go through, `discardPendingMutation(id)` removes it, unsent, and the writes behind it go.

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
full the oldest entries go, and their writes are lost: nothing reports the loss yet. Only a write
that waited in the queue can end up here; one sent at once that the server refuses throws to its
caller instead, as supabase-kt's verbs do.

## Conflicts

The last write wins: the library does not merge or resolve conflicting writes.

- **A replayed UPSERT, UPDATE or DELETE** sends what it was given; if its first send reached the
  server but the answer was lost, the replay overwrites what another writer changed in between.
- **A value several devices change** (a counter, a running total) belongs in a server-side
  computation: a queued write replays exactly the value it set, so write the facts that feed it.
- **A policy change** under row-level security can turn writes already queued into refusals.
- **A parent row deleted elsewhere** makes a queued child's insert fail on its foreign key
  (`23503`), and it lands on the rejected list. Your own child queued after its parent is sent
  after it.
- **The same id twice.** A keyed INSERT whose earlier send may have reached the server replays as
  an upsert that ignores duplicates; a genuine duplicate id is refused (`23505`) and lands on the
  rejected list. A row whose key the server makes is inserted twice when the answer to its first
  send was lost: make ids on the device.
- **On iOS**, a request that fails as the network path drops can read as never sent, and its
  replay may be refused as a duplicate of itself: a false rejection, never a lost write.

## A missing Storage object

Storage answers a request for a missing object with HTTP 400 and a 404 in its body, which
supabase-kt raises as `NotFoundRestException`, so `httpStatusOrNull()` reads 400: test for the type.
The offline bucket drops its cached copy when the server says so ([Storage caching](storage.md)).

## Pinned by

`HeadSendTest.a_refusal_at_the_head_is_thrown_and_the_write_leaves_the_queue`,
`HeadSendTest.a_503_at_the_head_leaves_the_write_queued`,
`MutationQueueTransientFailureTest.a_write_that_keeps_meeting_503_is_never_dropped`,
`MutationQueueTransientFailureTest.a_write_that_keeps_meeting_connectivity_failures_is_never_dropped`,
`RejectedMutationListTest.a_permanent_rejection_keeps_the_write_and_the_servers_reason`,
`RejectedMutationListTest.the_list_keeps_the_newest_hundred`,
`RejectedMutationListTest.a_filter_that_can_no_longer_be_read_is_refused_for_that_reason`,
`SchemaCacheMissTest.a_table_still_missing_after_ten_spaced_misses_is_rejected`,
`DiscardPendingMutationTest.discarding_the_write_the_server_keeps_failing_lets_the_rest_through`,
`MutationQueueIdempotentReplayTest.a_genuine_duplicate_id_is_refused_and_lands_on_the_rejected_list`,
`MutationQueueIdempotentReplayTest.an_insert_whose_key_the_server_generates_is_inserted_twice_when_its_response_was_lost`,
`FailureClassificationTest.a_write_that_could_not_be_queued_or_sent_is_a_connectivity_failure`.
