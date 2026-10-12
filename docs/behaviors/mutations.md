# Writes and the offline queue

By default every `insert`, `upsert`, `update` and `delete` is saved to the mutation queue first,
and sent at once when nothing of the same user waits ahead of it; otherwise it waits and is sent
by a later pass, in the order it was made. A write returns a `MutationOutcome`, `Applied` with the
server's answer or `Queued`, and every cached read shows it at once either way.

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:first-write"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:first-write"
    ```

The verbs are supabase-kt's, with its overloads and request DSL. The sample's autosave and done
switch update with a typed value and a JSON body (Move to another task, on
[Storage caching](storage.md#writes), uses the update DSL); Duplicate task upserts one row and asks for it back with `select()`, read with `resultOrNull()`
unless the write `isQueued`; a delete names its rows by filter; and several rows go in one write,
typed or as a JSON body, queued as one when offline. The starter list's ids are the same on every
import, so `ignoreDuplicates` makes a second import change nothing:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:typed-update"

    --8<-- "data/supabase/OfflineTaskRepository.kt:body-update"

    --8<-- "data/supabase/OfflineTaskRepository.kt:duplicate-task"

    --8<-- "data/supabase/OfflineTaskRepository.kt:delete-task"

    --8<-- "data/supabase/OfflineTaskRepository.kt:insert-list"

    --8<-- "data/supabase/OfflineTaskRepository.kt:starter-list"

    --8<-- "data/supabase/OfflineTaskRepository.kt:json-insert"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:typed-update"

    --8<-- "Data/TaskRepository.swift:body-update"

    --8<-- "Data/TaskRepository.swift:duplicate-task"

    --8<-- "Data/TaskRepository.swift:delete-task"

    --8<-- "Data/TaskRepository.swift:insert-list"

    --8<-- "Data/TaskRepository.swift:starter-list"

    --8<-- "Data/TaskRepository.swift:json-insert"
    ```

## The two strategies

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:mutation-strategies"

    --8<-- "data/supabase/OfflineTaskRepository.kt:delete-my-data"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:mutation-strategies"

    --8<-- "Data/TaskRepository.swift:delete-my-data"
    ```

- **`QueueIfOffline`**, the default, saves the write to the queue, so it keeps its place among the
  user's writes in every table, and keeps it there until the server has accepted or refused it.
- **`OnlineOnly`** sends the write now and throws when it cannot. It is never queued and never
  patched into the cache, so it can reach the server before writes still queued, to the same rows
  or to a parent row it depends on. Use it for a write that makes no sense later, such as deleting
  an account's data.

## What a write returns

`MutationOutcome` is a closed set, so a `when` over it stays exhaustive: `Applied(result)` holds
the server's answer as supabase-kt returns it, and `Queued(pendingMutation)` the queued write,
whose `id` identifies it from then on, on the rejected list too. `isQueued` says which, and
`resultOrNull()` is the `Applied` result or `null`. A `QueueIfOffline` write returns:

| When it is made | Outcome |
| --- | --- |
| Nothing of the user waits ahead of it, the session is settled, and the server accepts it | `Applied`, and the cached lists are patched with the rows the server returned, or, when it asked for none, with the rows sent |
| Something of the user waits ahead of it, in any table, or the session is still restoring | `Queued`, and a pass is started to send it |
| Network access is off (`disableNetwork()`) | `Queued`; nothing is sent until access is back on |
| Sent at once, and it may not have run: no connection or no answer, a `503` or `520`, an expired token that could not be refreshed, or PostgREST's schema cache not knowing the table or a column yet | `Queued`. A later pass sends it: when the network comes back, the session settles, another write is made or the app syncs |
| Sent at once, and the server refused it with any other answer | The server's exception is thrown, as `supabase.from(…)` would, and the write leaves the queue; it never reaches the rejected list, which holds only refusals found on replay ([Conflicts and failures](failures.md)) |
| The caller is canceled while the write is on the wire | It stays queued, since the server may have it, and a pass sends it |

## Writes the queue cannot hold

These are sent at once instead, as `OnlineOnly` writes are, except that a success still updates
the cache. One that cannot be sent for want of a connection throws
`MutationNotQueueableException`, whose cause is the connectivity failure and whose message names
the kind:

- an UPDATE or DELETE with no filter, or one the queue cannot record exactly: an `or { }` or
  `and { }` group, a negated filter, a filter on an embedded table's column, or a `limit`, `order`
  or `offset` (a queued UPDATE or DELETE carries filters on the table's own columns, joined as AND,
  with any of supabase-kt's operators);
- a dry run (below);
- a write to a schema other than the client's default (below; `OnlineOnly` sends it as any write);
- a write whose user cannot be read from the stored session;
- a write that was on its way into the queue while local data was cleared.

## Two filters on one column

An update or a delete with two top-level filters on one column throws `IllegalArgumentException`
under either strategy, online or offline, before anything is sent or queued: supabase-kt would send
only the first, and the write would reach more rows than it names, now or on replay
([Coming from supabase-kt](../getting-started/migrating.md#two-filters-on-one-column)). One
`and { }` group is the fix, and a group is a filter the queue cannot record, so such a write is
sent at once: Try it makes one offline to show its `MutationNotQueueableException`.

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:same-column-write"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:same-column-write"
    ```

    Swift sends two conditions on one column together, in one `and` group, so nothing is refused.
    The group is a filter the queue cannot record, so this delete is sent at once, and offline it
    fails with `mutationNotQueueable`.

## Dry runs

A dry run (`Prefer: tx=rollback`: supabase-kt 3.8's `dryRun()`, or the header added by hand on
3.6) is never queued, since a replay would run it for real: it is sent at once, and offline throws
`MutationNotQueueableException`. PostgREST rolls it back only when the project lets a request
choose (`db-tx-end` set to `commit-allow-override` or `rollback-allow-override`), which a hosted
Supabase project does not by default, so there **a dry run commits**: the library reads
`Preference-Applied`, applies such a write like any other, patches its rows into the cache and
logs a warning once per client.

```kotlin
--8<-- "QueueExamples.kt:dry-run"
```

## Upserts and ids

A queued write keeps `onConflict`, `ignoreDuplicates` and `defaultToNull` for its replay. The cache
merges an upsert by its `onConflict` columns or the table's key (`id` unless configured), so on a
table without an `id` column name the conflict columns; one that ignores duplicates shows offline
as though it landed ([Optimistic overlay](overlay.md#limits)).

**Make ids on the device**: such a row can be shown, opened and edited before it reaches the
server, and is never inserted twice. A keyed INSERT whose earlier send may have reached the server
is replayed as an upsert that ignores duplicates, and `SyncReport.alreadyOnServerCount` counts
such rows. An INSERT whose key the server makes is inserted twice when the answer to its first send
was lost; it can show twice in a read of its table answered while it is on the wire and, unless it
asks for its rows back (`select()`), in that query's cached rows, across restarts, until they are
fetched again. An UPSERT, an UPDATE and a DELETE replay as sent: idempotent, but a replay after a
lost answer overwrites what another writer changed in between
([Conflicts and failures](failures.md#conflicts)). A queued row carries only the columns it wrote
until the list is fetched again ([Optimistic overlay](overlay.md#tips)).

## Another schema and the request lambda

The queue replays to the client's default schema only, so a `QueueIfOffline` write to another
schema is sent at once and does not wait for writes queued to the default one; offline it throws a
`MutationNotQueueableException` naming the schema, and online it marks the cached queries of that
schema's table stale. Name the schema with `from(schema, table)` or the request's `schema`: the
engine does not read a raw `Content-Profile` header.
<small>`WritesToAnotherSchemaTest.online_it_is_sent_to_that_schema`,
`WritesToAnotherSchemaTest.offline_it_fails_naming_the_schema_and_nothing_is_queued`,
`WritesToAnotherSchemaTest.it_does_not_wait_for_writes_queued_to_the_default_schema`,
`WritesToAnotherSchemaTest.the_cached_queries_of_that_schema_are_fetched_again`,
`NonDefaultSchemaTest.an_online_only_write_to_another_schema_is_sent_to_it`</small>

A write runs its `request` lambda up to three times (to read its schema, filter and `select()`, to
tell a dry run, and to send it; an `OnlineOnly` write, once), so it must configure the same request
every time: no side effects, and no clock or counter read inside it.
<small>`RequestLambdaRunsTest.an_update_sent_at_once_runs_the_request_three_times`,
`RequestLambdaRunsTest.a_write_queued_offline_runs_the_request_three_times`,
`RequestLambdaRunsTest.an_online_only_write_runs_the_request_once`</small>

## Seeing and dropping queued writes

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [LibraryMappings.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LibraryMappings.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift) · [QueueWording.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Sync/QueueWording.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:pending"

    --8<-- "data/supabase/OfflineTaskRepository.kt:discard"

    --8<-- "data/supabase/LibraryMappings.kt:waiting-change"

    --8<-- "data/supabase/OfflineTaskRepository.kt:pending-count"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:pending"

    --8<-- "Data/TaskRepository.swift:discard"

    --8<-- "Sync/QueueWording.swift:waiting-change"

    --8<-- "Data/TaskRepository.swift:pending-count"
    ```

`pendingMutations(table)`, `pendingMutationCount(table)` and `pendingMutationCountFlow(table)` show
the current user's queued writes, of one table or of all, in the order they will be sent: each a
`PendingMutation` with its `id`, `table`, `operation`, `createdAt`, the keys of the rows it touches
(`rowKeys`) and its `body`. `discardPendingMutation(id)` removes a write without sending it:
**destructive**, the write is lost and its rows show again as the server last sent them. It is the
way out of a write the server keeps failing, which holds every later write of the user behind it;
a write on the wire is discarded once that send ends, if it is still queued. The queue is never
evicted and has no size bound. Sending it is on [Replaying the queue](sync.md); refused writes are
on [Conflicts and failures](failures.md#the-rejected-list).

## Behavior matrix

Facts about the engine, one row per condition; the conditions are those of the
[one-shot read matrix](read-strategies.md#behavior-matrix), where warm and cold say whether the
queries a write touches are cached. Each cell names the test that pins it.

| Condition | `OnlineOnly` | `QueueIfOffline` (default) |
|---|---|---|
| **Online** | Sent at once, never behind the queue: `Applied`. The cached queries are not patched.<br><small>`OnlineOnlyWriteTest.an_online_only_write_leaves_the_cached_queries_as_they_were`, `HeadSendTest.an_online_only_write_does_not_wait_behind_the_queue`</small> | Recorded in the queue, then sent at once when it heads its user's queue: `Applied`, and patched into every cached query it touches and every live query it now matches. Rows the server returned are used: an INSERT or UPSERT keeps the columns it wrote beside them; an UPDATE that asked for rows back changes those rows alone, and none when it got none. A write whose filter the cache cannot evaluate marks the lists it may reach stale, and their live readers fetch again. Behind pending writes: `Queued`, and a pass the engine starts sends it. A server's refusal is thrown and the write leaves the queue. These leave it `Queued` instead: a 503 or 520; PostgREST's schema cache not knowing the table or a column yet (`PGRST205`, `PGRST204`), until the tenth such refusal, which rejects it; and a session token the Auth server will not refresh, since the write was never sent.<br><small>`OnlineWriteJoinsQueueTest.an_online_write_with_nothing_pending_is_applied_at_once_and_leaves_no_queue_row`, `ListMembershipTest.an_online_insert_into_list_b_lands_in_list_b_only`, `EmptyCacheReactivityTest.live_cache_less_subscriber_sees_a_materialized_online_upsert`, `ReturnedRowsOverlayTest.an_update_that_returns_its_row_leaves_the_servers_columns_in_the_cache`, `WritesThatSelectTest.an_insert_that_selects_its_id_keeps_the_columns_it_wrote`, `WritesThatSelectTest.an_update_that_selects_and_gets_no_row_back_changes_nothing`, `StaleQueriesTest.an_online_update_whose_filter_the_cache_cannot_evaluate_marks_the_lists_it_may_reach`, `StaleQueriesTest.a_live_reader_of_a_list_the_write_may_reach_fetches_again_once_and_no_other`, `OnlineWriteJoinsQueueTest.a_write_that_joins_the_queue_is_sent_by_a_pass_the_engine_starts_itself`, `HeadSendTest.a_refusal_at_the_head_is_thrown_and_the_write_leaves_the_queue`, `HeadSendTest.a_503_at_the_head_leaves_the_write_queued`, `SchemaCacheMissTest.a_write_meeting_a_column_the_schema_cache_does_not_know_is_queued`, `SchemaCacheMissTest.a_table_still_missing_after_ten_spaced_misses_is_rejected`, `ExpiredSessionWriteTest.online_a_write_whose_refresh_is_refused_is_queued_as_never_sent`</small> |
| **Offline, warm** | Throws the connectivity failure; nothing is queued.<br><small>`OfflineMutationQueueDecisionTest.insert_connectivity_failure_under_OnlineOnly_rethrows`, `MutationOutcomeTest.insert_online_only_connectivity_failure_throws_and_does_not_enqueue`</small> | `Queued`, and shown at once in each cached query that admits it; an UPDATE also brings a row cached by another query with the same columns into a list it starts matching. Not queued, a `MutationNotQueueableException` thrown instead, its cause the connectivity failure: an UPDATE or DELETE with no filter or one the queue cannot record exactly, and a dry run.<br><small>`MutationOutcomeTest.insert_connectivity_failure_returns_queued_and_enqueues`, `ListMembershipTest.a_queued_insert_into_list_b_shows_in_list_b_only`, `QueuedUpdateJoinsListsTest.a_queued_update_moves_a_server_row_into_the_list_it_starts_matching`, `OfflineMutationQueueDecisionTest.a_filter_the_queue_cannot_capture_is_not_queued_and_reports_why`, `OfflineMutationQueueDecisionTest.an_unfiltered_update_is_not_queued_and_reports_why`, `DryRunWriteTest.an_offline_dry_run_is_refused_and_never_queued`</small> |
| **Offline, cold** | As offline, warm.<br><small>`OfflineMutationQueueDecisionTest.insert_connectivity_failure_under_OnlineOnly_rethrows`</small> | `Queued`; a reader of a query with nothing cached sees the queued rows it admits.<br><small>`EmptyCacheReactivityTest.queued_offline_insert_is_visible_via_substrate_without_writing_a_room_row`</small> |
| **Network access off** (`disableNetwork()`) | Throws `NetworkDisabledException`; nothing is sent.<br><small>`NetworkSwitchTest.while_disabled_calls_that_must_reach_the_server_throw_and_send_nothing`</small> | `Queued`, and nothing is sent until access is back on.<br><small>`NetworkSwitchTest.while_disabled_reads_come_from_the_cache_writes_queue_and_nothing_is_sent`</small> |
| **Session restoring** | Sent at once, as supabase-kt sends any request meanwhile: on the anon key.<br><small>`WritesWhileRestoringTest.an_online_only_write_is_sent_at_once_with_the_token_sent_meanwhile`</small> | `Queued` for the stored user, online or not, and sent with that user's token once the session settles.<br><small>`WritesWhileRestoringTest.an_online_write_is_queued_until_the_session_settles_then_sent_as_the_user`, `WritesWhileRestoringTest.a_write_made_offline_while_restoring_is_queued`, `QueueIdentityTest.a_restoring_session_is_its_stored_user`</small> |

What is waiting and what was refused. Each row names the test that pins it.

| Fact | Test |
|---|---|
| `pendingMutations`, `pendingMutationCount` and `pendingMutationCountFlow` show the current user's queued writes, and those with no user recorded that they may take over; never another user's, who cannot discard one by its id either. | `QueueVisibilityAcrossUsersTest.a_users_queued_writes_are_invisible_to_another_user_who_cannot_discard_them` |
| `discardPendingMutation` waits for a send of the write in flight, then removes it, unsent: the write is lost. | `DiscardWhileSendingTest.a_write_being_sent_is_discarded_once_its_send_ends` |
| Discarding the write at the head of the queue lets the writes behind it go, with no call from the app. | `DiscardWhileSendingTest.discarding_the_write_at_the_head_lets_the_writes_behind_it_go` |
| `rejectedMutations` lists the current user's refused writes only; another user can neither see, retry nor dismiss one by guessing its id. | `QueueVisibilityAcrossUsersTest.a_users_rejection_is_invisible_to_another_user`, `QueueVisibilityAcrossUsersTest.another_user_cannot_retry_or_dismiss_a_rejection_by_guessing_its_id` |
| `retryRejectedMutation` queues the write again, and it is sent. | `QueueVisibilityAcrossUsersTest.the_user_who_made_a_refused_write_can_retry_it_and_it_is_sent_again` |
| A refusal whose body was too large to keep says so (`isRetryable` is `false`), and retrying it throws. | `QueueVisibilityAcrossUsersTest.a_rejection_whose_body_was_too_large_to_keep_says_so_and_cannot_be_retried` |
| A write sent at once that the server refuses throws the server's error, and is not listed: the list is for refusals found on replay. | `HeadSendTest.a_refusal_at_the_head_is_thrown_and_the_write_leaves_the_queue` |
