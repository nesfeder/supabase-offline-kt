# Project setup

Every step from a Kotlin Multiplatform project to a first offline read and write. The library is a
supabase-kt plugin, so most of this is supabase-kt's own setup with one more `install`.

## Requirements

| | |
| --- | --- |
| Kotlin | 2.4 or later. The library is built with Kotlin 2.4.20 and needs `kotlin-stdlib` 2.4.20 at run time |
| supabase-kt | 3.6. The library brings supabase-kt's BOM at 3.6.0, with PostgREST, Storage and Auth; with another minor, the plugin logs a warning when it is installed ([why 3.6](../project/compatibility.md#supabase-kt)) |
| Android | API 23 (Android 6.0) or later for the library. The [sample app](../samples/android.md) needs API 24 |
| iOS | 15 or later, for the `iosArm64` and `iosSimulatorArm64` targets of a Kotlin Multiplatform module |

There are no JVM, JS or Wasm targets. A native Swift app uses the Swift package instead:
[iOS apps in Swift](swift.md).

## Add the dependency

The library is on Maven Central, which needs no credentials. Add it to the shared module's
`commonMain`:

=== "Version catalog"

    ```toml
    [versions]
    supabase-offline = "0.1.0"

    [libraries]
    supabase-offline = { module = "io.github.nesfeder.supabase-offline:supabase-offline-kt", version.ref = "supabase-offline" }
    ```

    ```kotlin
    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation(libs.supabase.offline)
            }
        }
    }
    ```

=== "Kotlin DSL"

    ```kotlin
    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt:0.1.0")
            }
        }
    }
    ```

It brings supabase-kt's PostgREST, Storage, Auth and Realtime modules and its BOM. Take any other
supabase-kt module, such as Functions, from the same BOM, so every module stays on one version:

```kotlin
commonMain.dependencies {
    implementation(project.dependencies.platform("io.github.jan-tennert.supabase:bom:3.6.0"))
    implementation("io.github.jan-tennert.supabase:functions-kt")
}
```

Before 1.0, pin the minor you have tested: a minor release may break your build. See
[Compatibility](../project/compatibility.md).

## Add a Ktor engine per target

supabase-kt sends its requests through Ktor: give each platform an engine, at the Ktor version
supabase-kt 3.6.0 was released with, 3.4.3, or later:

```kotlin
kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.4.3")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.4.3")
        }
    }
}
```

## Create the client

Build the supabase-kt client as you do today, and install the plugin last:

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:install"
    ```

=== "iOS"

    ```swift
    --8<-- "App/AppModel.swift:install"
    ```

Import `io.github.nesfeder.supabase.offline.offline` to reach it as `supabase.offline`, as
`supabase.postgrest` is reached. **PostgREST** is required (`from` throws `IllegalStateException`
without it); **Storage** is needed only for `supabase.offline.storage`; **Auth** is optional, and
with it the cache belongs to one user at a time and each user's queued writes are sent with that
user's token ([Signing out and switching users](../behaviors/sign-out.md)). The settings go in the
`install(Offline) { … }` block, each with a default ([Configuration](../reference/configuration.md)).

- **Install the plugin last.** It opens its database when the client is created, on the calling
  thread (build it off the main thread when that matters); when a plugin installed after it fails
  to set up, `createSupabaseClient` throws and the database stays claimed until the process ends.
- **One client per process.** A second client over the same database throws
  `IllegalStateException` until the first is closed: build it once, in your `Application` on
  Android or at app start on iOS, and share it.
- **No `Context` on Android.** The plugin takes the application context from androidx.startup; if
  your manifest removes its `InitializationProvider`, `createSupabaseClient` throws, naming it.

## First read

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:first-read"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:first-read"
    ```

The call is supabase-kt's own. `NetworkFirst`, the default, is named only to give it a `maxAge`:
the server's answer is kept on the device, and when the server cannot be reached the kept rows are
served, with your queued writes laid over them, unless they are older than `maxAge`. Before the
first read reaches the server nothing is kept ([One-shot reads](../behaviors/read-strategies.md)).

## First live read

`selectAsFlow` emits the rows as they change: when you write through the plugin, when a refresh
lands, and when the server reports a change to a table `realtime { }` names, or a record is applied
by hand; it needs no channel of its own.

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift) · [TasksModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Tasks/TasksModel.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:first-observed-read"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:first-observed-read"

    --8<-- "Tasks/TasksModel.swift:live-read"
    ```

Each emission is a `QuerySnapshot`: the rows (`null` while nothing is known yet), whether they are
the device's copy, when they were fetched, whether a queued write changed them, and how the
refresh behind them stands ([Reactive reads](../behaviors/reactive-reads.md)).

## First write

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:first-write"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:first-write"
    ```

**Make the row's id on the device**: a write made offline can then be shown, opened and edited
before it reaches the server, and one sent again after its answer was lost is not inserted twice.
A write returns `MutationOutcome.Applied`, with the server's answer, or `Queued`, and every cached
read shows it at once either way; one sent at once that the server refuses throws supabase-kt's
exception, as `supabase.from(…)` does ([Writes and the offline queue](../behaviors/mutations.md)).

## Connectivity and sync

Nothing to wire up: the plugin watches the platform's connectivity and sends queued writes by
itself when the network comes back and when a user's session settles. `syncStatus` is what a sync
banner reads, and `syncPendingMutations()` is what a "Sync now" button calls
([Replaying the queue](../behaviors/sync.md)).

## Sign-out

Call `supabase.offline.clearLocalData()` when a user signs out for good, before `auth.signOut()` or
in a `finally` around it: offline, `signOut()` throws, and a clear after it never runs. It deletes
the cached rows, the downloaded files and every write still queued; with Auth the cache already
passes from one user to the next, but queued writes stay with their user until you clear them.
With your own token provider and no Auth, clear only once it has stopped returning the outgoing
user's token: a live read refreshes right after the clear with whatever token it gets
([Signing out and switching users](../behaviors/sign-out.md)).

## Logging

The plugin logs through supabase-kt's own logging, tagged `Supabase-Offline`, at the client's
`defaultLogLevel` unless you give it a level of its own:

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [ClientSettings.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/ClientSettings.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:log-level"
    ```

=== "iOS"

    ```swift
    --8<-- "App/ClientSettings.swift:log-level"
    ```

Messages at `INFO` and above carry no rows, filter values, tokens or object paths; `DEBUG` carries
request bodies and responses, so keep it out of release builds
([Security and privacy](../help/security.md#logs)).

## Android

### The manifest

The library's manifest declares `android.permission.ACCESS_NETWORK_STATE`, for its connectivity
monitor, so it is in your app's merged manifest and its store listing's permissions, even when
`install(Offline) { networkAvailability = … }` gives your own signal and the monitor is not used.

### Backups

The database and the file cache hold one user's data, all of which can be fetched again. Android's
Auto Backup includes both by default, so a restore onto a new device would bring them back under
whoever signs in next: leave them out. For Android 12 and later (`android:dataExtractionRules`),
with the default data directory:

```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="database" path="supabase_offline.db" />
        <exclude domain="database" path="supabase_offline.db-wal" />
        <exclude domain="database" path="supabase_offline.db-shm" />
        <exclude domain="file" path="supabase_offline/" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="database" path="supabase_offline.db" />
        <exclude domain="database" path="supabase_offline.db-wal" />
        <exclude domain="database" path="supabase_offline.db-shm" />
        <exclude domain="file" path="supabase_offline/" />
    </device-transfer>
</data-extraction-rules>
```

For Android 11 and earlier (`android:fullBackupContent`), put the same four `exclude` lines in a
`<full-backup-content>` file. On iOS the plugin excludes its files from backups itself.

Or keep everything in a directory Android never backs up, and a new install needs no rules:

```kotlin
--8<-- "GettingStartedExamples.kt:no-backup-directory"
```

### A data directory, and a second process

When `dataDirectory` names a directory that holds no database yet, and the default location holds
one, the first client moves the database there, with its unsent writes, and then the downloaded
files; a file that cannot move is deleted, to be downloaded again, so none is left where
`clearLocalData()` no longer reaches it. When the database cannot move, the client keeps using the
default one and logs a warning. The claim on the database is per process: do not install the
plugin in a second process (a service or a WorkManager worker declared with `android:process`), as
two processes over one database can send the same queued write twice, and the move can happen
while the other process has the database open.

## Next steps

- [Coming from supabase-kt](migrating.md): moving existing calls.
- [Testing your app](testing.md): the in-memory client for unit tests.
- [Behaviors](../behaviors/index.md): what each call does offline.
- [Configuration](../reference/configuration.md): every setting.
