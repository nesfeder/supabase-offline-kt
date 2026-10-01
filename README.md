[![CI](https://github.com/nesfeder/supabase-offline-kt/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/nesfeder/supabase-offline-kt/actions/workflows/ci.yml)

# Supabase Offline SDK (Kotlin Multiplatform)

An open-source Kotlin Multiplatform SDK that wraps the official
[`supabase-kt`](https://github.com/supabase-community/supabase-kt) client to make PostgREST and
Storage **offline-first**: SELECT responses are cached locally, writes are queued when the
network is down, and Storage downloads are kept on disk. Ships an Android library (AAR) and an
iOS Swift Package backed by XCFrameworks.

This README is the entry point. Deep dives live in [docs/](./docs).

## Documentation index

- [docs/architecture.md](./docs/project/internals.md) — SDK internals: caching, signatures, subset
  resolution, mutation overlay, sync orchestration, error classification, eviction.
- [docs/api-reference.md](./docs/reference/supported-features.md) — Every public type with behavior tables and
  the full exception reference.
- [docs/configuration.md](./docs/reference/configuration.md) — `SupabaseOfflineConfig` and the
  `OfflineLogger` setup for both platforms.
- [docs/storage.md](./docs/behaviours/storage.md) — Storage download cache, path helpers, client-driven
  staleness model.
- [docs/persistence.md](./docs/reference/on-device-data.md) — Room schema for `object_cache`,
  `mutation_cache`, `storage_objects` and DB locations per platform.
- [docs/project/releasing.md](./docs/project/releasing.md) — Where releases are published and what
  each one carries.
- [docs/test-apps.md](./docs/samples/index.md) — Walkthrough of the Android (Compose) and iOS
  (SwiftUI + TCA) TODO demo apps with cited code.

## Project layout

```
shared/                KMP SDK (commonMain + androidMain + iosMain)
composeApp/            Android Compose TODO demo
iosApp/                iOS SwiftUI + TCA TODO demo
ios/                   Swift wrapper (SupabaseOfflineSupport) + distribution XCFrameworks
scripts/               CI + release helper scripts
.github/               GitHub Actions workflows
docs/                  Feature documentation (this set of files)
demo/supabase/         SQL schema + RLS policies for the demos' tasks/task_photos tables
                       and `task-photos` storage bucket
Package.swift          SwiftPM manifest pointing at ios/distribution/
```

## Prerequisites

- JDK 17
- Android Studio (Hedgehog/Koala or newer) — for the Android demo
- Xcode 15+ — for the iOS demo
- A Supabase project (URL + anonymous API key)

## Quick start

The TODO demo apps connect to a real Supabase project — there is no mock backend. If you
already have a Supabase account, skim Steps 1 and 2; otherwise follow them top-to-bottom.

### 1. Create a Supabase project

1. Sign up / sign in at [supabase.com](https://supabase.com).
2. From the dashboard click **New project**.
3. Pick an organization (create one if you don't have any), give the project a name
   (e.g. `supabase-offline-demo`), and set a strong database password (you won't need it for
   the demo — but save it).
4. Pick the region nearest you, choose the free plan, and click **Create new project**.
5. Wait ~2 minutes for Supabase to finish provisioning.

### 2. Apply the demo schema

The schema lives at [demo/supabase/schema.sql](./demo/supabase/schema.sql) and creates the
`tasks` + `task_photos` tables (with a `moddatetime` trigger on `tasks.updated_at`),
permissive RLS policies for `anon`, and a **public** Storage bucket named `task-photos`.

1. In your new project's left sidebar, click **SQL Editor → New query**.
2. Paste the entire contents of [demo/supabase/schema.sql](./demo/supabase/schema.sql) into the
   editor.
3. Click **Run**. The script is idempotent — re-running it is safe.
4. Verify:
   - **Table Editor** should now list `tasks` and `task_photos`.
   - **Storage → Buckets** should list `task-photos` and show **Public bucket: Yes**.

> The demo RLS policies allow anonymous full access to both tables and to the bucket. That is
> intentional so the apps can run with just the anon key. **Do not copy these policies into a
> production project.**

### 3. Grab the project URL and anon key

1. In the project sidebar, click **Project Settings (the gear icon) → Data API**.
2. Copy **Project URL** — it looks like `https://abcdefghijklmno.supabase.co`.
3. Copy the **`anon` `public` API key** — a long string starting with `eyJ…`. (Do **not** use
   the `service_role` key.)

Keep these two values handy for the next step.

### 4. Configure secrets for the demo apps

Both demos read their secrets from gitignored files; example templates are checked in.

**Android** — copy [local.properties.example](./local.properties.example) to `local.properties`
at the repo root, set `sdk.dir` to your local Android SDK path, and fill in:

```properties
SUPABASE_URL=https://YOUR-PROJECT-REF.supabase.co
SUPABASE_ANON_KEY=YOUR-ANON-KEY
```

`composeApp` reads these at build time and exposes them as `BuildConfig.SUPABASE_URL` /
`BuildConfig.SUPABASE_ANON_KEY` to `MainActivity`.

**iOS** — copy [iosApp/Configuration/Secrets.xcconfig.example](./iosApp/Configuration/Secrets.xcconfig.example)
to `iosApp/Configuration/Secrets.xcconfig` and fill in:

```properties
SUPABASE_URL=YOUR-PROJECT-REF.supabase.co
SUPABASE_ANON_KEY=YOUR-ANON-KEY
```

> The iOS URL has **no** `https://` prefix. The xcconfig stripper treats `//` as a comment
> delimiter, so including it would silently truncate the value at build time. The Swift
> wrapper prepends `https://` automatically.

Both files are listed in [`.gitignore`](./.gitignore); they will never be committed.

Details: [docs/test-apps.md → Secrets setup](./docs/samples/index.md#secrets-setup).

### 5. Build & run

```bash
# Android demo (installs to a connected device or running emulator)
./gradlew :composeApp:installDebug

# iOS demo (build the KMP framework, then open Xcode)
./gradlew :shared:assembleSupabaseOfflineReleaseXCFramework
open iosApp/iosApp.xcodeproj
```

For iOS, set your Apple Developer **Team ID** in
[iosApp/Configuration/Config.xcconfig](./iosApp/Configuration/Config.xcconfig) before building
to a physical device (the simulator works without it).

> The iOS demo embeds the KMP framework directly via the Gradle task above — it does **not**
> consume the SDK through `Package.swift`.

### 6. Try the offline behavior

Once the app is running and connected to your Supabase project:

1. Add a few tasks. They appear immediately and the row count in the table editor updates.
2. Turn airplane mode on (or kill the device's network).
3. Add another task, toggle one done, or delete one. The UI updates instantly and the banner
   shows pending mutations.
4. Turn the network back on. The banner switches to "Syncing…" and the queued mutations push
   to Supabase in order. Refresh the Supabase Table Editor — the changes are now persisted.
5. Force-stop and relaunch the app while offline. The previously fetched tasks still appear
   (served from the local Room cache); newly added photos that were uploaded once will still
   render from disk.

## Using the SDK in your own app

The two snippets below are the smallest end-to-end examples — pulled straight from the demos.
For the full API surface (read strategies, mutation strategies, flow observation, storage cache
helpers), see [docs/api-reference.md](./docs/reference/supported-features.md) and
[docs/test-apps.md](./docs/samples/index.md).

### Minimal Android usage

```kotlin
val supabase = createSupabaseClient(
    supabaseUrl = BuildConfig.SUPABASE_URL,
    supabaseKey = BuildConfig.SUPABASE_ANON_KEY
) {
    install(Postgrest)
    install(Storage)
}

val offline = createSupabaseOfflineClient(context = this, supabase = supabase)

// Cache-friendly read (falls back to cache on connectivity failure by default)
val tasks = offline["tasks"].select(
    columns = Columns.ALL,
    readStrategy = ReadStrategy.NetworkFirst()
) {
    order("created_at", Order.DESCENDING)
}.decodeList<Task>()

// Queues automatically if offline
offline["tasks"].insert(Task(id = null, title = "Buy milk", notes = "Whole, not skim"))

// Trigger a sync when the device is online again
offline.syncAllPendingMutations()
```

### Minimal iOS usage

```swift
let offline = SupabaseFactory.offlineClient(
    supabaseUrl: AppSecrets.supabaseURL,
    supabaseAnonKey: AppSecrets.supabaseAnonKey
)

// Cache-friendly read
let res = try await offline.get(table: "tasks").selectAll(
    readStrategy: .networkFirst,
    orderBy: "created_at",
    ascending: false
)
let tasks = try res.decode([Task].self)

// Queues automatically if offline
_ = try await offline.get(table: "tasks").insertOne(
    value: Task(id: nil, title: "Buy milk", notes: "Whole, not skim")
)

// Trigger a sync when the device is online again
_ = try await offline.syncAllPendingMutations()
```

> `selectAsFlow` (reactive observation) is Kotlin-only today. On iOS, reload after mutations or
> on connectivity changes.

## Core concepts at a glance

- **`ReadStrategy`** — `NetworkOnly`, `NetworkFirst(cacheDuration?)`, `CacheFirst(cacheDuration?)`,
  `CacheOnly(cacheDuration?)`. TTL is evaluated at **read** time against the cached row's
  timestamp. See [docs/architecture.md → Read path](./docs/project/internals.md#read-path-caching-deep-dive).
- **`FlowReadStrategy`** — `CacheFirstThenNetwork`, `NetworkFirstThenCache`, `CacheOnly` for
  reactive `selectAsFlow`.
- **`MutationStrategy`** — `QueueIfOffline` (default) queues writes locally on connectivity
  failure; `OnlineOnly` rethrows. Server-side 4xx/5xx are **never** queued.
- **Sync is manual.** Call `syncAllPendingMutations()` / `syncPendingMutations(table)` yourself —
  typically when connectivity returns. See
  [docs/test-apps.md → Connectivity-driven sync](./docs/samples/index.md#connectivity-driven-sync).
- **Storage downloads** are cached to disk and served from there when offline. Use
  `bucket.ensureLocalFile` / `ensureLocalPath` to skip the bytes-in-memory step.

## Consumer integration

- **Kotlin Multiplatform and Android (Gradle):**
  `io.github.nesfeder.supabase-offline:supabase-offline-kt:X.Y.Z` from Maven Central, and
  `io.github.nesfeder.supabase-offline:supabase-offline-kt-testing:X.Y.Z` for tests. See
  [Releasing](./docs/project/releasing.md).
- **iOS (SwiftPM):** depend on `https://github.com/nesfeder/supabase-offline-kt.git` and add
  the `SupabaseOffline` and `SupabaseOfflineSupport` products to your app target.

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
