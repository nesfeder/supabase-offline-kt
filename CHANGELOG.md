# Changelog

All notable changes to this project are recorded in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers follow the
[compatibility policy](https://nesfeder.github.io/supabase-offline-kt/project/compatibility/).

<!-- Keep the six headings under "Unreleased", even when they are empty: scripts/api-compat.py
     requires a change to the public API, the stored data or the supabase-kt pin to add a line under
     the heading it needs (before the first release, under any of them). A release renames
     "Unreleased" to its version and date, and starts a new one. -->

## Unreleased

### Breaking

- The library depends on supabase-kt's `realtime-kt`, from the same BOM as its other modules, for
  the tables an app names in its configuration to keep current through Realtime. An app that
  already lists `realtime-kt` changes nothing; one that did not now resolves the module, which it
  can leave uninstalled.

### Added

### Changed

### Deprecated

### Removed

### Fixed

## [0.1.0] - 2026-10-12

The first release.

### Breaking

- The library is a supabase-kt plugin. Install it with `install(Offline) { … }` in
  `createSupabaseClient` and reach it as `supabase.offline`, where `createSupabaseOfflineClient(…)`
  returned a client; the settings of `SupabaseOfflineConfig` are the plugin's `Offline.Config`
  (`offlineStoragePath` is `dataDirectory`, a `String`). The old names are gone; see Removed.
  supabase-kt closes the plugin with the client, once it has begun closing its HTTP client: a call
  made in between, or one whose request had not gone out, is still sent (Ktor can send while its
  client finishes closing), or ends as it would offline (a read the cache can answer is answered
  from it, a write the mutation queue holds is `Queued`) or with the closed error, never with a
  cancellation; a request already on the wire still answers. Call `supabase.offline.close()` first
  to let the calls not yet sent go out.
- A flow being collected when the client closes (`selectAsFlow`, `pendingMutations`,
  `pendingMutationCountFlow`, `rejectedMutations`) completes, where it failed with the client's
  closed error, so a screen torn down at logout sees its flow end, not an error. A flow collected
  after `close()` still fails with the closed error, as every other call does.
- Logging goes through supabase-kt's own: the plugin logs to the client's logging processor
  (`defaultLoggingFactory`) with the tag `Supabase-Offline`, at `Offline.Config.logLevel` or the
  client's `defaultLogLevel`. `OfflineLogger`, `OfflineLogLevel` and the `logger` and `minLogLevel`
  settings are gone: configure logging once, in `createSupabaseClient`.
- `MutationOutcome.Queued` is a class that carries the queued write as a `PendingMutation`: match it
  with `is MutationOutcome.Queued`, not `MutationOutcome.Queued ->`. `MutationOutcome.Applied` and
  `Queued` have internal constructors.
- `OfflinePostgrestQueryBuilder` is a final class, as supabase-kt's builder is, and `Offline` and
  `OfflineBucketApi` need the `OfflineInternal` opt-in to implement. A test double comes from
  `supabase-offline-kt-testing`.
- `RefreshState.InFlight` is `RefreshState.Refreshing`, and `RefreshState.Paused` is new: an
  exhaustive `when` over `RefreshState` needs a branch for it.
- `ReadStrategy.NetworkFirst`, `CacheFirst` and `CacheOnly` take `maxAge`, not `cacheDuration`, and
  are no longer data classes. `FlowReadStrategy` and `MutationStrategy` are sealed interfaces: their
  cases are written as before, but `entries`, `values()` and `valueOf` are gone.
- A `CacheOnly` read that finds nothing it may serve throws `CacheMissException`, not
  `NoSuchElementException`. A `QueueIfOffline` write the queue cannot hold (a dry run, or one whose
  request could not be inspected to tell; a filter it cannot record; an unknown user; another
  schema; a write on its way into the queue while local data was cleared) that cannot be sent for
  want of a connection throws `MutationNotQueueableException`, with that failure as its cause,
  instead of the failure itself or an `IOException`. `isNetworkOrConnectivityFailure()` is still
  `true` for it.
- The testing hook's opt-in marker `InternalOfflineTestingApi` is `OfflineInternal`.
- `syncPendingMutations(table: String? = null)` returns a `SyncReport` (what it sent, what the
  server refused, what is still queued) in place of a count, and replaces
  `syncAllPendingMutations()`. It no longer throws a failure that stopped the pass: the report says
  what is still queued and `syncStatus.value.lastSyncError` why. When the library is sending the
  same writes already, it waits for that and then sends what is left.
- `pendingMutationCount` and `pendingMutationCountFlow` each take one `table: String? = null` in
  place of two overloads: calls compile as before, compiled callers must be rebuilt. They, and the
  new queue and rejected-list members, show the current user's writes only, and none while the
  stored session cannot be read (they showed every user's then).
- `selectAsFlow` emits a `QuerySnapshot` in place of `OfflineReadResult`, with `isFromCache`,
  `fetchedAt` and `hasPendingMutations` beside `result` and `refresh`; it is no longer constructed
  by an app.
- `autoSync` is on by default and does what it says: the library sends queued writes by itself
  when the session settles into a user with writes queued, and when the network comes back. Set
  `autoSync = false` to leave those passes to the app; a write queued behind an earlier one is
  sent by itself either way.
- On Android the library's manifest declares `android.permission.ACCESS_NETWORK_STATE`, for its
  connectivity monitor: it appears in the merged manifest of every app that uses the library.
- A test builds its client as an app does, with `install(Offline) { inMemory(clock = …) }` from
  `supabase-offline-kt-testing`; `buildInMemorySupabaseOfflineClient` is gone (see Removed). The
  client no longer runs in the test's own scope: its background work runs on the library's, and a
  failure there is logged, never thrown into the test.
- `ReadStrategy.NetworkFirst`, `CacheFirst` and `CacheOnly` take a `Duration` only: the
  constructors taking milliseconds are gone, and `NetworkFirst(null)` now compiles.
- The Storage file helpers extend `OfflineBucketApi`, the bucket `offline.storage.from(…)` returns,
  in place of supabase-kt's `BucketApi`: on Android `ensureLocalFile` returns a `File` and
  `ensureLocalUri` a `Uri`, on iOS `ensureLocalPath` a `String`, none of them nullable any more, and
  each takes `path` as the bucket's own members do. `localUriIfCached` and `localPathIfCached` are
  `null` only when nothing is cached. On supabase-kt's own bucket they no longer compile, where
  they returned `null`.
- A read, an update or a delete with two top-level filters on one column, such as
  `filter { gte("age", 1); lte("age", 10) }`, throws `IllegalArgumentException` before anything is
  sent or queued. supabase-kt sends only the first of them, so the read showed rows outside the
  window and the write reached them, and a queued write replayed that way later. Write the two as
  one `and { }` group, which sends both.
- A read, an update or a delete whose pattern list (`likeAll`, `likeAny`, `ilikeAll`, `ilikeAny`)
  is empty, or holds a pattern that is empty, `NULL`, edged with white space or holding a brace
  (and none of `,.:()`), throws `IllegalArgumentException` before anything is sent or queued, at
  the top level or in a group. supabase-kt sends such a pattern unquoted, so the server read another
  list: `likeAll("code", listOf(""))` went out as `like(all).{}`, which matches every row.
- On iOS the database and the file cache live in `Library/Application Support` by default
  (`supabase_offline.db` and `supabase_offline/`), not in `Documents`, which an app with file
  sharing on shows in the Files app. A client that finds a database in `Documents` moves it, with
  its unsent writes and its cached files, when it opens; while another client holds it, it stays
  there and that client's claim fails the open, and when both places hold a database the one in
  `Documents` is left and a warning logged.
- The iOS framework carries no Swift-only helpers and no SKIE-generated Swift (see Removed). The
  Swift package reaches it through a bridge of its own.
- On Android, an interface member with a body compiles to a JVM default method only, so the
  `$DefaultImpls` classes are gone (`-jvm-default=no-compatibility`).
- `SyncStatus.lastSyncError` is a `SyncFailedException?`, which is all it ever held: read its
  `httpStatus` and `code` without a cast. The testing artifact's `syncStatus(lastSyncError = …)`
  takes one.
- `syncPendingMutations(table)` no longer sends a table's writes ahead of an earlier write of the
  same user to another table: it stops at the first write with an earlier one still queued
  elsewhere, and `SyncReport.heldBehindOtherTablesCount` counts what it held back. Sync every table
  to send them in order.
- On Swift, the requirement of `OfflineFilterValue` is `offlineFilterText`, where it was
  `filterText`, a name that showed in completion on `String`, `Int`, `Date`, `UUID` and every other
  standard type that conforms, and could collide with one an app declares. A type of your own that
  conforms declares `var offlineFilterText: String`.

### Added

- Built and tested against supabase-kt 3.6 (`supabase-bom` in the version catalog), with Ktor 3.4.3
  and kotlinx.coroutines 1.10.2, the versions it was released with. Not 3.8: supabase-kt 3.7.0 and
  3.8.0 carry a Realtime crash and a protocol-V2 leave defect, fixed upstream but not yet released,
  and this library's pin sets an app's Realtime too; it moves to the first release with both fixes.
  On 3.6 there is no `dryRun()`, `maybeSingle()`, `isdistinct` filter or `purgeCache` (write a dry
  run by adding `Prefer: tx=rollback` to the request), and a Storage object name with `%`, `?` or
  `#` is downloaded as supabase-kt downloads it but never cached, since supabase-kt 3.6 puts it in
  the URL unencoded and can fetch a different object (see the compatibility page). Each minor
  release of this library names exactly one supabase-kt minor: the artifact compiles supabase-kt's
  inline functions into itself and implements its `BucketApi`, which gains members in minor
  releases, so a range cannot be promised. When an app resolves another supabase-kt minor, the
  plugin logs one warning when it is installed, naming both versions, and carries on; a patch
  difference is no warning.
- The `Offline` plugin for supabase-kt: `install(Offline) { … }` and `supabase.offline`, with its
  settings in `Offline.Config`: the two cache sizes, `dataDirectory`, `primaryKeys` (each table's
  key, `id` unless set; `primaryKey(table, columns)`), `responseTrust` (`ResponseTrust`, with the
  earlier rule as `AuthenticatedSessionOnly`), `logLevel`, `networkAvailability`, the app's own
  connectivity signal in place of the library's monitor, and `autoSync`. On Android the plugin
  needs no `Context`: androidx.startup gives it the application's. The configuration is read once,
  when the client is created.
- Offline-first PostgREST reads through supabase-kt. `supabase.offline.from(table).select { }`
  takes supabase-kt's own request builder and a `ReadStrategy` (`NetworkFirst`, `CacheFirst`,
  `CacheOnly` or `NetworkOnly`). `selectAsFlow` emits a `QuerySnapshot` each time the cache or the
  network answers, under a `FlowReadStrategy`.
- `OfflinePostgrestQueryBuilder` has supabase-kt's verbs and overloads under its names (`insert`,
  `upsert` and `update` of JSON, of a value and of a list, the `update { set(…) }` DSL, `delete`)
  and its `table`, `schema` and `postgrest`; the offline choice is a named parameter before the
  trailing lambda. Most calls written against supabase-kt compile with only the receiver changed
  and `io.github.nesfeder.supabase.offline.offline` imported. These need an edit: a write whose
  result is read (`resultOrNull()`); a suspending call or an early `return` inside a request
  lambda, since the verbs are not `inline` (make the call before the verb); a request lambda passed
  inside the parentheses (name it `request = { … }` or put it after them); the typed `rpc` (import
  `io.github.nesfeder.supabase.offline.rpc` in place of supabase-kt's); and realtime's
  `selectAsFlow` and bucket management, which stay on `supabase.from` and `supabase.storage`.
  Typed writes encode through `postgrest.serializer`, as supabase-kt's do.
- `MutationOutcome.Queued.pendingMutation` (`PendingMutation`: id, table, `MutationOperation`
  `INSERT`, `UPSERT`, `UPDATE` or `DELETE`, createdAt, row keys, body) and
  `MutationOutcome.isQueued`.
- `rpc` on the plugin, network only, with a typed overload; `offline.storage.from(bucket)` and
  `offline.storage[bucket]`; a typed `applyServerRecord`, whose `primaryKeys` default to the table's
  configured key. `applyServerRecord` and `applyServerDelete` take a `schema`, for a table of a
  schema other than the client's default.
- `OfflineException`, open for new subclasses, with `CacheMissException`,
  `MutationNotQueueableException` and `NetworkDisabledException`; `httpStatusOrNull()`, which reads
  supabase-kt's and Ktor's errors, Storage's included.
- The opt-in markers `OfflineExperimental` and `OfflineInternal`, both at error level. API under
  either is left out of the API records and the compatibility checks: experimental API may change
  in a minor release with no changelog line.
- Writes that work offline. `insert`, `update`, `upsert` and `delete`, and their JSON forms, return
  a `MutationOutcome`: `Applied` with the server's response, or `Queued` when the write was recorded
  in the mutation queue to be sent later (`MutationStrategy.QueueIfOffline`, the default). Queued
  writes show in cached reads until they are sent.
- The mutation queue, observed and steered: `pendingMutations(table?)` (a flow of
  `PendingMutation`), `pendingMutationCount(table?)` and `pendingMutationCountFlow(table?)`, and
  `discardPendingMutation(id)`, the way out of a write the server keeps failing (it waits for a
  send in flight, and what waited behind the write is then sent). `syncPendingMutations(table?)`
  replays the current user's queued writes in the order they were made and returns a `SyncReport`
  (`syncedCount`, `rejected`, `remainingCount`, and `alreadyOnServerCount` for an insert whose
  earlier send had in fact arrived); it returns promptly when offline.
- Refused writes: `rejectedMutations()`, a flow of the current user's persisted rejected list
  (`RejectedMutation`, with the server's `ServerError` and `isRetryable`), with
  `dismissRejectedMutation(id)` and `retryRejectedMutation(id)`; one user can never see, dismiss or
  retry another's. A write sent at once that the server refuses still throws the server's error
  to its caller: the list is for refusals found on replay.
- `syncStatus`, a `StateFlow<SyncStatus>`: `network` (`NetworkStatus`: `UNKNOWN`, `ONLINE`,
  `OFFLINE`, `DISABLED`), the current user's pending and rejected counts, `isSyncing`,
  `lastSyncedAt`, and `lastSyncError`, a `SyncFailedException` that carries the status and code
  but no row, filter value or token. The network is current as soon as the switch below returns;
  the two counts follow the queue shortly after each change, read together. It never throws: at
  `close()` it takes one last value, with `isSyncing` false, and changes no more.
- `disableNetwork()` and `enableNetwork()`: while access is off, nothing is sent, reads are
  answered from the cache, writes that may queue are queued, reactive reads are `Paused`, and
  requests that must reach the server throw `NetworkDisabledException`, which
  `isNetworkOrConnectivityFailure()` counts as offline. A new client starts with access on.
- A connectivity monitor, on by default: Android's `ConnectivityManager` (a validated network
  with internet) and the iOS path monitor. It is a hint, never a gate: it decides when the library
  tries by itself and what `syncStatus.network` says, and a request the app makes is tried
  whatever it says. It starts with the client and stops with `close()`.
- `RefreshState.Paused`: a refresh that cannot reach a network known to be down waits for it,
  instead of reading as `Stalled`, and runs again when the network comes back.
- Provenance on one-shot reads: `PostgrestResult.isFromCache`, `fetchedAt` and
  `hasPendingMutations`, as `QuerySnapshot` has them, carried in response headers PostgREST never
  sends; a result from supabase-kt itself has none of them set.
- Storage downloads cached on the device. `storage.from(bucket)` returns an `OfflineBucketApi` with
  `cachedFile` and `ensureCachedFile`, and Android and iOS functions return the cached file's
  location.
- `applyServerRecord` and `applyServerDelete` apply a Realtime change to the cached reads.
- Size limits for the cached rows and the cached files, in `Offline.Config`.
- `clearLocalData()` deletes the cached rows, the queued mutations, which are never sent, and the
  cached files; `close()` releases the plugin's database.
- `buildInMemorySupabaseOfflineClient` in the `supabase-offline-kt-testing` artifact: a client that
  keeps everything in memory, for tests.
- A record of the public API of both artifacts, checked on every change, so the API cannot change
  without a reviewer seeing it.
- `supabase-offline-kt-testing`: `Offline.Config.inMemory(clock)` keeps the plugin's cache,
  mutation queue and Storage file cache in memory, writing nothing to disk, with every other setting
  applied as in an app; the clock is the time the client sees. It watches no connectivity monitor,
  so its network status is `UNKNOWN` until a test sets `networkAvailability` or calls
  `disableNetwork()`, which is how a test takes it offline. Factories build the value types whose
  constructors are internal, for an app's own test doubles: `appliedOutcome`, `queuedOutcome`,
  `cachedFile`, `pendingMutation`, `stalledRefresh`, `querySnapshot`, `syncReport`, `syncStatus`,
  `rejectedMutation`, `serverError`, and the exceptions `cacheMissException`,
  `mutationNotQueueableException`, `networkDisabledException` and `syncFailedException`.
- Published on Maven Central as `io.github.nesfeder.supabase-offline:supabase-offline-kt` and
  `io.github.nesfeder.supabase-offline:supabase-offline-kt-testing`, for Android, `iosArm64` and
  `iosSimulatorArm64`, each with its sources, an HTML API reference and a signature on every file.
- The Swift package `SupabaseOffline` (and `SupabaseOfflineTesting` for tests), published by each
  release to `https://github.com/nesfeder/supabase-offline-swift` under the same version. It needs
  iOS 16 and Xcode 26 or later, and runs on arm64 simulators only: a simulator build that includes
  x86_64 (such as Any iOS Simulator Device) fails to link, so build for a specific simulator, or
  with `ARCHS=arm64`. Its framework is an asset of this repository's GitHub Release, and carries a
  privacy manifest, as the `SupabaseOffline` target does, declaring the file-timestamp and
  user-defaults APIs it references. It is not code-signed in 0.1.0.
- `SyncReport.heldBehindOtherTablesCount`, and the testing artifact's
  `syncReport(heldBehindOtherTablesCount = …)`, defaulting to `0`.
- The Swift package's API reference, built with DocC and published with the documentation site
  under `/swift/`, with articles on getting started, coming from supabase-swift, offline behavior,
  handling errors and testing an app; every code sample in it is compiled with the package's tests.
  The package's public interface is recorded in `swift/api/`, and a change to it is rated as a
  change to the Kotlin API is.

### Changed

- Every public `suspend` function declares `@Throws(Throwable::class)`, so a framework that exports
  the library hands Swift a failure as an error instead of ending the process. `close()`, which
  overrides supabase-kt's, and the `syncStatus` property cannot declare it.
- A reactive read's refresh that fails for a reason other than the network is logged at WARNING,
  by type, once for each kind of failure in a row; it was retried silently.
- A reactive read's refresh that waits for the session to settle (an answer it may not cache yet)
  reports `RefreshState.Refreshing`, never `Stalled` with an exception of the library's own.
- Expected offline conditions on the read side log at INFO: a `CacheOnly` read with nothing cached,
  a cold `NetworkFirst` read offline, a read answered from the cache because the network failed,
  and a download offline. A download the server refuses logs at WARNING.
- A `NetworkFirstThenCache` read fetches again when its cached rows may no longer be the server's:
  after a write the cache could not follow, a change of the cache's owner, or `clearLocalData()`.
- okio is no longer an `api` dependency of the library: no public declaration names one of its
  types. It still reaches an app, at the same version, through supabase-kt's Auth.
- The Android artifact depends on `androidx.startup:startup-runtime` itself, at the version
  supabase-kt's Auth already brings, instead of reaching it through Auth.
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
- `single()`, `maybeSingle()` (from supabase-kt 3.8), `head`, counts (a list with a count included),
  `csv()`, `geojson()`, `stripNulls()` and `explain()` are never cached: they go to the network, and
  under `CacheOnly` throw `CacheMissException` without sending anything.
- A table in another schema is cached apart. A write to it with the default strategy is sent at
  once and never queued; offline it throws `MutationNotQueueableException`.
- Typed writes (`insert(value)`, `upsert(values)`, `update(value)`) encode through the app's own
  PostgREST serializer, so a custom `Json`, contextual serializers and `encodeDefaults` reach the
  server, and the mutation queue, as supabase-kt sends them; they used the library's own `Json`.
- Offline, a `NetworkFirst` read with a warm cache answers at once instead of after supabase-kt's
  retries; `NetworkFirstThenCache` with a warm cache shows it as `Stalled` (`Paused` when the
  network is known to be down) and keeps fetching instead of failing; a `CacheFirstThenNetwork`
  refresh reports `Stalled` after about 3.6 s, or `Paused` at once when the network is known to be
  down.
- After a sync pass stopped on a server's `Retry-After`, the library's own next pass waits at least
  that long (at most an hour); a sync the app asks for is sent at once.
- The testing artifact pins the library to its own version with a strict constraint in its
  published metadata. Beside a newer library, an older testing artifact fails dependency resolution
  instead of a test failing with a missing method; a newer testing artifact raises the library in
  the test classpath to its own version without a word. Keep both on one version.
- A slow collector of a `CacheFirstThenNetwork` read is handed the latest snapshot, not each one in
  turn, and never holds back the query's other collectors.
- The request lambda passed to a read or a write may run more than once, so it must configure the
  same request every time.
- The on-device database is schema version 1, the one this release ships: each queued write keeps
  its owner, its replay order, its schema-cache refusals and whether a send of it may have reached
  the server in its own row, and each cached query the replay order it has caught up with.
- Replayed writes are kept for the overlay until every cached query of their table has been
  fetched again since, or until they fall beyond the newest 1,000 of the table, whatever the device
  clock says; a table nothing caches keeps none.
- A write PostgREST refuses because its schema cache does not know the table or a column yet
  (`PGRST205`, `PGRST204`), as happens just after a migration, stays queued, online as well as
  offline, and its user's later writes wait behind it. It is rejected only at the tenth such
  refusal, each counted at least 30 seconds after the last while the app runs (the first after it
  starts counts at once), so a table that does not exist, or a misspelled column, cannot hold the
  queue for ever.
- `close()` lets a replay pass finish the request it has on the wire, and record the answer,
  before it stops, so the next client does not send the write again. A request the server does
  not answer holds `close()` for up to its 10-second bound.
- The library's internal code is renamed and grouped into packages under
  `io.github.nesfeder.supabase.offline.internal`. No public declaration, stored name or stored
  format changed. The exported Room schema records one field's new Kotlin name, `cacheKey`; its
  column is still `signature`, and the schema's identity hash is unchanged.
- The engine's internal store is the set of components the client builds itself. No public
  declaration, stored name or stored format changed: the exported Room schema and its identity hash
  are unchanged.
- `syncStatus` follows the queue from the moment the client opens, so its counts include what an
  earlier run left queued before the app first reads it; read for the first time after `close()`,
  it holds the last value it had.
- A reconnection, or the session settling, ends at once the wait a failed pass left before the
  library's next pass, and starts it again from one second; a server's `Retry-After` is still
  waited out. While the monitor says offline, the library makes one failed attempt by itself per
  offline period and grows no wait. A wrong monitor costs nothing while requests reach the server;
  after one fails for want of a connection, queued writes wait for the monitor, the session or the
  app's sync. With `autoSync` on, a client without Auth sends what an earlier run left queued when
  it opens. An app's connectivity signal that ends while saying offline counts as unknown, as one
  that fails does.
- Keeping the rows `clearLocalData()` deleted out of the database's files is best effort, and
  never holds the clear: it waits at most a second for an open read holding the write-ahead log,
  then empties the log in the background within a few seconds of the read ending, and once more at
  `close()`. It waited for up to about a minute, holding every send, and then only logged a
  warning.
- Being offline is logged at INFO at most: a write the queue cannot hold that fails for want of a
  connection was logged at ERROR, and a replay pass stopped by the network at WARNING. A write that
  asked for a rollback (`Prefer: tx=rollback`, what a dry run sends) and was committed, as hosted
  projects do, is logged once per client at WARNING.
- `close()` never throws: a failure closing the database is logged, and the database is released.

### Removed

- The ways in before the plugin, and the names the plugin's API replaced, with nothing deprecated
  left behind: `createSupabaseOfflineClient` (Android and iOS) and `SupabaseOfflineConfig` (install
  the plugin and use `supabase.offline`; it opens the same database, on iOS once it has moved from
  `Documents` to `Library/Application Support`, so the cache and unsent writes carry over); the
  `SupabaseOfflineClient` type (`Offline`); `insertJson`, `upsertJson` and `updateJson` (`insert`,
  `upsert` and `update`); `insertWith`, `upsertWith` and `updateWith`, and the reified `insert`,
  `upsert` and `update` extensions (the typed members, which encode through `postgrest.serializer`);
  `storage(bucket)` (`storage.from(bucket)`); `clearAllCache()` (`clearLocalData()`); `supabase`
  (`supabaseClient`); `OfflineLogger` and `OfflineLogLevel`, with the iOS factory's `logger` and
  `minLogLevel` (supabase-kt's logging, at `logLevel`); `syncAllPendingMutations()` and
  `syncPendingMutations(table): Int` (`syncPendingMutations(table?): SyncReport`); the two-overload
  `pendingMutationCount()` / `pendingMutationCount(table)` and `pendingMutationCountFlow()` /
  `pendingMutationCountFlow(table)` (one member each, `table: String? = null`); and
  `OfflineReadResult` (`QuerySnapshot`).
- `buildInMemorySupabaseOfflineClient` (`install(Offline) { inMemory() }`), and the millisecond
  constructors of `ReadStrategy.NetworkFirst`, `CacheFirst` and `CacheOnly` (pass `maxAge` as a
  `Duration`).
- What the iOS framework carried for the old Swift layer: `SupabaseFactory` and its
  `offlineClientKt`, `SwiftJson`, the `encodeToJsonString`, `encodeToJsonElement`,
  `encodeListToJsonString` and `encodeListToJsonArray` helpers, `singleElementArray`, the fifteen
  `…Kt` query and mutation helpers, the three `applySwiftOptions`, `ensureLocalFileUrl` and
  `localFileUrlIfCached` (`ensureLocalPath` and `localPathIfCached` stay), and SKIE with the Swift
  it generated. Nothing has consumed the framework, so no published user is affected; the Swift
  package will be built on a bridge module of its own.
- `NumericDeltaColumn`, and the `deltaColumns` parameter of the update verbs. A queued write
  replays exactly what was sent; a value that several devices change concurrently, such as a
  counter or a running total, is computed on the server from the facts clients write.

### Fixed

- A write is a dry run when any of its `Prefer` values carries `tx=rollback`, not only the first:
  supabase-kt writes its own `Prefer` first and appends one the request adds, so a write that added
  `Prefer: tx=rollback` itself was queued offline and would have been replayed, and committed, as a
  real write.
- Rows a write returns (`select()`) are cached with the columns the caller wrote, matched by key;
  an update that asks for rows back changes only the rows returned.
- A write the cache cannot follow marks the cached lists it may reach stale, and they are fetched
  again, instead of showing values the server no longer holds.
- A cached list a live reader shows is never evicted, for up to 500 live readers.
- A patched list keeps the time it was fetched, and a list made only of local writes is stale.
- A queued write is sent with its owner's session token or not at all: a user signing in while
  another's writes replay no longer has them sent as theirs.
- A write that may queue, made when the session's token has expired and cannot be refreshed
  (offline, or refused by the Auth server), is queued instead of throwing `TokenExpiredException`,
  and replays as never sent once the session is good again.
- A replay refused because its stored filter can no longer be read says so, instead of saying the
  write has no filter.
- The in-memory client of the testing artifact is safe across threads: a live read while another
  thread writes no longer fails with `ConcurrentModificationException`.
- Each emission of a live read is one view of the cache, the queue and its refresh. An insert the
  server applied no longer shows twice for an emission (a list keyed by id crashed on it), a
  replayed insert no longer drops out of a list opened right after the sync, and a
  `CacheFirstThenNetwork` read no longer reports `Idle` one emission before the rows its refresh
  fetched.
- A channel download Storage answers with an error (`ensureCachedFile`, the offline bucket's
  `downloadAuthenticated` and `downloadPublic` to a channel, and supabase-kt's `…To` helpers through
  it) throws what the bytes download throws. It cached the error body as the file and served it
  from then on. A redirect from https to plain http is refused too, even where Ktor's
  `allowHttpsDowngrade` lets the bytes download follow it.
- A live read fetches again after `clearLocalData()`, as after a change of owner, instead of
  showing the emptied cache until something else fetched; a refresh request made while a read's
  refresh ran is no longer lost.
- A live read fetches again after a change of owner whose last step, deleting the file cache's
  folders, failed: it showed the emptied cache until something else fetched, and the error logged
  said the cache had not been dropped.
- A read made while the stored session restores waits for it at most 10 seconds instead of waiting
  on a session store that hangs. Past that the cache is not served and the read goes out on the anon
  key: a one-shot read returns that answer uncached, which under row-level security can hold fewer
  rows, or none, and a live read shows none of the cached rows, only the queued writes it admits
  (`Refreshing`), until the session settles.
- A read the cache cannot answer, made while the connectivity monitor says the network is down, is
  sent once instead of after supabase-kt's retries.
- One failed read of the database no longer turns automatic behavior off for the client's life,
  or fails a write the server answered: a later sign-in sends the user's queued writes, the sync
  status follows the queue again, and a write the server applied (or refused), or a discard that
  happened, is reported as it happened rather than as the failed read.
- A sync that `clearLocalData()` cut short reports the writes it had sent before.
- A write made as a client without Auth opens, with `autoSync` on, is sent at once and returns
  `Applied`. The pass that client starts for writes an earlier run left could find the new write in
  the queue and send it itself, so the write returned `Queued` with the network up and nothing
  ahead of it; it was still sent once, and never twice.
- While a stored session restores, reads and writes read it once between them, not once each.
- A Storage call naming an object whose name holds a `%` that does not begin a percent-escape
  (`100%.png`) throws `IllegalArgumentException` before anything is sent. supabase-kt 3.6 puts the
  name in the URL as written, the HTTP client cannot build that URL, and the failure read as a
  connectivity failure, so an app took itself to be offline.
- A Storage "not found" answered while the session restores, or after its refresh failed, keeps
  the user's cached copy and is answered from it. The download went out on the anon key, and
  Storage answers an object that key may not read as "not found", so the copy was dropped and an
  app opening a private file at launch lost it until the next download.
- A client that opens with `dataDirectory` newly set, and moves the database from the default
  location, moves the downloaded files with it; one that cannot move is deleted, to be downloaded
  again. They stayed in the default directory, where `clearLocalData()` no longer reached them and,
  on Android, Auto Backup still included them.
- `SyncStatus.lastSyncError` for a pass stopped on the device, by neither the server nor the
  network, says so in a fixed sentence instead of naming the failure's class. On iOS a pass stopped
  by a request header the HTTP client refuses now says so, and what to check, instead of that
  general sentence.
