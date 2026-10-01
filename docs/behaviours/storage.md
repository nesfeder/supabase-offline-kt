# Storage

> **Written for an earlier engine.** This page describes the library as it was before 0.1.0: an
> earlier engine, API and release process. Parts of it contradict what 0.1.0 will ship. It is
> being rewritten for 0.1.0; until then, do not rely on it.

The SDK wraps a Supabase Storage `BucketApi` with an offline-aware layer that caches successful
downloads to disk, falls back to those files when the network is down, and tracks metadata in the
local Room database.

For internals (`OfflineBucketApi` + Okio file IO), see
[architecture.md](../project/internals.md#storage-cache). For exception behavior, see
[api-reference.md](../reference/supported-features.md#storage-downloads).

## Contents

- [Getting a bucket](#getting-a-bucket)
- [Download caching](#download-caching)
- [Local file layout](#local-file-layout)
- [Path helpers](#path-helpers)
- [Mutating ops (`delete`, `move`, `copy`)](#mutating-ops-delete-move-copy)
- [Client-driven staleness](#client-driven-staleness)
- [Behaviour matrix](#behaviour-matrix)

## Behaviour matrix

Facts about the engine as it is now, unlike the rest of this page; one row per condition, the
conditions those of the [one-shot read matrix](read-strategies.md#behaviour-matrix), with warm and
cold saying whether the object is cached. Each cell names the test that pins it. Writes are
`upload`, `update`, `delete`, `move`, `copy` and the resumable and signed-URL uploads.

| Condition | `downloadAuthenticated` / `downloadPublic` | `ensureCachedFile` | `cachedFile` | Writes |
|---|---|---|---|---|
| **Online** | The server's bytes, written to the cache whole or not at all. Not found: the cached copy is dropped and the error thrown.<br><small>`OfflineBucketApiDownloadTest.downloadAuthenticated_success_cachesBytesAndReturnsThem`, `StorageStreamingDownloadTest.a_channel_download_streams_from_the_network_through_the_cache_to_the_caller`, `StorageAtomicWriteTest.a_write_that_fails_part_way_leaves_the_previous_copy_whole`, `OfflineBucketApiTest.not_found_download_evicts_cache_and_rethrows`</small> | The cached file; if there is none, the object is downloaded to disk once.<br><small>`StorageCachedFileTest.ensureCachedFile_streams_a_missing_object_to_disk_once`</small> | The cached file or `null`; never a request.<br><small>`StorageCachedFileTest.cachedFile_is_null_before_anything_is_cached_and_never_downloads`, `StorageCachedFileTest.cachedFile_describes_a_downloaded_object`</small> | Sent; the cached copy of what they change is dropped. A write the server rejected keeps it.<br><small>`StorageWriteInvalidationTest.an_update_never_leaves_the_old_bytes_to_be_served`, `StorageWriteInvalidationTest.a_vararg_delete_removes_the_cached_copy`, `StorageWriteInvalidationTest.a_write_the_server_rejected_keeps_the_cached_copy`</small> |
| **Offline, warm** | The cached bytes.<br><small>`OfflineBucketApiTest.successful_download_caches_bytes_then_serves_cache_on_network_failure`, `OfflineBucketApiDownloadTest.downloadAuthenticatedToChannel_fallsBackToCacheOnFailure`</small> | The cached file, no request.<br><small>`StorageCachedFileTest.ensureCachedFile_streams_a_missing_object_to_disk_once`</small> | The cached file.<br><small>`StorageCachedFileTest.cachedFile_describes_a_downloaded_object`</small> | Throw the connectivity failure; the cache is left as it was.<br><small>`StorageWriteInvalidationTest.storage_writes_fail_offline_with_the_connectivity_error_and_leave_the_cache_alone`</small> |
| **Offline, cold** | Throws the failure.<br><small>`OfflineBucketApiDownloadTest.downloadAuthenticated_throwsWhenNoCacheAndDelegateFails`, `OfflineBucketApiDownloadTest.downloadPublic_throwsWhenNoCacheAndDelegateFails`</small> | Throws the failure.<br><small>`StorageCachedFileTest.ensureCachedFile_throws_the_download_failure_when_nothing_is_cached`</small> | `null`.<br><small>`StorageCachedFileTest.cachedFile_is_null_before_anything_is_cached_and_never_downloads`</small> | As offline, warm.<br><small>`StorageWriteInvalidationTest.storage_writes_fail_offline_with_the_connectivity_error_and_leave_the_cache_alone`</small> |
| **Session restoring** | Not held for the session: sent at once on the anon key, and its answer cached. Refused for authorization, as such a request may be: the cached copy, kept; cold, the refusal is thrown.<br><small>`StorageWhileRestoringTest.a_download_is_sent_at_once_on_the_anon_key_and_cached`, `OfflineBucketApiTest.transient_auth_failure_serves_cache_and_does_not_evict`, `OfflineBucketApiDownloadTest.downloadAuthenticated_throwsWhenNoCacheAndDelegateFails`</small> | As a download.<br><small>`StorageCachedFileTest.ensureCachedFile_streams_a_missing_object_to_disk_once`, `StorageCachedFileTest.ensureCachedFile_throws_the_download_failure_when_nothing_is_cached`</small> | Unchanged: never a request.<br><small>`StorageCachedFileTest.cachedFile_is_null_before_anything_is_cached_and_never_downloads`</small> | Sent at once on the anon key; a refusal keeps the cached copy.<br><small>`StorageWhileRestoringTest.an_upload_is_sent_at_once_on_the_anon_key`, `StorageWriteInvalidationTest.a_write_the_server_rejected_keeps_the_cached_copy`</small> |

## Getting a bucket

```kotlin
val bucket = offline.storage("public")
```

`SupabaseOfflineClient.storage(bucket)` returns a `BucketApi` (the supabase-kt type) backed by
[OfflineBucketApi.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/commonMain/kotlin/io/github/nesfeder/supabase/internal/storage/OfflineBucketApi.kt).
You can use every method `BucketApi` provides; the wrapper transparently caches downloads and
mirrors writes locally.

## Download caching

Both `downloadAuthenticated(path)` and `downloadPublic(path)` cache successful responses to disk:

1. Try the network call.
2. On success, write the bytes to disk and `upsert` a `CachedFileRow` in the `storage_objects`
   table.
3. On failure, attempt to serve the bytes from disk; otherwise rethrow.

Two byte-channel variants (`downloadAuthenticated(path, channel)` / `downloadPublic(path, channel)`)
are also wrapped — they read the network into memory and then either stream cached bytes from
disk or pipe the freshly-cached bytes back into the channel.

## Local file layout

Cached files live under the offline base directory at:

```
<baseDirectory>/storage/<bucketId>/<objectPath>
```

The path resolver is in
[FileIO.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/commonMain/kotlin/io/github/nesfeder/supabase/internal/storage/FileIO.kt):

```kotlin title="shared/src/commonMain/kotlin/io/github/nesfeder/supabase/internal/storage/FileIO.kt" linenums="19"
internal fun resolveLocalPath(baseDir: Path, bucketId: String, objectPath: String): Path {
    val clean = objectPath
        .removePrefix("/$bucketId/")
        .removePrefix("$bucketId/")
        .removePrefix("/")
    return baseDir / "storage" / bucketId / clean
}
```

`baseDirectory` is provided by `PlatformBaseDirectory` in each platform's source set:

- **Android:** app private storage rooted at the `Context`-provided base. Implemented in
  `shared/src/androidMain/kotlin/io/github/nesfeder/supabase/internal/storage/FileIO.android.kt`.
- **iOS:** the Application Support directory. Implemented in
  `shared/src/iosMain/kotlin/io/github/nesfeder/supabase/internal/storage/FileIO.ios.kt`.

You can override the base by passing `offlineStoragePath` in
[`SupabaseOfflineConfig`](../reference/configuration.md).

## Path helpers

Convenient extensions on `BucketApi` (which under the hood downcast to the internal
`OfflineBucketApi`) let you skip the bytes-in-memory step when you just need a file URL.

### Android

Defined in
[shared/src/androidMain/kotlin/io/github/nesfeder/supabase/OfflineBucketApi.android.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/androidMain/kotlin/io/github/nesfeder/supabase/OfflineBucketApi.android.kt):

| Function | Returns | Behavior |
|----------|---------|----------|
| `ensureLocalFile(objectPath, isPublic)` | `File?` | Returns the cached `File` if present; otherwise downloads and returns the resulting file. Returns `null` on failure. |
| `ensureLocalUri(objectPath, isPublic)` | `Uri?` | Same as `ensureLocalFile` but wraps in a `file://` `Uri`. |
| `localUriIfCached(objectPath)` | `Uri?` | Returns the cached `Uri` only — never triggers a download. |
| `storageCacheMetadata(objectPath)` | `StorageCacheMetadata?` | Returns metadata only if both the DB row exists and the file is still on disk. |

### iOS

Defined in
[shared/src/iosMain/kotlin/io/github/nesfeder/supabase/OfflineBucketApi.ios.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/iosMain/kotlin/io/github/nesfeder/supabase/OfflineBucketApi.ios.kt):

| Function | Returns | Behavior |
|----------|---------|----------|
| `ensureLocalPath(objectPath, isPublic)` | `String?` | Returns the cached absolute POSIX path; downloads if missing. |
| `ensureLocalFileUrl(objectPath, isPublic)` | `String?` | `ensureLocalPath` wrapped as `"file://" + path`. |
| `localPathIfCached(objectPath)` | `String?` | Cached path or `null`. No network. |
| `localFileUrlIfCached(objectPath)` | `String?` | Same wrapped as `file://`. |
| `storageCacheMetadata(objectPath)` | `StorageCacheMetadata?` | Same semantics as Android. |

## Mutating ops (`delete`, `move`, `copy`)

These pass through to the remote bucket and also keep the local cache consistent:

- `delete(paths)` — deletes remotely, then removes the cached files **and** the corresponding
  `storage_objects` rows.
- `move(from, to, destinationBucket?)` — moves remotely, attempts an atomic file move locally
  (falls back to copy+delete), retracks the new row.
- `copy(from, to, destinationBucket?)` — copies remotely. Does **not** auto-populate the local
  cache for the destination; the next download will fill it.

## Client-driven staleness

The SDK does not enforce a max age for cached storage files — it never deletes a file just
because it's old. Use `StorageCacheMetadata.lastFetchedEpochMs` to make that decision yourself:

```kotlin
val metadata = bucket.storageCacheMetadata(objectPath)
val cutoff = Clock.System.now().toEpochMilliseconds() - 24.hours.inWholeMilliseconds
if (metadata == null || metadata.lastFetchedEpochMs < cutoff) {
    // re-download
    bucket.ensureLocalFile(objectPath, isPublic = true)
}
```

The metadata type lives at
[shared/src/commonMain/kotlin/io/github/nesfeder/supabase/StorageCacheMetadata.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/commonMain/kotlin/io/github/nesfeder/supabase/StorageCacheMetadata.kt):

```kotlin title="shared/src/commonMain/kotlin/io/github/nesfeder/supabase/StorageCacheMetadata.kt" linenums="11"
data class StorageCacheMetadata(
    val bucketId: String,
    /** Object key within the bucket (same string passed to download / path helpers). */
    val objectPath: String,
    /** Absolute path to the cached file on device. */
    val localPath: String,
    /** Epoch milliseconds when this cache entry was last written (successful download / track). */
    val lastFetchedEpochMs: Long,
)
```

`storageCacheMetadata` returns `null` whenever the file referenced by a row is no longer on disk
(e.g. user cleared app data) — so a non-null return guarantees both the row and the file are
intact.
