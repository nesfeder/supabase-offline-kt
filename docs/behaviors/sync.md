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
| A client without Auth, and without `identityFromAccessToken`, opens with writes an earlier run left queued (its session is settled from the start, so no other trigger fires for them). With the setting, the token naming a user is the session settling | Yes |
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

No timer runs a pass again after one fails. A pass runs only when something asks for one: the
network coming back, the session settling anew (a sign-in, or Auth refreshing the token), a write
queued behind pending ones, a write sent, refused or discarded that leaves more queued, a rejected
write retried, a conflicted write settled with `KeepLocal` or `Apply`, a client without Auth
opening with writes queued, or your own `syncPendingMutations()` call. Requests made while a pass
runs are folded into one more. After an automatic pass stopped on a failure that may pass (the
table [below](#what-happens-to-each-write) says which), the next pass asked for runs no sooner
than 1 s after the failed one ended, doubling up to 60 s, and at least as long as a `Retry-After`
the server sent, up to an hour; a pass that ends any other way resets the wait. The network coming
back and the session settling end that wait at once and start it again from 1 s, but never before
a `Retry-After` has passed. A pass you start with `syncPendingMutations()` goes at once and moves
no wait. With `autoSync` off, only the write-path triggers and your call remain.

So time alone never rejects a write PostgREST's schema cache keeps refusing (`PGRST204`,
`PGRST205`). It is rejected at the tenth refusal counted, and a refusal is counted only 30 seconds
or more after the last one counted; each comes from the write's own first send or from a pass one
of the triggers above asked for. That takes at least ten such sends, 30 seconds apart, and between
them nothing runs.

## When to call sync

Nothing fires when the app comes to the foreground, and after a failed pass nothing runs another
until something asks for one, and then not before the wait above has passed. Call
`syncPendingMutations()` then, as the samples do (Android from the activity's `onStart`, iOS when
the scene's phase becomes `.active`):

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
except when the network is known to be down. A PostgREST plugin configured with its own `jwtToken`
sends that token whoever is signed in, so the owner check of
[Signing out and switching users](sign-out.md#switching-accounts) does not apply to it.

## What happens to each write

| The server answers | The write |
| --- | --- |
| It accepts it | Leaves the queue; it stays in the overlay until the cached lists have been fetched again after it |
| No connection, a `401`, `408`, `429` or any `5xx`, or a failure on the device | Stays queued, and the pass stops; `syncStatus.value.lastSyncError` says why |
| PostgREST's schema cache does not know the table or a column yet (`PGRST204`, `PGRST205`) | Stays queued, and the pass stops; the tenth refusal counted, each 30 seconds or more after the last and each from the write's own send or a pass a trigger asked for, rejects it ([above](#waiting-between-passes)) |
| Any other `4xx` | Moves to the rejected list, and the pass goes on |
| (The library can no longer replay it exactly as it was made) | Moves to the rejected list with no `serverError`, and the pass goes on |
| No row at the version the device saw, on a table with a version column | One read by key tells the case: already applied or gone (synced), a policy denial (the rejected list), or a moved row, which the resolver settles: resent, dropped or parked on the conflicted list, and the pass goes on ([Conflicts and merging](conflicts.md#the-five-outcomes)) |

## What a sync returns

| `SyncReport` field | Meaning |
| --- | --- |
| `syncedCount` | The writes this call sent and the server accepted |
| `rejected` | The `RejectedMutation`s refused during this call, now on `rejectedMutations()` |
| `remainingCount` | The writes still queued when the call returned |
| `alreadyOnServerCount` | Of `syncedCount`, the inserts whose rows the server already held, because an earlier send whose answer was lost had reached it; and on a table with a version column, the updates whose row already held their values and the guarded deletes whose row was already gone |
| `heldBehindOtherTablesCount` | Of `remainingCount`, the writes a call for one table held back behind an earlier write to another table; `0` for a call over every table |
| `conflictsResolvedCount` | Of `syncedCount`, the conflicts settled with `KeepLocal` or `Apply`, by the resolver or the default, and sent |
| `conflictsKeptServerCount` | The writes dropped for the server's row, with `KeepServer`: by default, an update whose row is gone |
| `conflicted` | The `ConflictedMutation`s parked during this call, now on `conflictedMutations()` |
| `stopReason` | Why the call returned when it did: `Completed`, or what stopped it short ([below](#why-a-sync-returned)) |

The counts are this call's own: a pass the library ran by itself just before is counted nowhere
here, and a refusal it made is on `rejectedMutations()`. Calling sync more than once at a time is
safe: a call that meets a pass already sending the same writes waits for it to let go, then sends
what is left, so it returns only once nothing more can be sent now. A pass in progress when local
data is cleared is canceled first, and its report counts what it sent before.

A sync throws only `IllegalStateException`: `OfflineClientClosedException` when the client is
closed, or a plain one when PostgREST is not installed. A failure that stops the pass is not
thrown: the report's `stopReason` names it, and `syncStatus.value.lastSyncError` holds it as a
`SyncFailedException`, with the HTTP status and the PostgREST or Postgres code and nothing of the
original message ([Failures and the rejected list](failures.md) has the rejected list).

## Why a sync returned

`SyncReport.stopReason` is a `SyncStopReason`. A call that waited for the library's own sending
gives the reason its last pass ended for. In Swift it is a `kind`, with the failure as `error`.

| Reason | Why the call returned | What remains |
| --- | --- | --- |
| `Completed` | It went through every write it found queued: each was sent, refused, parked or dropped | Only the writes queued while it ran |
| `NetworkUnavailable` | A write could not reach the server, or got no answer in time | That write and those after it |
| `ServerFailure(error)` | The server answered a write with a failure that may pass: a `5xx`, `408`, `429` or `401`, a schema cache miss, or a `403` or `409` while an earlier write is still queued. `error` has the status and code | That write and those after it |
| `LocalFailure(error)` | A failure on the device: the local database failing, a request header the HTTP client refuses, or the pass canceled by something other than the caller or a clear | The writes it did not send |
| `SessionNotReady` | The session did not settle within the wait, or could not be told while it restored or refreshed | Every write; a pass sends them once the session settles |
| `SessionChanged` | The session changed user while it ran | The new user's writes go in a later pass |
| `NetworkDisabled` | Network access is off ([below](#the-network-switch)) | Every write it did not send |
| `HeldBehindOtherTables` | A call for one table reached a write with an earlier one queued in another table | `heldBehindOtherTablesCount` of them |
| `LocalDataCleared` | Local data was cleared while it ran | Nothing: the clear removed the writes |

The set is open: a later release may add a reason, so a `when` over it keeps an `else` branch, and
a Swift `switch` over its `kind` keeps a `default`. The three failures are recorded as
`lastSyncError`; no other reason is a failure.

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
| `pendingMutationCount`, `rejectedMutationCount`, `conflictedMutationCount` | The current user's queued writes, rejected entries and conflicted entries. They follow each change to the queue shortly after it, read together, so a write moving between them is counted in exactly one |
| `isSyncing` | Whether a pass is sending, started by you or by the library |
| `lastSyncedAt` | When a pass, or a write sent at once, last left the user's queue empty. Kept in memory only |
| `lastSyncError` | The failure that last stopped a pass with writes queued (a `NetworkUnavailable`, `ServerFailure` or `LocalFailure` stop); cleared when a later pass runs to the end or stops because the session changed user, when sending empties the queue, and when local data is cleared. A pass stopped because the session is not ready, access is off or a write waits behind another table leaves it as it was |

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

## At a glance

- `syncPendingMutations` reports what it sent, what the server refused (with its `ServerError`),
  what is still queued, and why it returned (`stopReason`).
- Offline it returns at once, with the writes still queued, and `syncStatus.lastSyncError` says the
  network.
- `lastSyncError` carries the status and code, never the server's text, a filter value or a token.
- An insert whose earlier send had in fact arrived is counted in `alreadyOnServerCount`, not
  inserted twice.
- On a table with a version column, a replayed update or delete whose row moved on the server is a
  conflict: the report counts what the resolver settled and dropped, and lists what it parked.
- `syncStatus.conflictedMutationCount` counts the writes waiting on the conflicted list for the
  app.
- A sync that meets the library sending the same writes waits for it, then returns with what is
  still queued after both.
- With `autoSync` on, the network coming back, network access turned back on, and the session
  settling anew send queued writes by themselves.
- With `autoSync = false` those three send nothing, but an online write queued behind an earlier one
  is still sent without the app asking.
- After a pass stopped on a server's `Retry-After`, the library's own next pass waits at least that
  long, at most an hour.
- `syncStatus` follows the queue, the rejected list and the passes, and says when the queue was last
  emptied.
- `syncStatus.network` is `DISABLED` as soon as `disableNetwork()` returns; a new client starts with
  access on.
- While access is off, a Storage download is answered from the file cache, and an upload, a delete
  or a move throws `NetworkDisabledException`, leaving the cache as it was.
- A sync for one table stops at a write that has an earlier write of the user queued in another
  table, and counts what it held back.
- A failure that may pass, a server's `5xx` included, stops the whole pass rather than skipping
  ahead.
- While the monitor says offline, the library makes one failed attempt by itself, then waits for the
  network, the session or the app; when the server answers, it keeps sending.
- A reconnection sends at once, however long a wait failed passes built; a server's `Retry-After`
  still holds.
- A client without Auth sends what an earlier run left queued when it opens, and only that: a write
  made as it opens is sent at once, by itself. With `identityFromAccessToken`, it sends what the
  token's user left queued.
- The status counts what an earlier run left queued from the moment the client opens.
