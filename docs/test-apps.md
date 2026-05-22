# Test Apps

The repo ships two demo apps that exercise the SDK end-to-end against the schema in
[demo/supabase/schema.sql](../demo/supabase/schema.sql):

- **Android (`composeApp/`)** — Jetpack Compose + ViewModels
- **iOS (`iosApp/`)** — SwiftUI + The Composable Architecture (TCA)

Both apps are simple TODO apps: list tasks, create tasks, mark them done, edit titles/notes on
a detail screen, attach photos uploaded to the `task-photos` storage bucket, and sync pending
mutations when the device regains connectivity. They are the most up-to-date reference for what
a real consumer should look like.

## Contents

- [Provision Supabase](#provision-supabase)
- [Secrets setup](#secrets-setup)
- [Building & running](#building--running)
- [SDK surface used by the demos](#sdk-surface-used-by-the-demos)
- [Connectivity-driven sync](#connectivity-driven-sync)
- [Pending mutation count](#pending-mutation-count)
- [Storage download](#storage-download)
- [Known gaps in the demos](#known-gaps-in-the-demos)

## Provision Supabase

Open the Supabase Dashboard → SQL Editor → New query → paste
[demo/supabase/schema.sql](../demo/supabase/schema.sql) → Run. The file is idempotent; you can
re-run it any time.

It creates:

- `public.tasks (id, title, notes, is_done, created_at, updated_at)`
- `public.task_photos (id, task_id, bucket_id, object_path, content_type, size_bytes, uploaded_at)`
- A `moddatetime` trigger that touches `tasks.updated_at` on every UPDATE.
- The `task-photos` Supabase Storage bucket (public).
- Permissive RLS policies for `anon` so the demo can read/write everything without auth.

> Do **not** copy those policies into a production project. They exist purely so the demo works
> with an anon key.

## Secrets setup

### Android: `local.properties` → `BuildConfig`

Copy [local.properties.example](../local.properties.example) to `local.properties` at the repo
root and fill in:

```properties
SUPABASE_URL=https://YOUR-PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR-ANON-KEY
```

[composeApp/build.gradle.kts](../composeApp/build.gradle.kts) reads them at build time and
generates `BuildConfig.SUPABASE_URL` / `BuildConfig.SUPABASE_ANON_KEY`.

### iOS: `Secrets.xcconfig` → `Info.plist` → `AppSecrets`

Copy [iosApp/Configuration/Secrets.xcconfig.example](../iosApp/Configuration/Secrets.xcconfig.example)
to `iosApp/Configuration/Secrets.xcconfig` and fill in:

```properties
SUPABASE_URL=YOUR-PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR-ANON-KEY
```

> The iOS URL has **no** `https://` prefix.

The file is `#include?`-d by `Config.xcconfig`, injected into
[iosApp/iosApp/Info.plist](../iosApp/iosApp/Info.plist), then read back in
[AppSecrets.swift](../iosApp/iosApp/AppSecrets.swift).

## Building & running

### Android

```bash
./gradlew :composeApp:installDebug
```

You can also open the project in Android Studio and Run the `composeApp` configuration.

### iOS

First generate the KMP framework that the iOS app embeds:

```bash
./gradlew :shared:assembleSupabaseOfflineReleaseXCFramework
```

Then open [iosApp/iosApp.xcodeproj](../iosApp/iosApp.xcodeproj), set a valid Team and `TEAM_ID`
under **Signing & Capabilities** (the bundle id pattern in
`iosApp/Configuration/Config.xcconfig` includes `$(TEAM_ID)`), and run on a simulator or
device.

> When you change anything in the Swift wrapper (`SupabaseOfflineSupport`), do **Product → Clean
> Build Folder** in Xcode before re-running.

## SDK surface used by the demos

The same handful of SDK calls power both apps:

| Operation | Android | iOS |
|-----------|---------|-----|
| Reactive list | `offline["tasks"].selectAsFlow { order("created_at", DESCENDING) }` | `offline.get(table:"tasks").selectAll(...)` + reload on mutations |
| One-shot list | `offline["tasks"].select(NetworkFirst()) { ... }` | `client.get(table:"tasks").selectAll(readStrategy: .networkFirst, ...)` |
| Detail read (cache-first) | `offline["tasks"].select(CacheFirst()) { filter { eq("id", id) } }` | `client.get(table:"tasks").selectAll(readStrategy: .cacheFirst, filter: { $0.eq(column: "id", value: id) })` |
| Insert | `offline["tasks"].insert(Task(...))` | `client.get(table:"tasks").insertOne(value: task)` |
| Update | `offline["tasks"].update(TaskPatch(...)) { filter { eq("id", id) } }` | `client.get(table:"tasks").updateOne(value: patch, filter: { $0.eq(column: "id", value: id) })` |
| Delete | `offline["tasks"].delete { filter { eq("id", id) } }` | `client.get(table:"tasks").deleteWhere(filter: { $0.eq(column: "id", value: id) })` |
| Upload photo | `offline.storage("task-photos").upload(path, bytes) { upsert = true; contentType = … }` | `bucket.resumable.createOrContinueUpload(data: bytes, source: path, path: path) { $0.upsert = true }` then `upload.startOrResumeUploading()` |
| Cached photo URL | `offline.storage("task-photos").ensureLocalFile(objectPath)` → `File` | `bucket.ensureLocalFileUrl(objectPath:isPublic:)` → `file://...` |

All mutations default to `MutationStrategy.QueueIfOffline`, so neither app needs to handle the
offline case explicitly — the SDK enqueues writes that fail with a connectivity error and the
UI updates optimistically from local state.

The Android list view uses `selectAsFlow` so it re-emits whenever the cache is updated by a
sync or a queued mutation. The iOS list view re-issues `selectAll(...)` after each mutation
because the Swift wrapper does not yet expose a Flow API.

## Connectivity-driven sync

Neither app uses WorkManager or `BGTaskScheduler`. They watch the system connectivity API and
call `syncAllPendingMutations()` whenever the device transitions to online.

### Android

`AndroidConnectivityObserver` is a `ConnectivityManager.NetworkCallback` wrapper that exposes a
debounced `Flow<Boolean>`. `DemoApp` collects it and triggers
`TaskListViewModel.syncPendingMutationsIfAny()` whenever it flips to `true`:

```kotlin
LaunchedEffect(isOnline) {
    if (isOnline) {
        listViewModel.syncPendingMutationsIfAny()
    }
}
```

`TaskListViewModel` checks `offlineClient.pendingMutationCount()` first; if `0`, it skips the
sync call entirely.

### iOS

`IOSConnectivityObserver` is an `NWPathMonitor` wrapper. `ContentView` watches it and dispatches
a `TasksFeature.Action.onlineStatusChanged(_:)`, which in turn sends `.syncPendingMutations`.

## Pending mutation count

The Android app subscribes to the reactive Flow variant for an in-banner counter:

```kotlin
val pendingMutationCount: StateFlow<Int> = offlineClient.pendingMutationCountFlow()
    .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
```

The iOS app polls `client.pendingMutationCount()` before and after each sync run; the TCA
reducer keeps the latest value in state for the banner.

## Storage download

Both apps prefer the `ensureLocalFile` / `ensureLocalFileUrl` helpers so they get a path back
without buffering bytes in app memory. The detail screens render each photo from its local file
URL, which means an offline launch can still display previously downloaded photos.

## Known gaps in the demos

- **No `selectAsFlow` on iOS.** Reactive observation is currently Kotlin-only; the Swift
  wrapper uses async/await + manual reload.
- **No background scheduler.** Sync is driven entirely by foreground connectivity. If your app
  needs WorkManager or `BGTaskScheduler` to flush mutations when the app isn't running, you'll
  have to write that yourself — the SDK does not start any timers or schedulers.
- **No storage staleness check.** The apps download once and reuse forever. If you want a max
  age, follow the [client-driven staleness pattern](./storage.md#client-driven-staleness).
- **Uploads happen online-only.** The offline bucket wrapper caches *downloads*; uploads are
  not queued. If the device is offline when a photo is added, both apps surface the error and
  the row is not persisted.
