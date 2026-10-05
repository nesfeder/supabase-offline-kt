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

It depends on supabase-kt's PostgREST, Storage and Auth modules and on its BOM, so you need not
declare them. To add another supabase-kt module, such as Realtime, take its version from the same
BOM, so every supabase-kt module stays on one version:

```kotlin
commonMain.dependencies {
    implementation(project.dependencies.platform("io.github.jan-tennert.supabase:bom:3.6.0"))
    implementation("io.github.jan-tennert.supabase:realtime-kt")
}
```

Before 1.0, pin the minor you have tested: a minor release may break your build. See
[Compatibility](../project/compatibility.md).

## Add a Ktor engine per target

supabase-kt sends its requests through Ktor and needs an engine on each platform. Use the Ktor
version supabase-kt 3.6.0 was released with, 3.4.3, or later:

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

```kotlin
--8<-- "GettingStartedExamples.kt:install"
```

Import `io.github.nesfeder.supabase.offline.offline` to reach it as `supabase.offline`, the way
`supabase.postgrest` is reached.

- **PostgREST** is required: `from` throws `IllegalStateException` without it.
- **Storage** is needed only for `supabase.offline.storage`.
- **Auth** is optional. With it, the cache belongs to one user at a time and each user's queued
  writes are kept apart and sent with that user's token. Without it, or with your own token
  provider, there is one identity for the life of the install; see
  [Signing out and switching users](../behaviors/sign-out.md).
- **Install the plugin last.** It opens its database when the client is created. When a plugin
  installed after it fails to set up, `createSupabaseClient` throws and the database stays claimed
  until the process ends.

**One client per process.** Opening the plugin claims its database, and a second client over the
same database throws `IllegalStateException` until the first is closed. Build the client once, in
your `Application` on Android or at app start on iOS, and share it. Opening touches the file
system on the calling thread, so build it off the main thread when that matters.

On Android the plugin takes the application context from androidx.startup, so it needs no
`Context`. If your app removes androidx.startup's `InitializationProvider` from its manifest,
`createSupabaseClient` throws, naming it.

The settings go in the `install(Offline) { … }` block. Every one has a default; see
[Configuration](../reference/configuration.md).

## First read

```kotlin
--8<-- "GettingStartedExamples.kt:first-read"
```

The call is supabase-kt's own, with its filters, order and range. Its read strategy defaults to
`NetworkFirst`: the server answers, and the answer is kept on the device; when the server cannot
be reached, the kept rows are served, with your queued writes laid over them. The first read must
reach the server: before it, nothing is kept. [One-shot reads](../behaviors/read-strategies.md)
covers the four strategies.

## First live read

`selectAsFlow` emits the rows as they change: when you write through the plugin, when a refresh
from the server lands, and when a Realtime record is applied. It needs no Realtime channel.

```kotlin
--8<-- "GettingStartedExamples.kt:first-observed-read"
```

Each emission is a `QuerySnapshot`: the rows (`null` while nothing is known yet), whether they are
the device's copy, when they were fetched, whether a queued write changed them, and how the
refresh behind them stands. See [Reactive reads](../behaviors/reactive-reads.md).

## First write

```kotlin
--8<-- "GettingStartedExamples.kt:first-write"
```

**Make the row's id on the device.** A write made offline can then be shown, opened and edited
before it reaches the server, and a write that is sent again after its answer was lost carries the
same id, so the server does not insert it twice. A row whose key the server makes cannot have
either.

A write returns `MutationOutcome.Applied`, with the server's answer, when it was sent at once, and
`MutationOutcome.Queued` when it waits in the mutation queue. Either way, every cached read shows
it at once. A write sent at once that the server refuses throws supabase-kt's exception, as
`supabase.from(…)` does. See [Writes and the offline queue](../behaviors/mutations.md).

## Connectivity and sync

There is nothing to wire up. The plugin watches the platform's connectivity (`ConnectivityManager`
on Android, Network's path monitor on iOS) and sends queued writes by itself when the network
comes back and when a user's session settles. `supabase.offline.syncStatus` says where things
stand, for a banner, and `syncPendingMutations()` sends the queue now, for a "Sync now" button. See
[Replaying the queue](../behaviors/sync.md).

## Sign-out

Call `supabase.offline.clearLocalData()` when a user signs out for good, before `auth.signOut()` or
in a `finally` around it: offline, `signOut()` throws, and a clear after it never runs. It deletes
the cached rows, the downloaded files and every write still queued. With Auth, the cache already passes from one
user to the next, but queued writes stay with the user who made them until you clear them. See
[Signing out and switching users](../behaviors/sign-out.md).

## Logging

The plugin logs through supabase-kt's own logging, tagged `Supabase-Offline`, at the client's
`defaultLogLevel` unless you give it a level of its own:

```kotlin
--8<-- "GettingStartedExamples.kt:logging"
```

Messages at `INFO` and above carry no rows, filter values, tokens or object paths. `DEBUG` carries
request bodies and responses: keep it out of release builds. See
[Security and privacy](../help/security.md#logs).

## Android

### The manifest

The library's manifest declares `android.permission.ACCESS_NETWORK_STATE`, for the connectivity
monitor that tells it when the network comes back. It is merged into every app that uses the
library, so it appears in your app's merged manifest and its store listing's permissions. Give
`install(Offline) { networkAvailability = … }` your own signal and the monitor is not used; the
permission is declared all the same.

### Backups

The database holds cached rows and writes not yet sent, and the file cache holds downloaded
objects: all of it one user's, and all of it can be fetched again. Android's Auto Backup includes
both by default, so a restore onto a new device would bring them back under whoever signs in
next. Leave them out of your backup rules. For Android 12 and later
(`android:dataExtractionRules`), with the default data directory:

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
one, the first client moves the database there, with its unsent writes; the downloaded files stay
where they were. When it cannot, it keeps using the default one and logs a warning.

So an app that already shipped with the default, and then sets `dataDirectory`, keeps its old
downloads in `<filesDir>/supabase_offline/` (Android) or
`Library/Application Support/supabase_offline/` (iOS), where `clearLocalData()` no longer reaches
them and Android's Auto Backup still includes them. Delete that directory yourself once the client
has opened on the new one, and keep its backup `exclude` line until then.

The claim on the database is per process. If your app runs code in a second process (a service or
a WorkManager worker declared with `android:process`), do not install the plugin there: two
processes over one database can send the same queued write twice, and the move above can happen
while the other process has the database open.

## Next steps

- [Coming from supabase-kt](migrating.md): moving existing calls.
- [Testing your app](testing.md): the in-memory client for unit tests.
- [Behaviors](../behaviors/index.md): what each call does offline.
- [Configuration](../reference/configuration.md): every setting.
