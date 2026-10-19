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

The verbs are supabase-kt's, with its overloads and request DSL; Move to another task, on
[Storage caching](storage.md#writes), uses the update DSL. An update with a typed value, and one
with a JSON body:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:typed-update"

    --8<-- "data/supabase/OfflineTaskRepository.kt:body-update"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:typed-update"

    --8<-- "Data/TaskRepository.swift:body-update"
    ```

An upsert that asks for its row back, and a delete by filter:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:duplicate-task"

    --8<-- "data/supabase/OfflineTaskRepository.kt:delete-task"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:duplicate-task"

    --8<-- "Data/TaskRepository.swift:delete-task"
    ```

Several rows in one write:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:insert-list"

    --8<-- "data/supabase/OfflineTaskRepository.kt:starter-list"

    --8<-- "data/supabase/OfflineTaskRepository.kt:json-insert"
    ```

=== "iOS"

    ```swift
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
| Nothing of the user waits ahead of it, the session is settled, and the server accepts it | `Applied`, and the cached lists are patched with the rows the server returned, or with the rows sent when it returned none |
| Something of the user waits ahead of it, in any table, or the session is still restoring | `Queued`, and a pass is started to send it |
| Network access is off (`disableNetwork()`) | `Queued`; nothing is sent until access is back on |
| Sent at once, and it may not have run: no connection or no answer, a `503` or `520`, an expired token that could not be refreshed, or PostgREST's schema cache not knowing the table or a column yet | `Queued`. A later pass sends it: when the network comes back, the session settles, another write is made or the app syncs |
| Sent at once, and the server refused it with any other answer | The server's exception is thrown, as `supabase.from(…)` would, and the write leaves the queue; it never reaches the rejected list, which holds only refusals found on replay ([Conflicts and failures](failures.md)) |
| The caller is canceled while the write is on the wire | It stays queued, since the server may have it, and a pass sends it |

A write sent at once goes out exactly as you built it. A `Queued` UPDATE or DELETE of one cached
row of a table with a version column replays with a precondition instead: only while the server's
row still has the version the device saw, asking for its row back. A row that moved meanwhile is a
conflict, which your resolver settles or holds for the user
([Conflicts and merging](conflicts.md)); `MutationOutcome` keeps its two cases either way.

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
under either strategy, online or offline, before anything is sent or queued
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

## Dry runs

A dry run (`Prefer: tx=rollback`: supabase-kt 3.8's `dryRun()`, or the header added by hand on
3.6) is never queued, since a replay would run it for real: it is sent at once, and offline throws
`MutationNotQueueableException`. PostgREST rolls it back only when the project lets a request
choose (`db-tx-end` set to `commit-allow-override` or `rollback-allow-override`), which a hosted
Supabase project does not by default, so there **a dry run commits**: the library reads
`Preference-Applied`, applies such a write like any other, patches its rows into the cache and
logs a warning once per client. Check a dry run against a project that allows the override, or do
not rely on it.

```kotlin
--8<-- "QueueExamples.kt:dry-run"
```

## Upserts and ids

A queued write keeps `onConflict`, `ignoreDuplicates` and `defaultToNull` for its replay. The cache
merges an upsert by its `onConflict` columns or the table's key (`id` unless configured)
([Optimistic overlay](overlay.md#limits)).

**Make ids on the device**: such a row can be shown, opened and edited before it reaches the
server, and is never inserted twice. A keyed INSERT (its rows carry the table's primary key) whose
earlier send may have reached the server is replayed as an upsert that ignores duplicates, and `SyncReport.alreadyOnServerCount` counts
such rows. An INSERT whose key the server makes is inserted twice when the answer to its first send
was lost; it can show twice in a read of its table answered while it is on the wire and, unless it
asks for its rows back (`select()`), in that query's cached rows, across restarts, until they are
fetched again; what a replay overwrites is on [Conflicts and failures](failures.md#conflicts).
An upsert is never checked for conflicts, even on a table with a version column: PostgREST has no
precondition for one, so a replayed upsert overwrites the columns it sets and recreates a row
deleted elsewhere. Edit a row that exists with an update. A
queued row carries only the columns it wrote until the list is fetched again
([Optimistic overlay](overlay.md#tips)).

## Another schema and the request lambda

The queue replays to the client's default schema only, so a `QueueIfOffline` write to another
schema is sent at once and does not wait for writes queued to the default one; offline it throws a
`MutationNotQueueableException` naming the schema, and online it marks the cached queries of that
schema's table stale. Name the schema with `from(schema, table)` or the request's `schema`: the
engine does not read a raw `Content-Profile` header.

A write runs its `request` lambda up to three times (to read its schema, filter and `select()`, to
tell a dry run, and to send it; an `OnlineOnly` write, once), so it must configure the same request
every time: no side effects, and no clock or counter read inside it.

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
(`rowKeys`), its `body`, and `baseVersion`: the version of the row the write replays against, on a
table with a version column, or `null` when the write is not checked
([What the queue records](conflicts.md#what-the-queue-records)). `discardPendingMutation(id)` is **destructive**, and the way out of a
write the server keeps failing, which holds every later write of the user behind it. The queue is
never evicted and has no size bound. Sending it is on [Replaying the queue](sync.md); refused
writes are on [Conflicts and failures](failures.md#the-rejected-list).

## Behavior matrix

Facts about the engine, one row per condition; the conditions are those of the
[one-shot read matrix](read-strategies.md#behavior-matrix), where warm and cold say whether the
queries a write touches are cached.

| Condition | `OnlineOnly` | `QueueIfOffline` (default) |
|---|---|---|
| **Online** | Sent at once, never behind the queue: `Applied`. The cached queries are not patched. | Recorded in the queue, then sent at once when it heads its user's queue: `Applied`, and patched into every cached query it touches and every live query it now matches. Rows the server returned are used: an INSERT or UPSERT keeps the columns it wrote beside them; an UPDATE that asked for rows back changes those rows alone, and none when it got none. A write whose filter the cache cannot evaluate marks the lists it may reach stale, and their live readers fetch again. Behind pending writes: `Queued`, and a pass the engine starts sends it. A server's refusal is thrown and the write leaves the queue. These leave it `Queued` instead: a 503 or 520; PostgREST's schema cache not knowing the table or a column yet (`PGRST205`, `PGRST204`), until the tenth such refusal, which rejects it; and a session token the Auth server will not refresh, since the write was never sent. |
| **Offline, warm** | Throws the connectivity failure; nothing is queued. | `Queued`, and shown at once in each cached query that admits it; an UPDATE also brings a row cached by another query with the same columns into a list it starts matching. Not queued, a `MutationNotQueueableException` thrown instead, its cause the connectivity failure: an UPDATE or DELETE with no filter or one the queue cannot record exactly, and a dry run. |
| **Offline, cold** | As offline, warm. | `Queued`; a reader of a query with nothing cached sees the queued rows it admits. |
| **Network access off** (`disableNetwork()`) | Throws `NetworkDisabledException`; nothing is sent. | `Queued`, and nothing is sent until access is back on. |
| **Replayed, on a table with a version column** | Never queued, so never checked. | An UPDATE or DELETE that pins one cached row is sent with its version as a precondition and asks for its row back: the row back is cached at its new version; no row back is followed by a read by key, which finds it applied already, gone, denied by a policy, or moved, a conflict for the resolver ([The five outcomes](conflicts.md#the-five-outcomes)). Inserts and upserts replay as they were made. |
| **Session restoring** | Sent at once, as supabase-kt sends any request meanwhile: on the anon key. | `Queued` for the stored user, online or not, and sent with that user's token once the session settles. |

What is waiting and what was refused:

- `pendingMutations`, `pendingMutationCount` and `pendingMutationCountFlow` show the current user's
  queued writes, and those with no user recorded that they may take over; never another user's, who
  cannot discard one by its id either.
- `discardPendingMutation` waits for a send of the write in flight, then removes it, unsent: the
  write is lost.
- Discarding the write at the head of the queue lets the writes behind it go, with no call from the
  app.
- `rejectedMutations` lists the current user's refused writes only; another user can neither see,
  retry nor dismiss one by guessing its id.
- `retryRejectedMutation` queues the write again, and it is sent.
- A refusal whose body was too large to keep says so (`isRetryable` is `false`), and retrying it
  throws.
- A write sent at once that the server refuses throws the server's error, and is not listed: the
  list is for refusals found on replay.
- `conflictedMutations` lists the current user's writes held after a conflict; resolving one with
  `KeepLocal` or `Apply` queues it again under a new id, against the server's version it recorded
  ([The conflicted list](conflicts.md#the-conflicted-list)).
