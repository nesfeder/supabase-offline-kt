# Changelog

All notable changes to this project are recorded in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers follow the
[compatibility policy](https://nesfeder.github.io/supabase-offline-kt/project/compatibility/).

<!-- Keep the six headings under "Unreleased", even when they are empty: scripts/api-compat.py
     requires a change to the public API, the stored data or the supabase-kt pin to add a line under
     the heading it needs (before the first release, under any of them). A release renames
     "Unreleased" to its version and date, and starts a new one. -->

## Unreleased

Nothing has been released yet. This section describes the library as it stands for its first
release.

### Breaking

### Added

- Offline-first PostgREST reads through supabase-kt. `SupabaseOfflineClient.from(table).select { }`
  takes supabase-kt's own request builder and a `ReadStrategy` (`NetworkFirst`, `CacheFirst`,
  `CacheOnly` or `NetworkOnly`). `selectAsFlow` emits an `OfflineReadResult` each time the cache or
  the network answers, under a `FlowReadStrategy`.
- Writes that work offline. `insert`, `update`, `upsert` and `delete`, and their JSON forms, return
  a `MutationOutcome`: `Applied` with the server's response, or `Queued` when the write was recorded
  in the mutation queue to be sent later (`MutationStrategy.QueueIfOffline`, the default). Queued
  writes show in cached reads until they are sent.
- `syncAllPendingMutations()` and `syncPendingMutations(table)` replay the current identity's queued
  mutations in the order they were made. `pendingMutationCount()` and `pendingMutationCountFlow()`
  report how many are waiting.
- Storage downloads cached on the device. `storage(bucket)` returns an `OfflineBucketApi` with
  `cachedFile` and `ensureCachedFile`, and Android and iOS functions return the cached file's
  location.
- `applyServerRecord` and `applyServerDelete` apply a Realtime change to the cached reads.
- Size limits for the cached rows and the cached files, in `SupabaseOfflineConfig`.
- `clearAllCache()` deletes the cached rows, the queued mutations and the cached files, and
  `close()` releases the client's database.
- `buildInMemorySupabaseOfflineClient` in the `supabase-offline-kt-testing` artifact: a client that
  keeps everything in memory, for tests.
- A record of the public API of both artifacts, checked on every change, so the API cannot change
  without a reviewer seeing it.
- Published on Maven Central as `io.github.nesfeder.supabase-offline:supabase-offline-kt` and
  `io.github.nesfeder.supabase-offline:supabase-offline-kt-testing`, for Android, `iosArm64` and
  `iosSimulatorArm64`, each with its sources, an HTML API reference and a signature on every file.

### Changed

- A locally written row shows only in the cached lists whose filters it matches, leaves a list it
  stops matching, and joins one it starts matching when another cached list read with the same
  columns holds it. `eq`, `neq`, `in` and `is` are evaluated with Postgres's null rules; a
  condition the library cannot evaluate admits the row, and a pending update or delete filtered by
  one changes nothing shown until it is sent. A queued row is still appended past a list's order
  and limit.
- An upsert is shown merged by its conflict columns, or by the table's key (`id` unless configured
  otherwise), and every row of a multi-row upsert shows once.
- Without the Auth plugin, with the app's own token provider, or signed out, an empty answer is
  cached and reactive reads settle on it. A response received while a stored session restores or
  refreshes is never cached.
- With the Auth plugin, the cached rows and cached files belong to one identity at a time: when the
  session settles into another, they are dropped. Queued mutations are kept for their owners.
- `single()`, `maybeSingle()`, `head`, counts (a list with a count included), `csv()`, `geojson()`,
  `stripNulls()` and `explain()` are never cached: they go to the network, and under `CacheOnly`
  throw `NoSuchElementException` without sending anything.
- A table in another schema is cached apart. A write to it with the default strategy is sent at
  once and never queued; offline it throws.
- Offline, a `NetworkFirst` read with a warm cache answers at once instead of after supabase-kt's
  retries; `NetworkFirstThenCache` with a warm cache shows it as `Stalled` and keeps fetching
  instead of failing; a `CacheFirstThenNetwork` refresh reports `Stalled` after about 3.6 s.
- A slow collector of a `CacheFirstThenNetwork` read is handed the latest snapshot, not each one in
  turn, and never holds back the query's other collectors.
- The request lambda passed to a read or a write may run more than once, so it must configure the
  same request every time.
- The on-device database is schema version 1, the one this release ships: each queued write keeps
  its owner, its replay order and its schema-cache refusals in its own row, and each cached query
  the replay order it has caught up with.
- Replayed writes are kept for the overlay until every cached query of their table has been
  fetched again since, or until they fall beyond the newest 1,000 of the table, whatever the device
  clock says; a table nothing caches keeps none.
- A write PostgREST refuses because its schema cache does not know the table or a column yet
  (`PGRST205`, `PGRST204`), as happens just after a migration, stays queued. It is rejected only
  at the tenth such refusal, each counted at least 30 seconds after the last, so a table that does
  not exist cannot hold the queue for ever.
- `close()` lets a replay pass finish the request it has on the wire, and record the answer,
  before it stops, so the next client does not send the write again.

### Deprecated

### Removed

- `NumericDeltaColumn`, and the `deltaColumns` parameter of `updateJson`, `updateWith` and
  `update`. A queued write replays exactly what was sent; a value that several devices change
  concurrently, such as a counter or a running total, is computed on the server from the facts
  clients write.

### Fixed

- Rows a write returns (`select()`) are cached with the columns the caller wrote, matched by key;
  an update that asks for rows back changes only the rows returned.
- A write the cache cannot follow marks the cached lists it may reach stale, and they are fetched
  again, instead of showing values the server no longer holds.
- A cached list a live reader shows is never evicted, for up to 500 live readers.
- A patched list keeps the time it was fetched, and a list made only of local writes is stale.
- A queued write is sent with its owner's session token or not at all: a user signing in while
  another's writes replay no longer has them sent as theirs.
- Offline for longer than a session token lives, a write that may queue is queued instead of
  throwing `TokenExpiredException`.
- A replay refused because its stored filter can no longer be read says so, instead of saying the
  write has no filter.
