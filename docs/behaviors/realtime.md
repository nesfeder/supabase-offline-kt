# Applying Realtime events

Realtime stays on supabase-kt: the library never opens a channel. Hand each record a channel
delivers to `applyServerRecord` or `applyServerDelete`, and every cached list of the table shows the
change at once, without fetching again, so a change made elsewhere (another device, the dashboard)
does not wait for the next fetch:

From the samples: [LiveUpdates.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LiveUpdates.kt) · [TaskRepository+Server.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+Server.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/LiveUpdates.kt:apply-records"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository+Server.swift:apply-records"
    ```

- **`applyServerRecord(table, record)`** replaces the cached row with the same key in every cached
  list of the table, and adds the row, after the list's own rows and past its order and limit, to
  each list that admits it and does not hold it.
- **`applyServerDelete(table, oldRecord)`** removes the row with that key from every cached list of
  the table.

Your user's queued writes are still laid over the result, so a change waiting to be sent keeps
showing over the server's record until it is sent. The typed overload encodes the row with the
client's PostgREST serializer (import `io.github.nesfeder.supabase.offline.applyServerRecord`). The
sample applies `tasks` typed and `task_photos` as JSON; its typed row has every column and no
default values, since the record replaces the cached row whole and supabase-kt's serializer leaves
a property at its default out of what it encodes.

## Wiring a channel

With supabase-kt's Realtime plugin installed, collect the table's changes and apply each one. The
sample subscribes to both tables behind its **Live updates** switch, on by default, and leaves the
channel when the switch goes off, when nobody is signed in, and while the network is down or
Simulate offline is on; it joins again when the network is back:

From the samples: [LiveUpdates.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LiveUpdates.kt) · [ServerChanges.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/ServerChanges.swift) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/LiveUpdates.kt:live-updates"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/ServerChanges.swift:live-updates"

    --8<-- "App/AppModel.swift:live-updates-apply"
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
keeps the cache current between fetches, never instead of them: read again after a reconnect, for
example with a `NetworkOnly` read of the same query, as the sample does each time its channel
connects:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:read-again"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:read-again"
    ```

Nothing happens for a table nothing caches.
