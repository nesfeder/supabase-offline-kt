[![CI](https://github.com/nesfeder/supabase-offline-kt/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/nesfeder/supabase-offline-kt/actions/workflows/ci.yml)

# Supabase Offline SDK (Kotlin Multiplatform)

An open-source Kotlin Multiplatform SDK that wraps the official
[`supabase-kt`](https://github.com/supabase-community/supabase-kt) client to make PostgREST and
Storage **offline-first**: SELECT responses are cached locally, writes are queued when the
network is down, and Storage downloads are kept on disk. Ships an Android library (AAR) and an
iOS Swift Package backed by XCFrameworks.

This README is the entry point. Deep dives live in [docs/](./docs).

## Documentation index

- [docs/project/architecture.md](./docs/project/architecture.md) — The architecture: the public
  API in full, how the engine works, and the decisions behind both.
- [docs/api-reference.md](./docs/reference/supported-features.md) — Every public type with behavior tables and
  the full exception reference.
- [docs/configuration.md](./docs/reference/configuration.md) — Configuration and logging, for both
  platforms.
- [docs/storage.md](./docs/behaviours/storage.md) — Storage download cache, path helpers, client-driven
  staleness model.
- [docs/persistence.md](./docs/reference/on-device-data.md) — Room schema for `object_cache`,
  `mutation_cache`, `storage_objects` and DB locations per platform.
- [docs/project/releasing.md](./docs/project/releasing.md) — Where releases are published and what
  each one carries.
- [docs/samples/index.md](./docs/samples/index.md) — Offline Tasks, the sample app, on Android and
  iOS, and the Supabase project it needs.

## Project layout

```
shared/                KMP SDK (commonMain + androidMain + iosMain)
samples/android/       Offline Tasks, the Android sample (Jetpack Compose, Material 3 Expressive)
iosApp/                iOS SwiftUI + TCA TODO demo
ios/                   Swift wrapper (SupabaseOfflineSupport) + distribution XCFrameworks
scripts/               CI + release helper scripts
.github/               GitHub Actions workflows
docs/                  Feature documentation (this set of files)
samples/supabase/      The samples' Supabase schema (tables, row-level security, the photo bucket)
Package.swift          SwiftPM manifest pointing at ios/distribution/
```

## Prerequisites

- JDK 17
- Android Studio with Android SDK 37 — for the Android sample
- Xcode 15+ — for the iOS demo
- A Supabase project of your own, for its URL and publishable key (see [Sample apps](#sample-apps))

## Sample apps

The sample app, Offline Tasks, runs against a Supabase project of your own. The Android sample runs
on this library: its tasks and photos are cached on the device, and what you change offline is
queued and sent when the network comes back. Creating that project, loading the schema and running
each sample are on the documentation site:
[Set up Supabase](./docs/samples/supabase-setup.md),
[Run the Android sample](./docs/samples/android.md) and
[Run the iOS sample](./docs/samples/ios.md).

## Using the SDK in your own app

The two snippets below are the smallest end-to-end examples.
For the full API surface (read strategies, mutation strategies, flow observation, storage cache
helpers), see [docs/api-reference.md](./docs/reference/supported-features.md).

### Minimal Kotlin usage

The library is a supabase-kt plugin: install it beside PostgREST (and Storage, if you use it) and
reach it as `supabase.offline`, the way `supabase.postgrest` is reached. The same code runs on
Android and iOS.

```kotlin
val supabase = createSupabaseClient(
    supabaseUrl = BuildConfig.SUPABASE_URL,
    supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
) {
    install(Postgrest)
    install(Storage)
    install(Offline) // settings go here: install(Offline) { cacheSizeBytes = … }
}

// A read that falls back to the cache when the server cannot be reached (NetworkFirst)
val tasks = supabase.offline.from("tasks").select {
    order("created_at", Order.DESCENDING)
}.decodeList<Task>()

// A write: sent now, or queued while offline and sent when the network is back
when (val outcome = supabase.offline.from("tasks").insert(Task(title = "Buy milk"))) {
    is MutationOutcome.Applied -> outcome.result
    is MutationOutcome.Queued -> outcome.pendingMutation
}

// Queued writes go by themselves when the network is back; to send them now and see what happened:
val report = supabase.offline.syncPendingMutations() // report.remainingCount, report.rejected

// On sign-out: the cache, the downloaded files and every unsent write
supabase.offline.clearLocalData()
```

`supabase.offline.from(…)` takes supabase-kt's own calls, filters and request DSL. A few call
shapes need an edit, listed in the documentation of `OfflinePostgrestQueryBuilder`: a write whose
result is read, a suspending call or a `return` inside a request lambda, and a request lambda
passed inside the parentheses.

### From Swift

The Swift package is being rebuilt as a native Swift API over a new bridge, and this build has no
Swift API to call; its quick start returns with it.

## Core concepts at a glance

- **`ReadStrategy`** — `NetworkOnly`, `NetworkFirst(maxAge?)`, `CacheFirst(maxAge?)`,
  `CacheOnly(maxAge?)`. TTL is evaluated at **read** time against the cached row's
  timestamp. See [the architecture's read path](./docs/project/architecture.md#the-read-path).
- **`FlowReadStrategy`** — `CacheFirstThenNetwork`, `NetworkFirstThenCache`, `CacheOnly` for
  reactive `selectAsFlow`.
- **`MutationStrategy`** — `QueueIfOffline` (default) saves the write to the mutation queue,
  sends it at once when nothing of the user waits ahead of it, and keeps it queued when it may not
  have run (no connection, a `503` or `520`); `OnlineOnly` sends it now or throws. A write sent at
  once that the server refuses throws, as in supabase-kt.
- **Sync is automatic.** Queued writes are sent when the network comes back and when the session
  settles; `syncPendingMutations()` sends them now, and `syncStatus` says where things stand.
  `install(Offline) { autoSync = false }` leaves those passes to the app.
- **Storage downloads** are cached to disk and served from there when offline. Use
  `bucket.ensureLocalFile` / `ensureLocalPath` to skip the bytes-in-memory step.

## Consumer integration

Each minor release of this library is built and tested against exactly one supabase-kt minor:
this one against **supabase-kt 3.6**. Use supabase-kt 3.6.x with it; with another minor, the plugin
logs a warning when it is installed, naming both versions. Why 3.6 and not 3.8, and what 3.6 does
not have: [supabase-kt](./docs/project/compatibility.md#supabase-kt).

- **Kotlin Multiplatform and Android (Gradle):**
  `io.github.nesfeder.supabase-offline:supabase-offline-kt:X.Y.Z` from Maven Central, and
  `io.github.nesfeder.supabase-offline:supabase-offline-kt-testing:X.Y.Z` for tests. See
  [Releasing](./docs/project/releasing.md).
- **iOS (Swift Package Manager):** depend on `https://github.com/nesfeder/supabase-offline-swift`
  with `.upToNextMinor(from: "X.Y.Z")`, and add the `SupabaseOffline` product to your app target
  (`SupabaseOfflineTesting` to its tests). iOS 16 and Xcode 26 or later.

Maintainers release by following [RELEASING.md](./RELEASING.md).

## Troubleshooting

- **`Postgrest plugin is not installed`** — call `install(Postgrest)` and `install(Storage)`
  when you build the underlying `SupabaseClient`.
- **Empty `BuildConfig.SUPABASE_URL` on Android** — `local.properties` is missing or has empty
  values. Copy [local.properties.example](./local.properties.example), fill it in, then re-sync
  Gradle.
- **iOS not building after a fresh clone** — run
  `./gradlew :shared:assembleSupabaseOfflineReleaseXCFramework` before opening Xcode so the
  KMP framework exists for Xcode to embed.
- **iOS signing** — set a valid Team and update `TEAM_ID` in
  `iosApp/Configuration/Config.xcconfig` if needed.
- **First SELECT offline returns no data** — the SDK only seeds the cache after a successful
  network call. If you launch fully offline before ever syncing, there is no data to fall back
  to.

## License

MIT — see [LICENSE](./LICENSE).
