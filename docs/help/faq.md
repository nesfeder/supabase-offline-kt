# FAQ and troubleshooting

Answers to the questions people ask first, and fixes for the problems they meet.

## Setting up

**"Postgrest plugin is not installed."** `supabase.offline.from(…)` needs supabase-kt's PostgREST
plugin: add `install(Postgrest)` to `createSupabaseClient`, before `install(Offline)`. The same
message names Storage when `supabase.offline.storage` is used without `install(Storage)`.

**`supabase.offline` throws `IllegalStateException`.** The client was built without
`install(Offline)`. Install it last; see [Project setup](../getting-started/setup.md#create-the-client).

**"An offline client is already open on … or its data directory."** A second client was built over
the same database while the first is open: build the client once per process and share it, or
`close()` the first before you build another. "Still closing" means the first is releasing the
database: call its `close()` again to wait for that.

**`createSupabaseClient` throws on Android, naming androidx.startup.** The plugin takes the
application context from androidx.startup's `InitializationProvider`. If your manifest removes
that provider, the plugin cannot open its database; keep the provider, or at least the library's
initializer under it.

**A read, an update or a delete throws `IllegalArgumentException`: "two top-level filters on" a
column.** The request has two top-level filters on that column, and supabase-kt would send only the
first. Put them in one
`and { }` group; see [One-shot reads](../behaviors/read-strategies.md#edge-cases).

## The first read offline is empty

The cache holds the answers the server gave, one per request: a request's columns, filters, order
and range make its key. A request never answered online has nothing cached, so offline a one-shot
read fails, and a live read shows nothing but the queued writes it admits (`null` rows when there
are none), even when another cached request of the same table holds the rows it asks for. Read a
detail screen's row from the list a list screen already reads, with the same request, and pick it
out on the device:

```kotlin
--8<-- "ReadExamples.kt:row-from-list"
```

And read each list online once: on the very first launch, before any read has reached the server,
there is nothing to show offline.

## The fallback takes seconds

With nothing cached, a read that fails at the transport waits out supabase-kt's own retries (four
attempts, about seven seconds) before it throws, since there is nothing to fall back to. With the
query cached, a `NetworkFirst` read falls back at once, and a read sent while the network is known
to be down goes out once, without the retries. A server that answers `503` is retried on
supabase-kt's schedule and then thrown, cached or not. See
[One-shot reads](../behaviors/read-strategies.md).

## A list that never finishes loading

`QuerySnapshot.result == null` means nothing is known yet; an empty body means the server said the
list is empty. A list that stays `null` with `refresh` at `Refreshing` is waiting: for the network,
or for a stored session that is still restoring. A refresh that waits for a session never reads
`Stalled`. Under `ResponseTrust.AuthenticatedSessionOnly`, a live read of an empty table made signed
out stays `Refreshing` until the table has rows or a user signs in (without Auth, only rows end
it). See [Refresh lifecycle](../behaviors/refresh-lifecycle.md).

## Reading back a queued row

A queued write shows in cached reads at once, as it was sent. The rows a queued insert or upsert
adds come after the server's rows, whatever the request's order and limit, so sort on the device
when the order matters before the write is sent. They lack the columns the server fills in
(defaults, triggers) until the server has the write: decode them into a type whose server-filled
fields are optional, or write those columns yourself. A write whose result you read needs
`resultOrNull()`, which is `null` while the write is queued; see
[Coming from supabase-kt](../getting-started/migrating.md#call-shapes-that-need-an-edit).

## A write returned `Queued` while online

A write is queued behind any earlier write of the same user still waiting, in any table, so it
never overtakes it; the library sends it a moment later. It is also queued while a stored session
restores, and when it was sent but may not have run (no answer, a `503`). See
[Writes and the offline queue](../behaviors/mutations.md#what-a-write-returns).

## Writes are not sent when the app comes back

Nothing fires when the app comes to the foreground. After a pass that failed in a way that may
pass, the library's own next pass waits up to a minute. Call `syncPendingMutations()` as the app
comes to the foreground; see [Replaying the queue](../behaviors/sync.md#when-to-call-sync).

## Upserts

An upsert merges into the row with the same `onConflict` columns, or the same key: `id`, unless you
set the table's key with `primaryKey(table, columns)`. On a table without an `id` column, name the
conflict columns, or a queued upsert shows as a second row until the list is fetched again. An
upsert that ignores duplicates shows as though it landed, since offline the library cannot tell
whether the server holds the row already. See [Optimistic overlay](../behaviors/overlay.md#limits).

## A change made in the dashboard does not show

The library does not poll. A cached list changes when your app writes through the library, when
the list is fetched again, and when you apply a Realtime record. Fetch the list again (a
`NetworkOnly` read of the same query, for a pull to refresh), or apply Realtime's records; see
[Reactive reads](../behaviors/reactive-reads.md#when-it-fetches-again).

## A debug log line says a request was stopped

With supabase-kt's logging at `DEBUG`, each read logs a failed `GET` "stopped by the offline
engine's read-shape check". That request was never sent: it is how the library learns a read's
shape without asking for a token.

## iOS builds

In a Kotlin Multiplatform app, the library is a dependency of your shared module and ships inside
the framework that module builds, like supabase-kt itself; there is nothing more to add in Xcode. It
supports `iosArm64` and `iosSimulatorArm64`, iOS 15 and later. A native Swift app uses the Swift
package instead: [iOS apps in Swift](../getting-started/swift.md).

## Using the library alongside Room

The library keeps its own Room database, `supabase_offline.db`, beside any database your app has;
the two never share a file or a connection. It runs on the SQLite the library bundles. Do not open
`supabase_offline.db` yourself, and do not keep in Room a copy of data the library already caches
from Supabase: the library is the offline cache for those tables.
