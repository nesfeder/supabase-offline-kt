# API Reference

> **Written for an earlier engine.** This page describes the library as it was before 0.1.0: an
> earlier engine, API and release process. Parts of it contradict what 0.1.0 will ship. It is
> being rewritten for 0.1.0; until then, do not rely on it.

This document lists the public types, their behavior, and the exceptions you should expect to
handle. For the *why* behind any of this, see [architecture.md](../project/internals.md). For real
usage examples in the test apps, see [test-apps.md](../samples/index.md).

## Contents

- [Client surface](#client-surface)
- [Read strategies (`ReadStrategy`)](#read-strategies-readstrategy)
- [Reactive read strategies (`FlowReadStrategy`)](#reactive-read-strategies-flowreadstrategy)
- [Mutation strategies (`MutationStrategy`)](#mutation-strategies-mutationstrategy)
- [Exception types](#exception-types)
- [Behavior reference](#behavior-reference)
    - [SELECT (one-shot)](#select-one-shot)
    - [SELECT as Flow](#select-as-flow)
    - [Mutations (INSERT / UPSERT / UPDATE / DELETE)](#mutations-insert-upsert-update-delete)
    - [Sync APIs](#sync-apis)
    - [Storage downloads](#storage-downloads)
    - [Storage path helpers](#storage-path-helpers)
    - [Pending mutation count](#pending-mutation-count)

## Client surface

The entry point is the `SupabaseOfflineClient` interface, defined in
[shared/src/commonMain/kotlin/io/github/nesfeder/supabase/SupabaseOfflineClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/commonMain/kotlin/io/github/nesfeder/supabase/SupabaseOfflineClient.kt).

| Member | Purpose |
|--------|---------|
| `supabase: SupabaseClient` | Underlying online supabase-kt client |
| `from(table)` / `get(table)` | Returns an offline-aware `OfflinePostgrestQueryBuilder` |
| `from(schema, table)` / `get(schema, table)` | Same, in a non-default schema |
| `storage(bucket)` | Returns an offline-aware `BucketApi` |
| `syncAllPendingMutations()` | Push every queued mutation across all tables |
| `syncPendingMutations(table)` | Push queued mutations for a single table |
| `pendingMutationCount()` / `pendingMutationCount(table)` | Suspend snapshot |
| `pendingMutationCountFlow()` / `pendingMutationCountFlow(table)` | Reactive `Flow<Int>` |

`OfflinePostgrestQueryBuilder` (in
[OfflinePostgrestQueryBuilder.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/commonMain/kotlin/io/github/nesfeder/supabase/OfflinePostgrestQueryBuilder.kt))
exposes:

- `select` — one-shot, configurable via `ReadStrategy`
- `selectAsFlow` — reactive, configurable via `FlowReadStrategy`
- `insertJson` / `insertWith` (+ Android reified `insert`)
- `upsertJson` / `upsertWith` (+ Android reified `upsert`)
- `updateJson` / `updateWith` (+ Android reified `update`)
- `delete`

> **iOS note:** `selectAsFlow` is currently exposed only in the Kotlin/Android surface. iOS code
> uses the Swift wrapper in
> [ios/support/SupabaseOfflineSupport/](https://github.com/nesfeder/supabase-offline-kt/tree/9b014a795453590d7aecd704b01ca36ade8f67cb/ios/support/SupabaseOfflineSupport), which provides
> `selectAll`, `selectRaw`, `selectColumns`, `insertOne`, `updateOne`, `deleteWhere`, etc., all
> built on the one-shot Kotlin API.

## Read strategies (`ReadStrategy`)

Defined as a sealed interface in
[OperationOfflineStrategy.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/commonMain/kotlin/io/github/nesfeder/supabase/OperationOfflineStrategy.kt).

| Variant | Behavior |
|---------|----------|
| `NetworkOnly` | Always go to the network. Updates the cache on success. Throws on any failure. |
| `NetworkFirst(cacheDuration?)` (default) | Try network. On **connectivity** failure, fall back to a cached row newer than `cacheDuration`. Server errors do **not** trigger fallback. |
| `CacheFirst(cacheDuration?)` | Try cache (subject to TTL). On miss/stale, go to network. |
| `CacheOnly(cacheDuration?)` | Cache only. On miss/stale, throw `NoSuchElementException`. |

`cacheDuration` is a `kotlin.time.Duration?`. `null` means "no TTL — never stale by time". A
millisecond constructor overload is provided for Swift convenience.

See the [caching deep-dive](../project/internals.md#read-path-caching-deep-dive) for how TTL is
evaluated, signature generation, and the cache subset resolver.

## Reactive read strategies (`FlowReadStrategy`)

Enum in the same file.

| Variant | Behavior |
|---------|----------|
| `CacheFirstThenNetwork` (default) | Emit cached row immediately (if any); start a background network refresh in parallel. Network errors are swallowed; subsequent cache updates keep emitting. |
| `NetworkFirstThenCache` | Fetch network first; once stored, observe the cache. Network failure terminates the Flow with the error. |
| `CacheOnly` | Observe cache rows only. No network call. |

## Mutation strategies (`MutationStrategy`)

Enum in the same file.

| Variant | Behavior |
|---------|----------|
| `QueueIfOffline` (default) | Try network. On connectivity failure, persist to local queue and return an empty result. On server failure, rethrow. |
| `OnlineOnly` | Network only. Connectivity failure rethrows without queueing. |

## Exception types

The SDK does not invent its own exception hierarchy — it re-throws exceptions raised by
supabase-kt, Ktor, kotlinx, and Room. Catch by type at the call site.

| Exception | Category | Source | Meaning |
|---|---|---|---|
| `HttpRequestTimeoutException` | Network | Ktor | The HTTP request exceeded the configured timeout |
| `ConnectTimeoutException` | Network | Ktor | TCP connection could not be established in time |
| `SocketTimeoutException` | Network | Ktor | An established socket timed out during read/write |
| `IOException` | Network | kotlinx.io / Okio | General transport failure (DNS, TLS, reset, file I/O) |
| `PostgrestRestException` | Server | supabase-kt | Supabase PostgREST returned an HTTP error; carries `statusCode` |
| `ResponseException` | Server | Ktor | Generic HTTP response error (non-2xx status) |
| `NoSuchElementException` | Cache | SDK | `ReadStrategy.CacheOnly` with no valid cached data for the query |
| `IllegalStateException` | Config | SDK | Postgrest or Storage plugin was not installed on `SupabaseClient` |
| `SerializationException` | Serialization | kotlinx.serialization | JSON encoding or decoding failed (malformed payload, schema mismatch) |
| `ClassCastException` | Serialization | Kotlin | JSON payload was not the expected type (e.g. not a `JsonArray`) |
| `CancellationException` | Coroutine | kotlinx.coroutines | Caller's coroutine scope was cancelled; never treated as a network error |

### Network vs. server classification

`isNetworkOrConnectivityFailure()` in
[ThrowableExtensions.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/commonMain/kotlin/io/github/nesfeder/supabase/internal/util/ThrowableExtensions.kt)
drives all fallback and queuing behavior:

- **Network/connectivity (transient):** `HttpRequestTimeoutException`, `ConnectTimeoutException`,
  `SocketTimeoutException`, `IOException`, and any throwable whose cause chain contains one of
  the above.
- **NOT network (rethrown unchanged):** `CancellationException`, anything carrying an HTTP
  status (`PostgrestRestException`, `ResponseException`), everything else.

## Behavior reference

### SELECT (one-shot)

`OfflinePostgrestQueryBuilder.select`:

| `ReadStrategy` | Network OK | Network error | Server error (4xx/5xx) | Possible exceptions |
|---|---|---|---|---|
| `NetworkOnly` | Returns result, updates cache | Throws | Throws | Network, Server, Serialization |
| `NetworkFirst(cacheDuration?)` | Returns result, updates cache | Cache hit within TTL: returns cached. Cache miss or expired: throws | Throws (no fallback) | Network, Server, Serialization |
| `CacheFirst(cacheDuration?)` | Cache hit within TTL: returns cached. Cache miss: fetches | Cache hit: returns cached. Cache miss: throws | Cache hit: returns cached. Cache miss: throws | Network, Server, Serialization |
| `CacheOnly(cacheDuration?)` | Cache hit within TTL: returns cached. Cache miss or expired: throws | Network is never used | Network is never used | `NoSuchElementException` |

### SELECT as Flow

`OfflinePostgrestQueryBuilder.selectAsFlow`:

| `FlowReadStrategy` | Behavior | Throws? | Possible exceptions |
|---|---|---|---|
| `CacheFirstThenNetwork` | Emits cached data immediately. Fires a background network request in parallel. Network errors are silently swallowed; the Flow continues emitting cached data. | No | None (network errors caught internally) |
| `NetworkFirstThenCache` | Performs a network request in `onStart`. If the network fails, the Flow terminates with the exception. On success, caches the result and observes the cache for future changes. | Yes | Network, Server |
| `CacheOnly` | Observes only the local cache. No network request is ever made. Emits cached data and future cache updates. | No | None |

### Mutations (INSERT / UPSERT / UPDATE / DELETE)

| `MutationStrategy` | Network OK | Network error | Server error (4xx/5xx) | Possible exceptions |
|---|---|---|---|---|
| `OnlineOnly` | Returns result from server | Throws | Throws | Network, Server, Serialization |
| `QueueIfOffline` | Returns result from server | Queues mutation locally, returns empty result (no exception) | Throws (non-network errors are never queued) | Server, Serialization |

### Sync APIs

| Method | Permanent failure (4xx except 408/429) | Network error | Unknown error | Possible exceptions |
|---|---|---|---|---|
| `syncAllPendingMutations()` | Mutation dropped, sync continues. Per-table errors are swallowed; returns total synced count | Stops syncing for that table, continues to other tables. Returns total synced count | Per-table errors are swallowed. Returns total synced count | `CancellationException` only (all others swallowed) |
| `syncPendingMutations(table)` | Mutation dropped, continues to next mutation | Stops syncing; current + remaining mutations stay queued. Returns synced count | Keeps failing mutation in queue. Rethrows to caller | Network, Server, `CancellationException` |

### Storage downloads

| Method | Network OK | Network error | No cache available | Possible exceptions |
|---|---|---|---|---|
| `downloadAuthenticated(path)` | Returns bytes, caches to disk | Falls back to disk cache if available | Throws | Network, Server |
| `downloadPublic(path)` | Returns bytes, caches to disk | Falls back to disk cache if available | Throws | Network, Server |
| `delete(paths)` | Deletes remotely, removes local cache entries and files | Throws | n/a | Network, Server |
| `move(from, to)` | Moves remotely, updates local cache paths | Throws | n/a | Network, Server, `IOException` |
| `copy(from, to)` | Copies remotely (no local cache population) | Throws | n/a | Network, Server |

### Storage path helpers

Convenience extensions defined per platform — see
[OfflineBucketApi.android.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/androidMain/kotlin/io/github/nesfeder/supabase/OfflineBucketApi.android.kt)
and
[OfflineBucketApi.ios.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/iosMain/kotlin/io/github/nesfeder/supabase/OfflineBucketApi.ios.kt).

| Method | Behavior | Throws? | Possible exceptions |
|---|---|---|---|
| `ensureLocalPath(objectPath, isPublic)` (iOS) / `ensureLocalFile(objectPath, isPublic)` (Android) | Returns cached path if present. Otherwise downloads (download errors caught, returns `null`). | Yes | `IOException`, database errors |
| `localPathIfCached(objectPath)` (iOS) / `localUriIfCached(objectPath)` (Android) | Returns cached path or `null`. Reads from local database directly. | Yes | Database errors |
| `storageCacheMetadata(objectPath)` | Returns [`StorageCacheMetadata`](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/commonMain/kotlin/io/github/nesfeder/supabase/StorageCacheMetadata.kt) when a cache row exists **and the file exists on disk**, otherwise returns `null`. Includes `lastFetchedEpochMs`. | Yes | Database errors |
| `ensureLocalFileUrl(objectPath, isPublic)` (iOS) / `ensureLocalUri(objectPath, isPublic)` (Android) | Delegates to `ensureLocalPath` / `ensureLocalFile`, returns a `file://` URL / `Uri` | Yes | Same as `ensureLocalPath` |
| `localFileUrlIfCached(objectPath)` (iOS) | Delegates to `localPathIfCached`, prepends `file://` | Yes | Same as `localPathIfCached` |

**Client-driven staleness:** The SDK does not enforce a max age for storage files. Compare
`StorageCacheMetadata.lastFetchedEpochMs` to your own cutoff to decide whether to use the cache
or re-download. See [storage.md](../behaviours/storage.md) for examples.

### Pending mutation count

| Method | Throws? | Possible exceptions |
|---|---|---|
| `pendingMutationCount()` | Yes | Database errors |
| `pendingMutationCount(table)` | Yes | Database errors |
| `pendingMutationCountFlow()` | No (Flow) | Errors surface as Flow termination |
| `pendingMutationCountFlow(table)` | No (Flow) | Errors surface as Flow termination |
