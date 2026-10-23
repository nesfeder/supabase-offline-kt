# Applying Realtime events

Name a table in `realtime { }` and the plugin keeps the device's copy of it current through
Supabase Realtime: it joins one channel for every named table, applies each insert, update and
delete the server sends to every cached list of the table, and reads the named tables again each
time it joins, since Realtime replays nothing. A change made elsewhere (another device, the
dashboard, a database function) shows without a fetch, and the app wires no channel.

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [ClientSettings.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/ClientSettings.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:realtime"
    ```

=== "iOS"

    ```swift
    --8<-- "App/ClientSettings.swift:realtime-tables"
    ```

On Android, install supabase-kt's `Realtime` plugin beside the others ([Project setup](../getting-started/setup.md#add-the-dependency));
`realtime-kt` comes with the library, and a table named without the plugin fails
`createSupabaseClient`. On iOS the core owns the channel: supabase-swift's `Realtime` product is not
needed for the named tables, and stays in the app for broadcast and presence. Each named table must
be in the project's `supabase_realtime` publication, or no change of it is sent;
[Set up Supabase](../samples/supabase-setup.md) adds the samples' two. `table(name, schema, filter)`
takes a schema and one `RealtimeFilter` the server evaluates before it sends a change (`eq`,
`neq`, `gt`, `gte`, `lt`, `lte` or `in`); row-level security narrows a table to the user's rows
without one. The full settings are on the [Configuration](../reference/configuration.md#realtime)
page.

## The lifecycle

The channel is joined while three things hold, and left as soon as one stops:

| The channel is joined while… | …and left when |
| --- | --- |
| a user is signed in, with the Auth plugin installed (without it, always) | the user signs out |
| the network is not known down | the network drops, or `disableNetwork()` turns access off |
| the subscription is on, which it is at start | `disableRealtime()` is called |

It is joined again as soon as every condition holds again, and left for good at `close()`. A
sign-in as another user, with or without a sign-out first, leaves the channel and joins it again as
the new user, who reads the named tables again; a new session of the same user changes nothing. A
channel the server refuses, or closes, is joined again after a wait that starts at a second and
doubles up to a minute, and is cut short by a reconnection or a sign-in; a follow that lasted a
minute starts the wait over. The log says so as a warning when the channel is joined again, not
when it ended because of a sign-out, the network or `close()`. Nothing is joined when no table is
named.

A Kotlin app that keeps channels of its own beside this one, for broadcast, presence or changes of
its own, should install supabase-kt's Realtime plugin with `install(Realtime) { maxAttempts = 1 }`.
On an error reply supabase-kt 3.6.0 rejoins the channel from inside the socket's read loop: it
waits 2 s, sends a leave, and then waits for an `UNSUBSCRIBED` that only that same loop could read.
With the named tables' channel alone this costs nothing: the library removes its channel, and the
disconnect that follows cancels the stuck loop. With an app channel present nothing disconnects,
the loop stays stuck until the heartbeat timeout and the reconnect delay have passed, and the
app's channels receive nothing meanwhile. `maxAttempts = 1` also ends supabase-kt's rejoin after an
error reply for the app's channels, which the wait above never covered: such a channel turns
`UNSUBSCRIBED`, and the app subscribes it again itself. After a socket reconnection supabase-kt
still rejoins every channel.

`realtimeStatus` says where the channel stands, as one value to draw a row of the Sync screen from:

| Status | Meaning |
| --- | --- |
| `Off` | No table is named, `disableRealtime()` was called, or the client is closed. |
| `Paused` | Tables are named and the subscription is on, but nobody is signed in, the network is known down, or access is switched off. |
| `Subscribing` | A join is in flight. |
| `Subscribed` | Changes arrive and are applied. Entered again after every rejoin. |
| `Stalled(cause)` | The join was refused, or the channel ended, while the network was believed up: `cause` says why. The join is tried again after the wait above. |

## The re-read on every join

Realtime does not replay what happened while the channel was not joined, so every join reads the
named tables again: the plugin marks every cached query of each named table stale and fetches the
ones a live read shows, as a `NetworkOnly` read of the same query would. Queries nothing reads
live stay marked and are fetched when they are read next. The cost is one fetch per live query of
each named table on every join, so a channel that drops and rejoins often refetches often; the
backoff above bounds how often a refused join does. A one-shot read is not fetched again by the
join; it takes its strategy's path when the app reads it next.

Between joins, the changes a table receives are applied as they arrive, those waiting together as
one patch of its cached queries; a burst the device cannot keep up with, more than 5,000 changes
waiting across every named table, is discarded as a whole and the named tables are read again as on
a join, with one warning in the log that says how many and which tables.

## The status and the switch

`disableRealtime()` leaves the channel at once and joins none until `enableRealtime()`, which joins
again as soon as the network allows and reads the named tables again. The switch is the app's own,
not kept across clients, like the [network switch](sync.md#the-network-switch); calling either side
twice changes nothing. The samples put it behind **Live updates** on the Sync tab and map the
status onto their own words:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [LibraryMappings.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LibraryMappings.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:live-updates-switch"

    --8<-- "data/supabase/LibraryMappings.kt:realtime-status"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:realtime-switch"

    --8<-- "App/AppModel.swift:live-updates-switch"
    ```

### Without the Auth plugin

A client without Auth joins the channel with the token its own provider returns at the join, and
the library keeps the channel's token current itself: it asks the provider again a minute before
the token's `exp`, and more often as the expiry nears, and sends the channel any token the provider
returns that expires later, as soon as it does, so a long session is not closed by the server when
the token expires. What it cannot see is a change of user: an iOS app, whose core has no Auth
plugin, brackets a sign-in or sign-out with `disableRealtime()` and `enableRealtime()`, as the iOS
sample's `applyLiveUpdates()` above does, so the channel is joined again as the new user. A Kotlin
app with Auth installed needs nothing: the plugin follows the session.

## Manual control

A channel the app wires itself, for a table it does not name, hands each record it delivers to
`applyServerRecord` or `applyServerDelete`, and every cached list of the table shows the change at
once, without fetching again. The samples apply the row the server stored for an online-only write
the same way:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

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
whole and supabase-kt's serializer leaves a property at its default out of what it encodes. The
named tables' channel applies each record the same way, as the JSON the server sent.

## Primary keys

A record is matched by the table's primary key: `id`, unless you set the table's key with
`primaryKey(table, columns)` in the configuration, or pass `primaryKeys` to the call. A record that
lacks a key column, or holds one as `null`, an array or an object, cannot be located: it is logged
and dropped. A DELETE's old record carries the key under row-level security, which is all
`applyServerDelete` needs. For a table in another schema, pass `schema`; its key is the one
configured as `schema.table`, or `id`.

## Limits

- **Realtime does not replay what happened while the device was disconnected.** The named tables
  are read again on every join, as above; a channel of your own reads again after a reconnect, for
  example with a `NetworkOnly` read of the same query. Applying records keeps the cache current
  between fetches, never instead of them.
- **A DELETE event is not filtered by row-level security.** Supabase sends every subscriber the key
  of every deleted row of the table, whoever owned it, and nothing else of the row. The plugin
  removes the row with that key from the cached lists, which hold only what the user may read, so
  a stranger's delete changes nothing on the device; but a table whose keys mean something should
  not be in the publication on these terms.
- **One filter per table.** A table named with a filter receives the changes the filter admits and
  nothing else; a table named twice for one schema is refused when named. Name the table without a
  filter to receive every change the policies allow.
- **The publication.** A table missing from `supabase_realtime` is joined without complaint and
  sends nothing.
- **Broadcast and presence** are not covered: keep them on supabase-kt's, or supabase-swift's, own
  channels. A Kotlin app that does sets `maxAttempts = 1` on supabase-kt's Realtime plugin
  ([The lifecycle](#the-lifecycle)).
- **supabase-kt's pin.** The library is built on supabase-kt 3.6 partly because of Realtime: 3.7.0
  and 3.8.0 carry a Realtime crash ([why 3.6](../project/compatibility.md#why-36-not-38)).
- **The log.** supabase-kt's own Realtime logger prints the join payload, the token included, at
  `DEBUG`; keep `DEBUG` out of release builds ([Security](../help/security.md#logs)).

Nothing happens for a table nothing caches.
