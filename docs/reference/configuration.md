# Configuration

The plugin's settings go in the `install(Offline) { … }` block, an `Offline.Config`, read once when
the client is created. Every setting has a default that suits most apps. The samples write every
setting out, each at its default but those they supply: their own connectivity signal, the Realtime
tables, the version columns and the resolver, and a debug build's log level:

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [ClientSettings.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/ClientSettings.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:every-setting"
    ```

=== "iOS"

    ```swift
    --8<-- "App/ClientSettings.swift:every-setting"
    ```

## The settings

| Setting | Type | Default | What it sets |
| --- | --- | --- | --- |
| [`cacheSizeBytes`](#cachesizebytes) | `Long` | 100 MiB | The most the cached query results may hold |
| [`fileCacheSizeBytes`](#filecachesizebytes) | `Long` | 256 MiB | The most the Storage file cache may hold |
| [`dataDirectory`](#datadirectory) | `String?` | `null`: the platform's place | Where the database and the file cache live |
| [`networkAvailability`](#networkavailability) | `Flow<Boolean>?` | `null`: the platform's monitor | The connectivity signal |
| [`autoSync`](#autosync) | `Boolean` | `true` | Whether queued writes are sent without the app asking |
| [`primaryKeys`](#primarykeys), `primaryKey(table, vararg columns)` | `MutableMap<String, List<String>>` | `id` for every table | Each table's key columns |
| [`versionColumns`](#versioncolumns), `versionColumn(table, column)` | `MutableMap<String, String>` | none: every write replays unchecked | Each table's version column, which guards the replay of a queued update or delete |
| [`conflictResolver`](#conflictresolver) | `ConflictResolver?` | `null`: the defaults | Settles a conflict a replay finds |
| [`realtime { table(…) }`](#realtime), `realtimeTables` | `List<RealtimeTable>` | none: no channel is opened | The tables kept current through Realtime |
| [`responseTrust`](#responsetrust) | `ResponseTrust` | `SettledSession` | Which server answers may replace what the cache holds |
| [`logLevel`](#loglevel) | `LogLevel?` | `null`: the client's `defaultLogLevel` | The least severe level the plugin logs at |

`Offline.CACHE_SIZE_UNLIMITED`, `-1L`, turns eviction off for either cache.

### `cacheSizeBytes`

The most the cached query results may hold, counted as the summed length of the cached responses,
not the size of the database file. Past it, the results fetched longest ago are evicted, never one
of up to 500 lists a live reader shows. Any other value below 4 KiB counts as 4 KiB. See
[Cache size and eviction](../behaviors/eviction.md).

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [ClientSettings.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/ClientSettings.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:sizes"
    ```

=== "iOS"

    ```swift
    --8<-- "App/ClientSettings.swift:sizes"
    ```

### `fileCacheSizeBytes`

The most the Storage file cache may hold, in bytes. When a download passes it, the least recently
used files are deleted until the cache is a tenth below the bound; a file being read, and one used
in the last 30 seconds, are kept. Any other value below zero counts as zero.

### `dataDirectory`

The directory for everything the plugin keeps on the device: the database, `supabase_offline.db`,
and the Storage file cache. `null` keeps them where the platform's convention puts them: the
database in the app's database directory and the files under `<filesDir>/supabase_offline` on
Android, both under `Library/Application Support` on iOS. On iOS, a database found in `Documents`,
where an earlier build kept it, is first moved to that default place with its files.

When the directory has no database yet and the default location has one that no client of this
process holds, the first client moves the database there with its unsent writes, then the
downloaded files ([Project setup](../getting-started/setup.md#a-data-directory-and-a-second-process));
when both places hold a database, it uses the configured one and logs that the other was left
behind, with its files. On Android, a directory under `noBackupFilesDir` keeps the data out of
backups ([Backups](../getting-started/setup.md#backups)).

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [ClientSettings.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/ClientSettings.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:data-directory"
    ```

=== "iOS"

    ```swift
    --8<-- "App/ClientSettings.swift:data-directory"
    ```

### `networkAvailability`

Your own connectivity signal, `true` while the device is online, in place of the platform's
monitor. `null` uses the monitor: Android's `ConnectivityManager`, counting a network with
internet that Android has validated (it needs the `ACCESS_NETWORK_STATE` permission the library's
manifest declares), or Network's path monitor on iOS.

The signal is a hint, never a gate: every request you make is tried whatever it says, and it
decides only when the library tries by itself, whether a waiting refresh reads `Paused` or
`Stalled`, and what `syncStatus.value.network` says
([Replaying the queue](../behaviors/sync.md#the-connectivity-monitor-is-a-hint-never-a-gate)). A
signal that fails or ends is no longer watched (a failure is logged), and the network is then
`UNKNOWN`, which counts as online.

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [ConnectivitySignal.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/ConnectivitySignal.kt) · [ClientSettings.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/ClientSettings.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:own-signal"

    --8<-- "platform/ConnectivitySignal.kt:connectivity-signal"
    ```

=== "iOS"

    ```swift
    --8<-- "App/ClientSettings.swift:own-signal"
    ```

### `autoSync`

Whether the plugin sends queued writes by itself: with `true`, the default, when the session
settles into a user with writes queued, when the network comes back, and, for a client without
Auth, once when it opens with writes an earlier run left queued. `false` stops those three, and you
call `syncPendingMutations()` yourself; a write queued behind another is still sent as before
([When the library sends by itself](../behaviors/sync.md#when-the-library-sends-by-itself)).

### `primaryKeys`

Each table's primary-key columns, by table name: the name alone for a table in the client's
default schema, `schema.table` for one in another schema. A table not named has the key `id`. The key is how the cache recognizes a row: where a queued or applied upsert
lands, which row a server record replaces, and which rows a queued write reports in
`PendingMutation.rowKeys`. Set it for every table whose key is not `id`, such as one with a
composite key, with `primaryKey("task_tags", "task_id", "tag")`. A key with no column throws
`IllegalArgumentException`.

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [ClientSettings.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/ClientSettings.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:primary-keys"
    ```

=== "iOS"

    ```swift
    --8<-- "App/ClientSettings.swift:primary-keys"
    ```

### `versionColumns`

Each table's version column, by table name, keyed as [`primaryKeys`](#primarykeys) is: the name
alone in the client's default schema, `schema.table` in another. A version column is one a
server-side trigger changes on every update of a row: a `timestamptz` kept by `moddatetime`, or an
integer the trigger increments ([Project
setup](../getting-started/setup.md#a-server-side-version-column)). Naming it guards the replay of a
queued UPDATE or DELETE whose filter pins one row a cached list holds: the queue records the version
the device saw, and a pass sends the write only while the server's row still has it. A row that
moved meanwhile is a conflict, settled by [`conflictResolver`](#conflictresolver). Writes sent at
once, inserts, upserts and writes that pin no single cached row are not checked. The value is
compared as text, for equality only, exactly as the server sent it. The cached list the row was read
into must include the column: a list read with named columns that leave it out holds the row without
it, and a write to that row records no base. `versionColumn` throws `IllegalArgumentException` for a
blank column, and a column among the table's key columns fails `createSupabaseClient` with it. See
[Conflicts and merging](../behaviors/conflicts.md).

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [ClientSettings.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/ClientSettings.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:version-column"
    ```

=== "iOS"

    ```swift
    --8<-- "App/ClientSettings.swift:version-column"
    ```

### `conflictResolver`

A `ConflictResolver`, a `fun interface` whose `suspend fun resolve(conflict: MutationConflict)`
answers a `ConflictResolution`: `KeepLocal`, `KeepServer`, `ConflictResolution.apply(columns)` or
`Hold`. A pass asks it about an UPDATE whose row moved with one of the write's columns changed on
the server, a DELETE of a row that moved, and an UPDATE whose row is gone. It runs on the
library's own dispatcher, outside every database transaction, while the pass waits; anything it
throws but a cancellation, and 30 seconds without an answer, hold the write on
`conflictedMutations()`. `null`, the default, settles a moved row with `KeepLocal` and an UPDATE of
a gone row with `KeepServer`. In Swift it is `Config.conflictResolver`, an `async throws` closure.
See [The resolver](../behaviors/conflicts.md#the-resolver).

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [ClientSettings.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/ClientSettings.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:conflict-resolver"
    ```

=== "iOS"

    ```swift
    --8<-- "App/ClientSettings.swift:conflict-resolver"
    ```

### `realtime`

The tables whose changes on the server the plugin applies to the cache as they happen, through
supabase-kt's Realtime plugin, which must be installed; `realtimeTables` lists what the blocks
named. `table(name, schema, filter)` names one table, in the client's default schema unless
`schema` says otherwise, with an optional `RealtimeFilter(column, operator, value)` the server
evaluates before sending a change (`eq`, `neq`, `gt`, `gte`, `lt`, `lte` or `in`; `in` takes a
non-empty list, the others one value, and another shape throws `IllegalArgumentException` when the
filter is made); row-level security narrows a table to the user's rows without one. A table's rows
are matched by the key [`primaryKeys`](#primarykeys) gives, `schema.table` for a table of another
schema. With no table named, no channel is opened. A table named without `install(Realtime)` fails
`createSupabaseClient` with `IllegalStateException`.

```kotlin
install(Realtime)
install(Offline) {
    realtime {
        table("tasks")
        table("audit", schema = "app", filter = RealtimeFilter("status", FilterOperator.EQ, "open"))
    }
}
```

The channel is joined while a user is signed in (or Auth is not installed) and the network is not
known down, left otherwise and at `close()`, and every join reads the named tables again, since
Realtime replays nothing. `realtimeStatus` says where it stands, and `disableRealtime()` and
`enableRealtime()` are the app's own switch, not kept across clients; see
[Applying Realtime events](../behaviors/realtime.md).

### `responseTrust`

Which answers from the server may replace what the cache holds. The question matters while the
session is not the user's: while a stored session restores, or after its refresh failed,
supabase-kt sends the anon key, and row-level security can answer with fewer rows, or none.

| Value | Rule |
| --- | --- |
| `ResponseTrust.SettledSession` (default) | An answer replaces the cache when its request went out under a settled session (signed in, signed out, or a client without Auth) that had not changed when it answered. A session that is restoring, or whose refresh failed, never writes to the cache |
| `ResponseTrust.AuthenticatedSessionOnly` | An empty answer is written only when the same user was signed in before and after the request; an answer with rows is written signed out too, under the same exclusions. For an app that reads public rows signed out and must never see an empty signed-out answer replace them |

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:response-trust"
    ```

=== "iOS"

    The Swift package's `Config` has no response-trust setting.

### `logLevel`

The least severe level the plugin logs at. The plugin logs through supabase-kt's own logging: to
the logging processor the client was built with (`defaultLoggingFactory` in
`createSupabaseClient`), tagged `Supabase-Offline`. `null` uses the client's `defaultLogLevel`, as
supabase-kt's own plugins do. There is no second logging setup.

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [ClientSettings.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/ClientSettings.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:log-level"
    ```

=== "iOS"

    ```swift
    --8<-- "App/ClientSettings.swift:log-level"
    ```

| Level | What the plugin logs | Carries row data |
| --- | --- | --- |
| `DEBUG` | Each read's strategy and shape, request bodies, server responses | Yes: rows, bodies, filter values |
| `INFO` | Reads served from the cache, writes queued, sync passes and their counts | No |
| `WARNING` | Recovered surprises: a record dropped for want of its key, a refused write moved to the rejected list, a committed dry run | No |
| `ERROR` | Failures the library could not recover from by itself | No |

Messages at `INFO` and above name tables, operations, queued writes' ids, counts, HTTP statuses
and exception types; never rows, filter values, tokens or object paths. See
[Security and privacy](../help/security.md#logs).

## Read once

The plugin reads its settings when the client is created: changing `supabase.offline.config`
afterward has no effect. The same holds for the PostgREST plugin's settings the library copies
when it first needs them: the read-shape check builds its own PostgREST from yours at the first
`from()`, so a PostgREST setting changed after that (`propertyConversionMethod`, say) is not
followed by it.

## Opening and closing

The plugin opens its database when the client is created, on the calling thread, and one database
serves one client at a time: a second client over the same database or data directory throws
`IllegalStateException` until the first is closed.

`supabase.close()` closes the plugin once supabase-kt has begun closing its HTTP client.
`supabase.offline.close()` closes the plugin alone; called first, it lets the calls not yet sent go
out.

| | What happens |
| --- | --- |
| At once | Every new call fails with `IllegalStateException`, on the plugin and on any builder or bucket obtained from it. Every flow being collected completes; one collected afterward fails. `syncStatus` keeps its last value. The library's own work stops: refreshes, passes, syncs and Storage calls, whose callers get the closed error, never a cancellation |
| Left to finish | A PostgREST write, a clear, a one-shot `select` that goes to the network, an `rpc`, a read of the cache, and a resumable upload. A write whose answer arrives is applied and never sent again |
| May still be sent | Through `supabase.close()`, a call made while supabase-kt closes its HTTP client is either still sent or ends as it would offline: answered from the cache, `Queued`, or the closed error |
| The bound | `close()` returns once the database is released, or after 10 seconds at most, and never throws. Past that, the database is released when the last call finishes, and a new client on it fails until then |

## Testing

`Offline.Config.inMemory(clock)`, from the `supabase-offline-kt-testing` artifact, keeps the cache,
the queue and the file cache in memory for tests, and `inMemory(clock, realtime)` takes a
`FakeRealtime` in place of the Realtime channel the named tables would open.
`inMemory(clock, store)` keeps them in an `InMemoryStore` instead, so a client built over it after
an earlier one closed finds what that one left. See [Testing your app](../getting-started/testing.md).
