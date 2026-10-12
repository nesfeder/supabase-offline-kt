# Replaying the queue

With `autoSync` on, the default, the library sends queued writes by itself when the network comes
back and when a user's session settles; `syncPendingMutations()` sends them now and says what
happened. A pass sends one user's writes one at a time, in the order they were made, across
tables, and stops at the first failure that may pass, so no write overtakes an earlier one.

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [LibraryMappings.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LibraryMappings.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift) · [QueueWording.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Sync/QueueWording.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:sync-now"

    --8<-- "data/supabase/LibraryMappings.kt:sync-report"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:sync-now"

    --8<-- "Sync/QueueWording.swift:sync-report"
    ```

## When the library sends by itself

Passes are coalesced: at most one runs and one waits, however many triggers arrive.

| Trigger | Turned off by `autoSync = false` |
| --- | --- |
| The session settles into a user with writes queued | Yes |
| The network comes back: the monitor or your `networkAvailability` turns online, or `enableNetwork()` is called | Yes |
| A client without Auth opens with writes an earlier run left queued (its session is settled from the start, so no other trigger fires for them) | Yes |
| A write is queued behind an earlier one; a write sent, refused or discarded leaves writes behind it; a rejected write is retried | No |

The last row runs whatever `autoSync` says: a write made online that returned `Queued` would
otherwise wait for the app with nothing to show for it. With `autoSync = false`, call
`syncPendingMutations()` yourself when the network comes back and when a user signs in.

## The connectivity monitor is a hint, never a gate

The library watches the platform's connectivity (Android's `ConnectivityManager`, counting a
network with internet that Android has validated; Network's path monitor on iOS), or your own
`networkAvailability` flow in its place. It never stops a request you make because the monitor
says offline: a monitor can be wrong either way (a captive portal, a VPN, a network that cannot
reach your project), so every read, write and sync you call is tried. What the monitor decides:

- while it says offline, the library's own passes stop after one fails for want of a connection,
  until the monitor stops saying offline, the session settles anew or you call
  `syncPendingMutations()`: one attempt per offline period, so a wrong monitor costs one failed
  attempt;
- when it turns online, waiting refreshes try again at once and, with `autoSync`, queued writes
  are sent;
- whether a waiting refresh reads `Paused` or `Stalled`, and what `syncStatus.value.network` says.

A monitor that fails or ends is no longer watched, and the network is then `UNKNOWN`, which counts
as online. To stop every request, use `disableNetwork()` ([below](#the-network-switch)): that
switch is a gate.

## Waiting between passes

After an automatic pass stopped on a failure that may pass, the next automatic pass waits 1 s,
doubling up to 60 s, and at least as long as a `Retry-After` the server sent, up to an hour. The
network coming back and the session settling end that wait at once and start it again from 1 s,
but never before a `Retry-After` has passed. A pass you start with `syncPendingMutations()` goes at
once and moves no wait.

## When to call sync

Nothing fires when the app comes to the foreground, and after a failed pass the library's own next
one may wait a minute, or as long as a `Retry-After`. Call `syncPendingMutations()` then, as the
samples do (Android from the activity's `onStart`, iOS when the scene's phase becomes `.active`):

From the samples: [OfflineTasksApplication.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/OfflineTasksApplication.kt) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "OfflineTasksApplication.kt:foreground"
    ```

=== "iOS"

    ```swift
    --8<-- "App/AppModel.swift:foreground"
    ```

Call it too for a "Sync now" button (the listing at the top), and before a user signs out for
good, to send what can still be sent ([Signing out](sign-out.md)). It returns promptly offline,
throws nothing for a failure that may pass, and sends nothing while network access is off.

## Order and the session

Writes are sent in the order they were made, the order of their ids, across every table of the
user's queue, so a child row queued after its parent in another table is sent after it.
`syncPendingMutations(table)` sends one table's writes but never ahead of an earlier write of the
user still queued in another table: it stops at the first write that has one and counts what it
held back in `SyncReport.heldBehindOtherTablesCount`. `OnlineOnly` writes, writes the queue cannot
hold, `rpc` and Storage writes are never queued, so they go around the queue.

A pass first waits, for at most 10 seconds, for the session to settle (signed in, or signed out),
except when the network is known to be down. With Auth installed, each write goes with its owner's
token or not at all: a user's writes wait, untouched, while another user is signed in, and an
anonymous write is never sent with a user's token. A PostgREST plugin configured with its own
`jwtToken` sends that token whoever is signed in, so it is not checked.

## What happens to each write

| The server answers | The write |
| --- | --- |
| It accepts it | Leaves the queue; it stays in the overlay until the cached lists have been fetched again after it |
| No connection, a `401`, `408`, `429` or any `5xx`, or a failure on the device | Stays queued, and the pass stops; `syncStatus.value.lastSyncError` says why |
| PostgREST's schema cache does not know the table or a column yet (`PGRST204`, `PGRST205`) | Stays queued, and the pass stops; the tenth such refusal, each at least 30 seconds after the last, rejects it |
| Any other `4xx` | Moves to the rejected list, and the pass goes on |
| (The library can no longer replay it exactly as it was made) | Moves to the rejected list with no `serverError`, and the pass goes on |

## What a sync returns

| `SyncReport` field | Meaning |
| --- | --- |
| `syncedCount` | The writes this call sent and the server accepted |
| `rejected` | The `RejectedMutation`s refused during this call, now on `rejectedMutations()` |
| `remainingCount` | The writes still queued when the call returned |
| `alreadyOnServerCount` | Of `syncedCount`, the inserts whose rows the server already held, because an earlier send whose answer was lost had reached it |
| `heldBehindOtherTablesCount` | Of `remainingCount`, the writes a call for one table held back behind an earlier write to another table; `0` for a call over every table |

The counts are this call's own: a pass the library ran by itself just before is counted nowhere
here, and a refusal it made is on `rejectedMutations()`. Calling sync more than once at a time is
safe: a call that meets a pass already sending the same writes waits for it to let go, then sends
what is left, so it returns only once nothing more can be sent now. A pass in progress when local
data is cleared is canceled first, and its report counts what it sent before.

A sync throws only `IllegalStateException`, when the client is closed or PostgREST is not
installed. A failure that stops the pass is not thrown: `syncStatus.value.lastSyncError` holds it
as a `SyncFailedException`, with the HTTP status and the PostgREST or Postgres code and nothing of
the original message ([Conflicts and failures](failures.md) has the rejected list).

## The status

`syncStatus` is one `StateFlow<SyncStatus>` to draw a sync banner from:

From the samples: [LibraryMappings.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LibraryMappings.kt) · [SyncHeadline.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/SyncHeadline.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/LibraryMappings.kt:sync-banner"
    ```

=== "iOS"

    ```swift
    --8<-- "App/SyncHeadline.swift:sync-banner"
    ```

| Field | Meaning |
| --- | --- |
| `network` | `UNKNOWN` (nothing has said yet; counts as online), `ONLINE`, `OFFLINE` or `DISABLED` (after `disableNetwork()`) |
| `pendingMutationCount`, `rejectedMutationCount` | The current user's queued writes and rejected entries. They follow each change to the queue shortly after it, read together, so a write moving between the two is counted in exactly one |
| `isSyncing` | Whether a pass is sending, started by you or by the library |
| `lastSyncedAt` | When a pass, or a write sent at once, last left the user's queue empty. Kept in memory only |
| `lastSyncError` | Why the last pass stopped with writes queued; cleared when a later pass runs to the end, when sending empties the queue, and when local data is cleared |

It follows the queue from the moment the client opens, so its counts include what an earlier run
left queued. It is the same flow whether or not the client is open, never throws, and after
`close()` holds its last value.

## The network switch

`disableNetwork()` turns the library's network access off, as airplane mode would, for this
client only; `enableNetwork()` turns it back on, and with `autoSync` queued writes are sent. The
switch is not kept: a new client starts with access on.

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:network-switch"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:network-switch"
    ```

While access is off nothing is sent: reads are answered from the cache, live reads read `Paused`,
writes that can be queued are queued, passes send nothing, and a Storage download is answered from
the file cache. A request that must reach the server (a `NetworkOnly` read, an `OnlineOnly` write,
an `rpc`, any Storage call but a download) throws `NetworkDisabledException`, a connectivity
failure. A request already on the wire still answers, and so does a resumable upload created or
paused while access was on. `syncStatus.value.network` reads `DISABLED` as soon as
`disableNetwork()` returns; a flow you derive from `syncStatus` catches up a moment later, so wait
for the value you expect on it.

## Background work

The library sends only while your app's process runs. To send from the background, call
`syncPendingMutations()` from work the platform schedules in the app's own process (a WorkManager
worker on Android, a `BGTaskScheduler` task on iOS), never from a second process
([Project setup](../getting-started/setup.md#a-data-directory-and-a-second-process)).

## Pinned by

| Fact | Test |
|---|---|
| `syncPendingMutations` reports what it sent, what the server refused (with its `ServerError`) and what is still queued. | `SyncReportTest.a_sync_reports_what_it_sent_what_was_refused_and_what_remains` |
| Offline it returns at once, with the writes still queued, and `syncStatus.lastSyncError` says the network. | `SyncReportTest.a_sync_while_offline_returns_with_the_writes_still_queued_and_says_why` |
| `lastSyncError` carries the status and code, never the server's text, a filter value or a token. | `SyncReportTest.the_failure_a_sync_reports_carries_no_filter_value_or_server_text` |
| An insert whose earlier send had in fact arrived is counted in `alreadyOnServerCount`, not inserted twice. | `SyncReportTest.an_insert_whose_earlier_send_had_arrived_is_counted_as_already_on_the_server` |
| A sync that meets the library sending the same writes waits for it, then returns with what is still queued after both. | `SyncReportTest.a_sync_that_meets_the_library_sending_the_same_writes_waits_and_reports_what_remains` |
| With `autoSync` on, the network coming back, network access turned back on, and the session settling anew send queued writes by themselves. | `AutoSyncTest.the_network_coming_back_sends_queued_writes_by_itself`, `AutoSyncTest.turning_network_access_back_on_sends_queued_writes_by_itself`, `AutoSyncTest.the_session_settling_with_writes_queued_sends_them_unless_auto_sync_is_off` |
| With `autoSync = false` those three send nothing, but an online write queued behind an earlier one is still sent without the app asking. | `AutoSyncTest.with_auto_sync_off_the_network_coming_back_sends_nothing`, `AutoSyncTest.with_auto_sync_off_turning_network_access_back_on_sends_nothing`, `AutoSyncTest.with_auto_sync_off_an_online_write_behind_a_queued_one_is_sent_without_the_app_asking` |
| After a pass stopped on a server's `Retry-After`, the library's own next pass waits at least that long, at most an hour. | `RetryAfterTest.the_next_pass_waits_at_least_as_long_as_the_server_asked`, `RetryAfterTest.a_server_cannot_hold_the_library_back_for_more_than_an_hour` |
| `syncStatus` follows the queue, the rejected list and the passes, and says when the queue was last emptied. | `SyncStatusTest.the_status_follows_the_queue_and_says_when_it_was_emptied`, `SyncStatusTest.the_status_counts_the_current_users_refused_writes`, `SyncStatusTest.the_status_says_a_pass_is_running_while_one_is` |
| `syncStatus.network` is `DISABLED` as soon as `disableNetwork()` returns; a new client starts with access on. | `NetworkSwitchTest.the_status_says_disabled_as_soon_as_the_switch_returns_and_not_after_it_is_back_on`, `NetworkSwitchTest.a_new_client_starts_with_network_access_on` |
| While access is off, a Storage download is answered from the file cache, and an upload, a delete or a move throws `NetworkDisabledException`, leaving the cache as it was. | `StorageWhileNetworkDisabledTest.a_download_while_access_is_off_is_answered_from_the_file_cache`, `StorageWhileNetworkDisabledTest.uploads_and_deletes_while_access_is_off_throw_and_leave_the_cache_alone` |
| A sync for one table stops at a write that has an earlier write of the user queued in another table, and counts what it held back. | `SyncReportTest.a_sync_of_one_table_holds_its_writes_behind_an_earlier_one_in_another_table`, `MutationQueueReplayOrderTest.a_table_sync_does_not_send_a_write_ahead_of_an_earlier_one_in_another_table` |
| A failure that may pass, a server's `5xx` included, stops the whole pass rather than skipping ahead. | `MutationQueueReplayOrderTest.a_connectivity_failure_stops_the_whole_sweep`, `MutationQueueReplayOrderTest.a_server_error_stops_the_whole_sweep_instead_of_skipping_ahead` |
| While the monitor says offline, the library makes one failed attempt by itself, then waits for the network, the session or the app; when the server answers, it keeps sending. | `AutoSyncTest.a_device_offline_makes_one_attempt_by_itself_until_the_network_comes_back`, `AutoSyncTest.a_monitor_stuck_offline_holds_nothing_back_when_the_server_answers` |
| A reconnection sends at once, however long a wait failed passes built; a server's `Retry-After` still holds. | `AutoSyncTest.a_reconnection_sends_at_once_however_long_a_wait_failed_passes_built`, `RetryAfterTest.an_edge_does_not_cut_short_the_wait_the_server_asked_for` |
| A client without Auth sends what an earlier run left queued when it opens, and only that: a write made as it opens is sent at once, by itself. | `AutoSyncTest.a_client_without_auth_sends_what_an_earlier_run_left_queued_when_it_opens`, `AutoSyncTest.a_write_made_while_a_client_without_auth_opens_is_not_taken_for_one_an_earlier_run_left` |
| The status counts what an earlier run left queued from the moment the client opens. | `SyncStatusTest.the_status_counts_what_an_earlier_run_left_queued_before_it_is_first_read` |
