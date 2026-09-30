# Changelog

All notable changes to this project are recorded in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers follow the
[compatibility policy](https://nesfeder.github.io/supabase-offline-kt/project/compatibility/).

<!-- Keep the five headings under "Unreleased", even when they are empty: scripts/api-compat.py
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

### Changed

### Deprecated

### Fixed
