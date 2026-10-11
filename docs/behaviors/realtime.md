# Applying Realtime events

Realtime stays on supabase-kt: the library never opens a channel. Hand each record a channel
delivers to `applyServerRecord` or `applyServerDelete`, and every cached list of the table shows the
change at once, without fetching again.

*From the samples: [TaskRepository+Server.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+Server.swift)*

=== "Android"

    ```kotlin
    --8<-- "ConfigurationExamples.kt:apply-records"
    ```

=== "iOS"

    ```swift
    --8<-- "samples/ios/OfflineTasks/Data/TaskRepository+Server.swift:apply-records"
    ```

## Why

A cached list changes when your app writes through the library and when the list is fetched
again. A change made elsewhere, on another device or in the dashboard, shows only after the next
fetch. Applying Realtime's records keeps the cache current in between, and every live read of the
table emits the change.

## Wiring a channel

With supabase-kt's Realtime plugin installed, collect the table's changes and apply each one. This
sample needs `realtime-kt`, which the library does not bring, so it is not compiled with the
others:

```kotlin
val channel = supabase.channel("todos")
channel
    .postgresChangeFlow<PostgresAction>(schema = "public") { table = "todos" }
    .onEach { action ->
        when (action) {
            is PostgresAction.Insert -> supabase.offline.applyServerRecord("todos", action.record)
            is PostgresAction.Update -> supabase.offline.applyServerRecord("todos", action.record)
            is PostgresAction.Delete -> supabase.offline.applyServerDelete("todos", action.oldRecord)
            else -> Unit
        }
    }
    .launchIn(scope)
channel.subscribe()
```

Add `realtime-kt` from the same supabase-kt BOM as the rest; see
[Project setup](../getting-started/setup.md#add-the-dependency). The library is built on
supabase-kt 3.6 partly because of Realtime: 3.7.0 and 3.8.0 carry a Realtime crash
([why 3.6](../project/compatibility.md#why-36-not-38)).

## What a record does

- **`applyServerRecord(table, record)`** replaces the cached row with the same key in every cached
  list of the table, and adds the row to each list that admits it and does not hold it, after the
  list's own rows.
- **`applyServerDelete(table, oldRecord)`** removes the row with that key from every cached list
  of the table.

Your user's queued writes are still laid over the result, so a change waiting to be sent keeps
showing over the server's record until it is sent. A typed overload of `applyServerRecord` encodes
the row with the client's PostgREST serializer; import `io.github.nesfeder.supabase.offline.applyServerRecord`.

## Primary keys

A record is matched by the table's primary key: `id`, unless you set the table's key with
`primaryKey(table, columns)` in the configuration, or pass `primaryKeys` to the call. A record that
lacks a key column, or holds one as `null`, an array or an object, cannot be located: it is logged
and dropped. A DELETE's old record carries the key under row-level security, which is all
`applyServerDelete` needs.

For a table in another schema, pass `schema`; its key is the one configured as `schema.table`, or
`id`.

## Limits

- **Realtime does not replay what happened while the device was disconnected.** Applying records
  keeps the cache current between fetches, never instead of them: read again after a reconnect,
  for example with a `NetworkOnly` read of the same query.
- **A new row joins every cached list whose filters may admit it**, appended after the list's own
  rows, past its order and limit, as a queued row is; the list's order returns with its next
  fetch. See [Optimistic overlay](overlay.md#limits).
- **Nothing happens for a table nothing caches.**

## Pinned by

`ApplyServerRecordTest`, `ApplyServerRecordToAnotherSchemaTest`.
