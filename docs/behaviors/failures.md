# Conflicts and failures

A failure is either the network's or the server's. On the network's, the library serves the cache
or keeps the write queued; on the server's, it hands you supabase-kt's own exception, unchanged. A
queued write the server refuses later, on replay, is not thrown at anyone: it goes to the rejected
list, which your app shows.

```kotlin
--8<-- "QueueExamples.kt:write-failure"
```

## How a failure is classified

Two extensions on `Throwable` tell them apart, and the library uses the first itself:

- `isNetworkOrConnectivityFailure()` is `true` for a timeout or an I/O failure, here or in a
  cause, such as supabase-kt's `HttpRequestException`. Never for a cancellation, and never for
  anything carrying an HTTP status.
- `httpStatusOrNull()` is the HTTP status the server answered with, read from supabase-kt's
  `RestException` (PostgREST's and Storage's errors), a Ktor `ResponseException` or a
  `SyncFailedException`, here or in a cause.

The library's own failures are subclasses of `OfflineException`. The hierarchy is open, so match
the ones you handle and keep a branch for the rest:

| Exception | When |
| --- | --- |
| `CacheMissException` | A one-shot `CacheOnly` read found nothing it may serve, or any `CacheOnly` read asked for a shape the cache never holds. Nothing was sent. A live `CacheOnly` read of a query that is not cached emits `null` rows, or only the queued writes it admits, instead |
| `MutationNotQueueableException` | A write the queue cannot hold could not be sent for want of a connection. Its `cause` is that failure, so it counts as a connectivity failure |
| `NetworkDisabledException` | A request that must reach the server was made after `disableNetwork()`. It counts as a connectivity failure |
| `SyncFailedException` | Why a sync pass stopped with writes still queued, as `syncStatus.value.lastSyncError` says. It is never thrown; it carries `httpStatus` and `code` only |

Everything else is supabase-kt's or the server's: `PostgrestRestException`, `RestException`,
`HttpRequestException`, `TokenExpiredException` and the rest.

## Reads

A read falls back to the cache on a connectivity failure only, as its strategy allows. A server's
error is thrown, with the query cached or not, so the cache never hides a refused request. A
reactive read reports a failing refresh in its snapshot instead of throwing it; see
[Refresh lifecycle](refresh-lifecycle.md).

## Writes

What a write can throw:

| Failure | When |
| --- | --- |
| supabase-kt's `PostgrestRestException` | The write was sent at once and the server refused it, with any answer but those that keep it queued (below). It does not stay queued |
| `MutationNotQueueableException` | A write the queue cannot hold (a dry run, a filter it cannot record, another schema, an unknown user) could not be sent for want of a connection. Nothing was queued |
| The connectivity failure itself | An `OnlineOnly` write could not be sent |
| `NetworkDisabledException` | An `OnlineOnly` write was made while network access is off |
| `IllegalArgumentException` | An UPDATE or DELETE named two top-level filters on one column, or a pattern list the server would misread; nothing was sent or queued |

A `QueueIfOffline` write that may not have run (no connection, no answer, a `503` or `520`, an
expired token that could not be refreshed, a table or column PostgREST's schema cache does not
know yet) is not thrown: it returns `Queued`.

When you show a refusal, show PostgREST's own `error` and `code`, or your own words keyed on the
code. The exception's `message` also carries the request's URL, with its filter values; keep it
out of the screen and out of crash reports. See [Security and privacy](../help/security.md).

## Replay failures

A replay pass sends the user's queued writes one at a time, in order. Two kinds of failure end
differently:

- **A failure that may pass** stops the pass with every remaining write queued, so no write
  overtakes an earlier one: no connection, a `401`, `408`, `429` or any `5xx`, PostgREST's schema
  cache not knowing the table or a column yet, or a failure on the device. It is not thrown;
  `syncStatus.value.lastSyncError` says what it was, and a later pass tries again.
- **A refusal for good**, any other `4xx`, moves the write to the rejected list, and the pass
  goes on with the next one.

Two more ways onto the rejected list: the tenth schema-cache refusal of one write, each counted at
least 30 seconds after the last (at least four and a half minutes of a missing table or column),
and a write the library can no longer replay exactly as it was made, such as a stored filter it
cannot read back. The second has no `serverError`.

A write the server keeps failing with a `5xx` holds every later write of its user behind it. When
it will never go through, `discardPendingMutation(id)` removes it, unsent, and the writes behind it
are sent.

## The rejected list

The rejected list is kept on the device across restarts until each entry is dismissed or retried.
Each entry is a `RejectedMutation`: the table, the operation, the rows' keys, the body, the
server's answer as a `ServerError` (`status`, `code`, `message`, `details`, `hint`), and whether it
can be retried.

```kotlin
--8<-- "QueueExamples.kt:rejected-list"
```

```kotlin
--8<-- "QueueExamples.kt:rejected-actions"
```

Retry a write once its cause is fixed, such as a missing parent row or a policy: it rejoins the
queue at the end under a new id. Postgres puts the offending values in `details` (a duplicate key's
value, for one); show your own words rather than the raw fields.

**The list's bounds.** It holds the newest 100 entries of every user together, within 512 K
characters. A body longer than 16 K characters is not kept, so that entry cannot be retried
(`isRetryable` is `false`); the server's message, details and hint are kept to 4 K characters
each. When the list is full the oldest entries go, and their writes are lost: nothing reports the
loss yet.

Only a write that waited in the queue can end up here. A write sent at once that the server
refuses throws to its caller instead, as supabase-kt's verbs do, and is not listed.

## Conflicts

The last write wins. The library does not merge or resolve conflicting writes:

- **A replayed UPSERT, UPDATE or DELETE** sends what it was given. If its first send reached the
  server but the answer was lost, the replay overwrites anything another writer changed in
  between.
- **A value several devices change** (a counter, a running total) belongs in a server-side
  computation. A queued write replays exactly the value it set, so write the facts that feed the
  value, not the value.
- **A policy change** under row-level security can turn writes already queued into refusals.
- **A parent row deleted elsewhere** makes a queued child's insert fail on its foreign key
  (`23503`), and it lands on the rejected list. The queue keeps your own writes in order, so a
  child you queued after its parent is sent after it.
- **The same id twice.** A keyed INSERT whose earlier send may have reached the server replays as
  an upsert that ignores duplicates, so it is not inserted twice; a genuine duplicate id is
  refused (`23505`) and lands on the rejected list. A row whose key the server makes is inserted
  twice when the answer to its first send was lost: make ids on the device.
- **On iOS**, a request that fails as the network path drops can read as never sent, and its
  replay may be refused as a duplicate of itself: a false rejection, never a lost write.

## A missing Storage object

Storage answers a request for a missing object with HTTP 400 and a 404 in its body, which
supabase-kt raises as `NotFoundRestException`: `httpStatusOrNull()` reads 400 for it. Test for the
type. The offline bucket drops its cached copy when the server says so; see
[Storage caching](storage.md).

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
