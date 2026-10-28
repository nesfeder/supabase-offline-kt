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

### Added

- `QuerySnapshot.pendingRowKeys` names the rows a queued write added, changed or removed: the
  primary key of each, worked out in the pass that lays the writes over the rows, so a per-row
  pending mark never shows before its row or stays after the write is sent. A row a write removed
  keeps its key, and a row without its whole key adds none. A one-shot read's result has the same
  `pendingRowKeys`, and the Swift package's `QuerySnapshot` and `OfflineResponse` have it as a set
  of key dictionaries. The testing artifacts' `querySnapshot` and `cachedResult` (in Swift,
  `querySnapshot`, `cachedResponse` and `offlineResponse`) have overloads that take the keys.
- `Offline.rejectedMutationList()` and `Offline.conflictedMutationList()` read the rejected and
  conflicted lists once, the list `rejectedMutations()` and `conflictedMutations()` emit first, as
  `pendingMutationCount()` reads the queue's count; the Swift package has the same two calls.
- On Android, `localFileIfCached(path)` gives the cached copy of an object as a `File`, or `null`
  when none is cached, beside `localUriIfCached(path)`, which gives it as a `Uri`.
- `OfflineClientClosedException`, the error every call on a closed client throws, and a call the
  closing stopped. It is an `IllegalStateException` with the message the closed error always had,
  "This offline client is closed", so an existing `catch (e: IllegalStateException)` or a check of
  the message keeps working; match the type instead. The testing artifact's
  `offlineClientClosedException()` builds one.
- `SyncReport.stopReason` says why a sync returned when it did, and so why writes remain: it
  completed, the network could not be reached, the server failed in a way that may pass (with its
  `SyncFailedException`), a failure on the device stopped it, the session was not ready or changed
  user, network access was off, writes wait behind another table, or local data was cleared. The
  set is open, so a `when` over it keeps an `else` branch. The Swift package's `SyncReport` has the
  same `stopReason`, a `SyncStopReason` with an extensible `kind` and the failure as `error`, and
  the testing artifacts build one with `serverFailureStop`, `localFailureStop` and a `syncReport`
  overload in Kotlin, and `syncStopReason(kind:error:)` and a `syncReport` overload in Swift.
- `identityFromAccessToken` in `install(Offline)`, and the same setting in the Swift package's
  `Config`: a client without the Auth plugin tells its users apart by the `sub` claim of the token
  its own provider returns, read and never verified. Each user's queued writes are then sent only
  with that user's token, the caches belong to one user at a time and are dropped when the token
  names another, and the Realtime channel is joined again for the new user, as with Auth, so a Swift
  app no longer needs its own sign-out routine. It is off by default, which changes nothing.
- `offline.storage.clearFileCache()` deletes every downloaded Storage file and its record, in every
  bucket, and leaves the mutation queue, the cached query results and the rejected and conflicted
  lists, so an app can free the device's storage without dropping writes waiting to sync. A
  download in flight is handed to its caller but not kept, as across `clearLocalData()`. The Swift
  package has it as `storage.clearFileCache()`.
- A bucket's `evictCachedFile(path)` deletes one object's cached copies, the untransformed one and
  every image transform, and `evictCachedFile(path) { … }` one transform's copy alone; nothing is
  sent. The Swift package has `evictCachedFile(path:)` and `evictCachedFile(path:transform:)`.
- `cachedFile(path) { … }` returns the cached copy of one image transform, the one a download with
  the same `transform { … }` keeps, so a cached thumbnail can be handed to an image loader as a
  file. `localUriIfCached(path) { … }` on Android and `localPathIfCached(path) { … }` on iOS give it
  in the platform's terms, and the Swift package has `cachedFile(path:transform:)`.
- A Storage upload can wait in the mutation queue with the row writes around it:
  `upload(path, data, MutationStrategy.QueueIfOffline)` on an offline bucket keeps the file's bytes
  on the device and returns `UploadOutcome.Queued` when it cannot be sent at once, and a later pass
  sends it in the order it was made, so a row written after it that names the file is sent after the
  file. It survives a restart, belongs to the user who made it, is refused onto the rejected list
  with its bytes kept for a retry, and goes with `clearLocalData()`; while it waits, `cachedFile`,
  `ensureCachedFile` and the platform helpers return its bytes on the device, and once Storage has
  it they become the cached copy. An upload whose answer was lost is sent again, and its refusal
  counts as stored only when the object at its path is verifiably its bytes.
  `offline.storage.pendingUploads()`, `rejectedUploads()` and `rejectedUploadList()` list them,
  `SyncReport.rejectedUploads` reports a refusal, and `discardPendingMutation`,
  `dismissRejectedMutation` and `retryRejectedMutation` take an upload's id. The Swift package has
  the same calls, and both testing artifacts build the new values.
- `RejectedUpload.localPath` is a refused upload's kept file on the device, for the app to show
  what was refused: `cachedFile` and `ensureCachedFile` never answer a refused upload, since
  Storage may hold other bytes at its path. The Swift package's `RejectedUpload` has it too, and
  each testing artifact's `rejectedUpload` has an overload that takes it.

### Changed

- When an object and a folder of the same name are both cached in one bucket, or two objects whose
  names differ only in case on a case-insensitive volume, the one downloaded second is stored under
  `storage-hashed/`, in the file named by a hash of its key, and its row's `localPath` records that
  file. Files and rows already on a device stay as they are and are read as before. An earlier build
  that finds such a row drops it when the object is next read, downloads the object again, and
  leaves the file until the file cache or local data is cleared. Until that read, an earlier build
  on iOS stops at such a row in every eviction pass, so its size bound evicts nothing while a row
  like `album/cover.png` beside the file `album` is there.
- The query cache's eviction pass runs in the background after a response is written, where it ran
  before the read returned, and starts when a running count of the cached lists' length passes the
  bound, where every response of 4 KiB or more, and every 32nd response, summed every cached list
  first; a pass reads each list's length once and deletes the lists it chose in one transaction.
- The testing artifact's `InMemoryStore` keeps an in-memory client's local data for the next
  client built over it: a client built with `inMemory(clock, store)` or
  `inMemory(clock, realtime, store)` after an earlier one closed finds its queued writes, which it
  replays, its rejected and conflicted lists, and its cached queries and files, so a test can
  simulate an app killed and relaunched. A client built over a store another client holds fails
  with `IllegalStateException`. The Swift testing product has the same `InMemoryStore` and
  `inMemory(…, store:)` overloads.
- `cachedResult(data, supabase, fetchedAt, hasPendingMutations)` in the testing artifact builds a
  `PostgrestResult` that `isFromCache`, `fetchedAt` and `hasPendingMutations` read as given, for a
  fake of an app's own repository. The Swift testing product has
  `cachedResponse(value:data:count:fetchedAt:hasPendingMutations:)`.
- `FakeRealtime.joinCount`, in Kotlin and Swift, counts the joins the client has handled: once it
  moves, the client has reported the channel subscribed and asked for the named tables to be read
  again, so a test that waits for it reports a change after the join instead of racing it.

### Changed

- `FakeRealtime` applies the table's `RealtimeFilter` as the server does: an insert or an update
  whose record does not meet it is dropped and counted in `droppedReportCount`, and a delete
  reaches the client whatever the filter. Before, every report to a followed table reached the
  client, so a test could see a change a device never receives.
- `realtimeTableStatus` says where each table named in `realtime { }` stands, keyed and ordered as
  named, beside `realtimeStatus`; the Swift package reads it as a `RealtimeTableStatusSequence` of
  `[RealtimeTable: RealtimeStatus]`. `FakeRealtime.failTable` refuses one table in a test, in both
  testing artifacts.

### Changed

- Each table named in `realtime { }` is followed on a channel of its own, so a table the server
  refuses, such as one whose filter names a column a migration renamed, stalls and is joined again
  alone while the others keep receiving, and each join reads only its own table again. Before, one
  refused binding ended the shared channel: every named table lost its changes, and each retry read
  every table again. `realtimeStatus` sums the tables up: `Stalled` while any table is, with the
  first one's cause, otherwise `Subscribing` while any join is in flight, and `Subscribed` once
  every table is joined. With supabase-kt's default `maxAttempts`, a refusal still drops the other
  tables' channels for a moment, as their socket is closed to end the rejoin supabase-kt runs in
  its read loop; `install(Realtime) { maxAttempts = 1 }` keeps them joined throughout.
  `FakeRealtime.failChannel` and `dropChannel` end every table's channel.

- Concurrent `ensureCachedFile` calls for one uncached object share one download instead of each
  fetching the whole object, and a bytes or channel download of an object such a call is fetching
  waits for it and reads its file. A call canceled while another still waits for that download
  returns at once, and the download goes on for the other; the last caller to cancel still returns
  only once its partial file is deleted.
- A Storage download of an object whose cached copy has an `ETag` asks the server whether it
  changed, with `If-None-Match`: a "not modified" answer serves the cached copy without sending the
  body again and moves its `fetchedAt` to the time of the answer, where every download fetched the
  whole object before. The Swift package's downloads do the same. Each cached file's row records
  the `ETag` it was sent under, in the `eTag` column, which was always empty; a file an earlier
  version cached has none, so its next download fetches it whole once and records it.
- The rejected list keeps a guarded write's base in each guarded entry, under two new keys
  (`baseVersion`, `baseJson`), so a retry replays guarded. A list an earlier build wrote reads back
  without them, and a retry of such an entry replays unguarded, as it did there.
- An object whose bucket id or path has a segment that starts with a letter and a colon
  (`u:42/a.png`) is stored under `storage-hashed/`, like every other name that cannot be stored
  verbatim. Nothing was cached under its plain path before, so no file already on a device moves.
- An `in` element, or a comparison value inside an `or { }` or `and { }` group, that holds `"` or
  `\` and none of `,.:()` is refused with `IllegalArgumentException` before anything is sent or
  queued, in a read, an update or a delete, and by the Swift package's core: supabase-kt escapes
  such a value but does not quote it, so the server read the escapes as the value and the request
  matched nothing, while a queued row with that value showed offline and vanished on the next
  refresh. A write queued by 0.2.0 with such an `in` element is moved to the rejected list when
  it comes up for sending, with a message naming the column, instead of being sent and dropped as
  applied. Filter on such a value with a single top-level filter, which sends it as written.
- `RealtimeFilter` refuses, when made, an `in` without a non-empty list and another operator with
  a collection or an array, and the Swift package's core refuses a filter that sets both forms or
  the wrong one: such a filter was sent as `owner=in.<id>` or `id=eq.[1]`, the server refused the
  join, and the channel stalled and retried forever. Pass a `List` for `in` and a single value for
  every other operator.
- A `QueueIfOffline` write sent at once stays queued, instead of being thrown and dropped from the
  queue, when the server answers `408`, `429`, `502`, `504`, `522` or `524`, or `401` with
  PostgREST's `PGRST301` or `PGRST303` (a token the server holds expired while the device's clock
  says it is fresh), as a replay of the same write always has; a later pass sends it. A caller
  that relied on the exception to show an error now gets `Queued`. A `500` from the database is
  still thrown.
- `single()` is cached as its list, one row long, under the key of the list read with the same
  query, through the guards a list response passes. Offline, or when the network fails,
  `NetworkFirst`, `CacheFirst` and `CacheOnly` answer it from that list when it holds exactly one
  row, with the queued writes laid over it; no row or several fail as before. It was never cached,
  so a detail screen had nothing offline. `maybeSingle()` on supabase-kt 3.8 is cached the same
  way, and offline answers only its one row, never `null`.
- The mutation queue can hold a Storage upload: a row of a new kind beside the row writes, with
  its bytes in a `storage-uploads` directory beside the file cache, excluded from iOS backups as the
  database is and moved with the database into a configured `dataDirectory`. Data already on a
  device is untouched: nothing moves, and no stored format changes. An earlier version that opens a
  queue holding an upload refuses it onto its rejected list.
- `SyncStatus.pendingMutationCount` and `rejectedMutationCount`, `pendingMutationCount()` with no
  table, and a sync's `syncedCount`, `remainingCount` and `alreadyOnServerCount` count a queued
  upload as a write; `pendingMutations()` and `rejectedMutations()` still list row writes only. An
  app that queues no upload sees the counts it saw.

### Deprecated

### Removed

### Fixed

- An object and a folder of the same name in one bucket, such as `album` and `album/cover.png`, can
  both be cached now. On disk they would need one name to be both a file and a folder, so the one
  downloaded second was never cached: `ensureCachedFile` threw for it every time, a channel download
  of it could fetch it twice, and it was not served offline.
- On a case-insensitive volume, such as the iOS simulator's or a Mac's, two objects whose names
  differ only in case, such as `photo` and `PHOTO`, or whose buckets' names do, shared one cached
  file, so the one downloaded second replaced the other's bytes, and a write to one deleted the
  other's copy. The second is now stored apart, and each object serves its own.
- With Room 2.8.5 or later, which an app can resolve itself, `pendingMutations()` and
  `pendingMutationCountFlow()` called after `close()` threw Room's `IllegalStateException`
  from the call, where a `catch` on the flow could not see it. Each now returns a flow that
  fails with `OfflineClientClosedException` when collected, as every flow made after `close()`
  does, on every Room version.
- In the Swift package, a call that failed for a reason of its own while the client closed threw
  `clientClosed`: any `IllegalStateException` or cancellation met during the close read as the
  closed client, such as the failure of a download whose local data was cleared. It now keeps its
  own code, and only the closed client's error is `clientClosed`.
- A sync pass that a user switch stopped left `SyncStatus.lastSyncError` holding an earlier pass's
  failure, which was about the previous user's writes; it is now cleared then. A pass stopped
  because the session is not ready, network access is off or a write waits behind another table
  still leaves it as it was, since no newer answer came for those writes.
- A reactive read's refresh asks again, on its retry schedule, for as long as its answers are
  refused as older than a change to the table, and reads `Refreshing` meanwhile; a
  `NetworkFirstThenCache` read whose first fetch is refused that way stays `Refreshing` too. Before,
  the second refusal settled the read on the rows it had and reported `Idle`, so a busy table, or a
  reconnect with queued writes, could leave a list stale for good.
- A burst of changes on a followed table, as one statement over a few hundred visible rows sends,
  overflowed the two buffers between the Realtime plugin and the cache, the plugin's own and the
  library's, past about 130 waiting changes, and the newest were dropped without a log or a stale
  mark, so a row could keep an older value until the next join or fetch; every change is now kept,
  and a backlog of more than 5,000 waiting changes of one table is discarded as a whole, with that
  table read again as on a join and one warning logged.
- Each change a followed table received was applied as its own pass over the table's cached
  queries, so a burst ran far behind the connection; the changes waiting for a table are now
  applied as one pass, in the order the server sent them.
- A one-shot read made while a stored session's token refresh has failed waits for the session,
  as it does while one restores. supabase-kt sends the anon key meanwhile, and tries the refresh
  again every ten seconds by default; the read returned the anon key's answer, empty under
  row-level security, as the server's. When the session does not settle within the wait, a
  `NetworkFirst` read with the query cached, or a `CacheFirst` read of a list marked stale, serves
  the cached rows rather than that answer. A `NetworkFirstThenCache` read waits the same way
  before its first emission. A read answered after the session settled asks again at once.
- A read of a shape the cache cannot hold (`single()`, `maybeSingle()`, `head`, a `count`, …)
  waits for a session that is restoring, or whose refresh failed, before it is sent. It went out
  at once on the anon key, where a detail screen's `single()` failed with `PGRST116` and a `count`
  read 0 at cold start.
- A cached list whose shape record the library could not read (one another build wrote, or one
  that did not decode) admitted every row of its table into the list, for good; the record is now
  replaced by the one the next read records.
- A write patched into a table while the cache was being trimmed could bring back a list the
  trimming had just removed; a patch now changes only the lists still there. A read of the cache
  that overlapped `clearLocalData()` could leave a record of its query's filter values behind; such
  a record is now written only beside the rows it describes, and never after a clear.
- A `2xx` answer that was not a list of rows (a captive portal's sign-in page, a proxy's block page,
  a self-hosted server's fallback page) replaced the cached list, and every offline read of the
  query then failed to decode it. Such an answer is no longer written: a `NetworkFirst` or
  `CacheFirst` read with the query cached within its `maxAge` is served the cached rows, and a
  reactive read's refresh asks again, `Stalled` by that cause after its fast retries. A blank
  answer is an empty list, served and stored as `[]`.
- Two different queries could share one cache key when a filter value held `&`, `=` or `|`, and be
  served each other's rows offline. Such a query's key now ends in a digest of its parts; every
  other key is unchanged. Such a query's rows cached before the upgrade are not found; it is fetched
  again once, and the old rows stay until evicted.
- A rejected write that was guarded by a version column keeps its base when it is retried: the
  retry checks the server's row as the first send did, so a row another device changed meanwhile is
  a conflict for the resolver, not an overwrite.
- A replayed write's returned row moves the base of the writes queued behind it by the same user
  only. On a shared device, another user's queued write to the same row keeps the version its user
  saw, so its replay meets the change as a conflict.
- A write parked on the conflicted list leaves the cached row showing the server's values for the
  columns the replay read, instead of the values the device last read. The row keeps the version
  the device saw, as it now does after the resolver's `KeepServer` too, so a later write to it
  still meets the move instead of applying over another device's change to a column the replay
  did not read.
- `KeepServer` on a listed entry drops a gone row from the cache, or lays the columns the entry
  recorded into the cached row while that row is still as the device saw it before the write, as
  the resolver's own `KeepServer` does; the lists holding the row are then fetched again.
- A sync pass canceled by anything but `clearLocalData()` (a cancellation the transport threw) is
  no longer reported as a cleared pass with nothing left queued. It reports a failure on the
  device, the writes stay queued, and the library's own passes go on.
- A download of a Storage object that was still on the wire when an upload, update, delete, move,
  copy or resumable upload of the same object landed no longer becomes the cached copy: the caller
  gets the bytes it asked for, and the next read downloads the new ones. `ensureCachedFile`
  downloads again once when the object outran it, and throws `IllegalStateException` when that
  happens twice, as it does after a clear.
- A download whose `httpOverride`, or a header set on the bucket, asks for part of the object (a
  `Range` or `If-Range` header) or none of it (a `HEAD`) is no longer cached as the whole object,
  and offline is no longer answered from the whole copy. `ensureCachedFile` on a bucket with such a
  header throws `IllegalStateException` instead of caching the fragment.
- A cached file whose size is not the one its row recorded, as a power loss right after a download
  can leave, is dropped and downloaded again instead of being served. Each download is written
  through to the disk before it is renamed into place.
- `clearLocalData()` part-way through a channel download no longer fails the download on the
  device, and the in-memory client behaves the same.
- A moved object's cached copy keeps the time it was fetched; `cachedFile(path).fetchedAt` no
  longer reads as the time of the move.
- An object whose path has a segment that starts with a letter and a colon (`u:42/a.png`) is now
  cached; `ensureCachedFile` no longer refuses it, and channel downloads of it no longer fetch every
  time.
- A sign-in as another user without a sign-out first, as an anonymous session signing into an
  account does, kept the Realtime channel joined as the old user, so the new user's named tables
  were not read again and a change still arriving for the old user could be applied to the new
  user's cache; the channel is now left and joined again as the new user, and a change from the
  old user's channel is dropped.
- `supabase.close()` with the Realtime plugin installed before Offline could leave `realtimeStatus`
  at `Stalled`, and it and a sign-out that disconnects Realtime logged a warning that the channel
  ended and would be joined again; the status now ends at `Off` at close, and a channel that ended
  is logged as a warning only when it is joined again.
- Without the Auth plugin, a token the app's provider rotated after the refresh a minute before
  the expiry was not sent to the channel until a minute before its own expiry, so the server closed
  the channel at the old expiry and the named tables were read again; the provider is now asked
  more often as the expiry nears, a later token is sent as soon as it is returned, and a provider
  that failed once is asked again instead of being left until the next join.
- An empty value in an `in` list with other values, such as `isIn("status", listOf("", "done"))`,
  is kept on the device as the server keeps it: a queued or cached row whose value is the empty
  string shows in a cached list read through that filter, and a queued update or delete sends the
  list whole instead of without the empty value. A list of only the empty value still matches no
  row, as the server reads it.

## [0.2.0] - 2026-10-20

### Breaking

- The library depends on supabase-kt's `realtime-kt`, from the same BOM as its other modules, for
  the tables an app names in its configuration to keep current through Realtime. An app that
  already lists `realtime-kt` changes nothing; one that did not now resolves the module, which it
  can leave uninstalled.

### Added

- `realtime { table("tasks") }` in `install(Offline)`: the plugin keeps the named tables current
  through supabase-kt's Realtime plugin, which the app installs. It joins one channel while a user
  is signed in (or Auth is not installed) and the network is not known down, applies each insert,
  update and delete as `applyServerRecord` and `applyServerDelete` do, reads the named tables again
  on every join (Realtime replays nothing), and leaves the channel on sign-out, offline,
  `disableNetwork()` and `close()`. `table(name, schema, filter)` takes a schema and one
  `RealtimeFilter` the server evaluates; `Offline.Config.realtimeTables` lists what was named.
  Tables named without `install(Realtime)` fail `createSupabaseClient`.
- `Offline.realtimeStatus`, a `StateFlow<RealtimeStatus>` (`Off`, `Paused`, `Subscribing`,
  `Subscribed`, `Stalled(cause)`), and the switch `disableRealtime()` / `enableRealtime()`, not
  kept across clients, like the network switch.
- `FakeRealtime` in `supabase-offline-kt-testing`, with `inMemory(clock, realtime)`: a test reports
  inserts, updates and deletes to an in-memory client that names tables in `realtime { }`, with no
  `install(Realtime)`; `subscribedTables` says what the client follows, and `failChannel` and
  `dropChannel` end the channel as a server or a lost socket would. `stalledRealtime(cause)` builds
  the stalled status for a test double.
- Swift: `Config.realtimeTables`, an array of `RealtimeTable(_:schema:filter:)` with a
  `RealtimeFilter(column:operator:value:)` or `RealtimeFilter(column:in:)`, and a `Config`
  initializer that takes it beside the existing one; `realtimeStatus`, a
  `RealtimeStatusSequence` of `RealtimeStatus` (`off`, `paused`, `subscribing`, `subscribed`,
  `stalled(cause:)`); and `disableRealtime()` / `enableRealtime()`. The core owns the channel and
  refreshes its token before it expires through `Config.accessToken`; an app brackets a sign-in or
  sign-out with the switch, since the core sees no Auth session. `FakeRealtime` in
  `SupabaseOfflineTesting`, with `inMemory(…, realtime:)`, reports changes to an in-memory client.
- Conflict detection for replayed writes, opt-in per table. `versionColumn("tasks", "updated_at")`
  in `install(Offline)`, and the map `Offline.Config.versionColumns` behind it, keyed as
  `primaryKeys` is, names the column a server-side trigger changes on every update of a row (a
  `timestamptz` kept by `moddatetime`, or an integer). A queued UPDATE or DELETE whose filter pins
  one cached row of such a table records the row's version, and a sync pass replays it only while
  the server's row still has that version, asking for the row back; the returned row is cached and
  its version forwarded to the writes queued behind it. When no row comes back, one read by key
  tells a gone row, a policy denial (the rejected list), a lost answer whose write had landed
  (already on the server), a change to other columns only (resent against the new version) and a
  conflict. Writes sent at once, inserts, upserts and writes the queue cannot pin to one cached row
  replay as before. `versionColumn` throws `IllegalArgumentException` for a blank column, and a
  column among the table's key columns fails `createSupabaseClient` with it. The documentation's
  new "Conflicts and merging" page walks through it.
- `Offline.Config.conflictResolver`, a `ConflictResolver` the app may implement, asked about a moved
  row whose written columns changed on the server too, a DELETE of a moved row, or an UPDATE of a
  gone one, with a `MutationConflict` (the row key, the base, the local columns, the server's row,
  `changedOnServer` and `conflictingColumns`) and answering a `ConflictResolution`: `KeepLocal`
  (resend against the server's version; the default for a moved row), `KeepServer` (drop it; the
  default for an UPDATE whose row is gone), `ConflictResolution.apply(columns)` (send other columns;
  an UPDATE of a row still on the server), or `Hold`. A resolver that throws (anything but a
  cancellation) or takes longer than 30 seconds holds the write; it runs on the library's own
  dispatcher.
- `Offline.conflictedMutations()`, the current user's held writes as `ConflictedMutation` entries,
  kept across restarts within the rejected list's bounds, with
  `resolveConflictedMutation(id, resolution)` (re-queues under a new id preconditioned on the
  recorded server version, or drops it) and `dismissConflictedMutation(id)`. `clearLocalData()`
  clears the list.
- `SyncReport.conflictsResolvedCount`, `SyncReport.conflictsKeptServerCount` and
  `SyncReport.conflicted`; `SyncStatus.conflictedMutationCount`, counted together with the pending
  and rejected counts; and `PendingMutation.baseVersion`, the version a queued write replays
  against.
- `mutationConflict(…)` and `conflictedMutation(…)` in `supabase-offline-kt-testing`, which build
  the two new value types for a test of the app's own resolver or conflicted-list screen, and
  overloads of `pendingMutation` (with `baseVersion`), `syncReport` (with the conflict counts and
  `conflicted`) and `syncStatus` (with `conflictedMutationCount`) beside the existing factories.
  `ConflictStagingTest` shows how to stage a row moved on the server between enqueue and replay
  with a Ktor `MockEngine` alone: answer the guarded write with `[]` and the read by key with the
  moved row.
- Swift: `Config.versionColumns` and `Config.conflictResolver`, an `async throws` closure from a
  `MutationConflict` to a `ConflictResolution` (`keepLocal`, `keepServer`, `apply(columns:)`,
  `hold`), with a `Config` initializer that takes them beside the existing ones;
  `conflictedMutations()`, a `ConflictedMutationSequence` of `ConflictedMutation`, with
  `resolveConflictedMutation(id:resolution:)` and `dismissConflictedMutation(id:)`; and
  `SyncReport.conflictsResolvedCount`, `conflictsKeptServerCount` and `conflicted`,
  `SyncStatus.conflictedMutationCount` and `PendingMutation.baseVersion`. The resolver runs in a
  task of its own, off the caller's actor; a throw holds the write. `mutationConflict(…)`,
  `conflictedMutation(…)` and overloads of `pendingMutation`, `syncReport` and `syncStatus` in
  `SupabaseOfflineTesting` build the new values.

### Changed

- The database moves to schema version 2, which adds `base_version` and `base_json` to the mutation
  queue, with a migration that keeps every queued write; a write queued before the upgrade replays
  unchecked, as it would have. Going back to 0.1.0 across it is not supported, and loses the writes
  queued since.

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
