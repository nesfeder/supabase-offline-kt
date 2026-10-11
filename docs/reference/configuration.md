# Configuration

The plugin's settings go in the `install(Offline) { … }` block, an `Offline.Config`. Every setting
has a default that suits most apps; set only what you need. The plugin reads its settings once,
when the client is created. The sample apps write every setting out, each at the default it would
take anyway but the connectivity signal they supply and a debug build's log level, so each line
says what it does:

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:every-setting"
    ```

=== "iOS"

    The iOS sample's section lands with its pull request.

## The settings

| Setting | Type | Default | What it sets |
| --- | --- | --- | --- |
| [`cacheSizeBytes`](#cachesizebytes) | `Long` | 100 MiB | The most the cached query results may hold |
| [`fileCacheSizeBytes`](#filecachesizebytes) | `Long` | 256 MiB | The most the Storage file cache may hold |
| [`dataDirectory`](#datadirectory) | `String?` | `null`: the platform's place | Where the database and the file cache live |
| [`networkAvailability`](#networkavailability) | `Flow<Boolean>?` | `null`: the platform's monitor | The connectivity signal |
| [`autoSync`](#autosync) | `Boolean` | `true` | Whether queued writes are sent without the app asking |
| [`primaryKeys`](#primarykeys), `primaryKey(table, vararg columns)` | `MutableMap<String, List<String>>` | `id` for every table | Each table's key columns |
| [`responseTrust`](#responsetrust) | `ResponseTrust` | `SettledSession` | Which server answers may replace what the cache holds |
| [`logLevel`](#loglevel) | `LogLevel?` | `null`: the client's `defaultLogLevel` | The least severe level the plugin logs at |

`Offline.CACHE_SIZE_UNLIMITED`, `-1L`, turns eviction off for either cache.

### `cacheSizeBytes`

The most the cached query results may hold, counted as the summed length of the cached responses,
not the size of the database file. Past it, the results fetched longest ago are evicted, never one
of up to 500 lists a live reader shows. Any other value below 4 KiB counts as 4 KiB. See
[Cache size and eviction](../behaviors/eviction.md).

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:sizes"
    ```

=== "iOS"

    The iOS sample's section lands with its pull request.

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
process holds, the first client moves the database there, its unsent writes included, and then the
downloaded files; a file that cannot move is deleted, to be downloaded again, rather than left
beyond `clearLocalData()`. When the database cannot move, the client keeps using the default one
and logs a warning; when both places hold a database, it uses the configured one and logs that the
other was left behind, with its files
([Project setup](../getting-started/setup.md#a-data-directory-and-a-second-process)). On Android, a directory under `noBackupFilesDir` keeps the data out of backups; see
[Project setup](../getting-started/setup.md#backups).

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:data-directory"
    ```

=== "iOS"

    The iOS sample's section lands with its pull request.

### `networkAvailability`

Your own connectivity signal, `true` while the device is online, in place of the platform's
monitor. `null` uses the monitor: Android's `ConnectivityManager`, counting a network with
internet that Android has validated (it needs the `ACCESS_NETWORK_STATE` permission the library's
manifest declares), or Network's path monitor on iOS.

The signal is a hint, never a gate: the library tries every request you make, whatever the signal
says, because a monitor can be wrong either way (a captive portal, a VPN, a network that cannot
reach your project). What it decides:

- when the library tries by itself: when it turns `true`, a waiting refresh tries again at once
  and, with `autoSync`, queued writes are sent;
- while it says offline, the library's own passes stop after one fails for want of a connection,
  until the signal turns `true`, the session settles anew or you call `syncPendingMutations()`, so
  a wrong signal costs one failed attempt;
- whether a refresh that cannot reach the network reads `Paused` rather than `Stalled`;
- what `syncStatus.value.network` says.

A signal that fails or ends is no longer watched (a failure is logged), and the network is then
`UNKNOWN`, which counts as online. To stop every request, use `disableNetwork()`: that switch is a
gate. See [Replaying the queue](../behaviors/sync.md).

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [ConnectivitySignal.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/ConnectivitySignal.kt) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:own-signal"

    --8<-- "platform/ConnectivitySignal.kt:connectivity-signal"
    ```

=== "iOS"

    The iOS sample's section lands with its pull request.

### `autoSync`

Whether the plugin sends queued writes by itself. With `true`, the default, it sends them when the
session settles into a user with writes queued, when the network comes back, and, for a client
without Auth, once when the client opens with writes an earlier run left queued. `false` stops
those three: you call `syncPendingMutations()` yourself.

Either way, a write queued behind an earlier one is sent once that one is, as is whatever a sent,
refused or discarded write leaves queued, and a retried rejection: otherwise a write made online
would wait for the app with nothing to show for it.

### `primaryKeys`

Each table's primary-key columns, by table name: the name alone for a table in the client's
default schema, `schema.table` for one in another schema. A table not named has the key `id`. The key is how the cache recognizes a row: where a queued or applied upsert
lands, which row a server record replaces, and which rows a queued write reports in
`PendingMutation.rowKeys`. Set it for every table whose key is not `id`, such as one with a
composite key, with `primaryKey("task_tags", "task_id", "tag")`. A key with no column throws
`IllegalArgumentException`.

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:primary-keys"
    ```

=== "iOS"

    The iOS sample's section lands with its pull request.

### `responseTrust`

Which answers from the server may replace what the cache holds. The question matters while the
session is not the user's: while a stored session restores, or after its refresh failed,
supabase-kt sends the anon key, and row-level security can answer with fewer rows, or none.

| Value | Rule |
| --- | --- |
| `ResponseTrust.SettledSession` (default) | An answer replaces the cache when its request went out under a settled session (signed in, signed out, or a client without Auth) that had not changed when it answered. A session that is restoring, or whose refresh failed, never writes to the cache |
| `ResponseTrust.AuthenticatedSessionOnly` | An empty answer is written only when the same user was signed in before and after the request; an answer with rows is written signed out too, under the same exclusions. For an app that reads public rows signed out and must never see an empty signed-out answer replace them |

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:response-trust"
    ```

=== "iOS"

    The iOS sample's section lands with its pull request.

### `logLevel`

The least severe level the plugin logs at. The plugin logs through supabase-kt's own logging: to
the logging processor the client was built with (`defaultLoggingFactory` in
`createSupabaseClient`), tagged `Supabase-Offline`. `null` uses the client's `defaultLogLevel`, as
supabase-kt's own plugins do. There is no second logging setup.

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:log-level"
    ```

=== "iOS"

    The iOS sample's section lands with its pull request.

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
the queue and the file cache in memory for tests. See [Testing your app](../getting-started/testing.md).
