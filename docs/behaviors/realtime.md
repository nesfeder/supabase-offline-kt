# Applying Realtime events

Name a table in `realtime { }` ([Configuration](../reference/configuration.md#realtime)) and the
plugin opens the channel itself: it applies each insert, update and delete as below, reads the
table again on every join, and `realtimeStatus` says where the channel stands. The rest of this
page is the manual way, for a channel the app wires itself. Hand each record such a channel
delivers to `applyServerRecord` or `applyServerDelete`, and every cached list of the table shows the
change at once, without fetching again, so a change made elsewhere (another device, the dashboard)
does not wait for the next fetch:

From the samples, which apply the row the server stored for an online-only write this way:
[OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:apply-record"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:first-write"
    ```

- **`applyServerRecord(table, record)`** replaces the cached row with the same key in every cached
  list of the table, and adds the row, after the list's own rows and past its order and limit, to
  each list that admits it and does not hold it.
- **`applyServerDelete(table, oldRecord)`** removes the row with that key from every cached list of
  the table.

Your user's queued writes are still laid over the result, so a change waiting to be sent keeps
showing over the server's record until it is sent. The typed overload encodes the row with the
client's PostgREST serializer (import `io.github.nesfeder.supabase.offline.applyServerRecord`); a
typed row should have every column and no default values, since the record replaces the cached row
whole and supabase-kt's serializer leaves a property at its default out of what it encodes.

## The samples' channel

The samples name both tables in the configuration, behind their **Live updates** switch, on by
default: the plugin leaves the channel when the switch goes off, when nobody is signed in, and
while the network is down or Simulate offline is on, and joins again when the network is back. The
switch calls `disableRealtime()` and `enableRealtime()`; the iOS sample, whose client sees no Auth
session, brackets a sign-in or sign-out with it too, so the channel is joined again as the new
user:

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [ClientSettings.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/ClientSettings.swift) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:realtime"
    ```

=== "iOS"

    ```swift
    --8<-- "App/ClientSettings.swift:realtime-tables"

    --8<-- "App/AppModel.swift:live-updates-switch"
    ```

`realtime-kt` comes with the library; install `Realtime` beside the other plugins
([Project setup](../getting-started/setup.md#add-the-dependency)). Each table must be
in Supabase's `supabase_realtime` publication, or no change of it is sent;
[Set up Supabase](../samples/supabase-setup.md) adds the samples' two. The library is built on
supabase-kt 3.6 partly because of Realtime: 3.7.0 and 3.8.0 carry a Realtime crash
([why 3.6](../project/compatibility.md#why-36-not-38)).

## Primary keys

A record is matched by the table's primary key: `id`, unless you set the table's key with
`primaryKey(table, columns)` in the configuration, or pass `primaryKeys` to the call. A record that
lacks a key column, or holds one as `null`, an array or an object, cannot be located: it is logged
and dropped. A DELETE's old record carries the key under row-level security, which is all
`applyServerDelete` needs. For a table in another schema, pass `schema`; its key is the one
configured as `schema.table`, or `id`.

## Limits

**Realtime does not replay what happened while the device was disconnected.** Applying records
keeps the cache current between fetches, never instead of them. For the tables `realtime { }`
names, the plugin marks every cached query of the table stale on each join and fetches the ones a
live read shows again; for a channel of your own, read again after a reconnect, for example with a
`NetworkOnly` read of the same query.

Nothing happens for a table nothing caches.
