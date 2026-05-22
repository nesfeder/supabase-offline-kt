# Storage

The SDK wraps a Supabase Storage `BucketApi` with an offline-aware layer that caches successful
downloads to disk, falls back to those files when the network is down, and tracks metadata in the
local Room database.

For internals (`OfflineBucketApi` + Okio file IO), see
[architecture.md](./architecture.md#storage-cache). For exception behavior, see
[api-reference.md](./api-reference.md#storage-downloads).

## Contents

- [Getting a bucket](#getting-a-bucket)
- [Download caching](#download-caching)
- [Local file layout](#local-file-layout)
- [Path helpers](#path-helpers)
- [Mutating ops (`delete`, `move`, `copy`)](#mutating-ops-delete-move-copy)
- [Client-driven staleness](#client-driven-staleness)

## Getting a bucket

```kotlin
val bucket = offline.storage("public")
```

`SupabaseOfflineClient.storage(bucket)` returns a `BucketApi` (the supabase-kt type) backed by
[OfflineBucketApi.kt](../shared/src/commonMain/kotlin/io/github/nesfeder/supabase/internal/storage/OfflineBucketApi.kt).
You can use every method `BucketApi` provides; the wrapper transparently caches downloads and
mirrors writes locally.

## Download caching

Both `downloadAuthenticated(path)` and `downloadPublic(path)` cache successful responses to disk:

1. Try the network call.
2. On success, write the bytes to disk and `upsert` a `StorageRow` in the `storage_objects`
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
[FileIO.kt](../shared/src/commonMain/kotlin/io/github/nesfeder/supabase/internal/storage/FileIO.kt):

```19:25:shared/src/commonMain/kotlin/io/github/nesfeder/supabase/internal/storage/FileIO.kt
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
[`SupabaseOfflineConfig`](./configuration.md).

## Path helpers

Convenient extensions on `BucketApi` (which under the hood downcast to the internal
`OfflineBucketApi`) let you skip the bytes-in-memory step when you just need a file URL.

### Android

Defined in
[shared/src/androidMain/kotlin/io/github/nesfeder/supabase/OfflineBucketApi.android.kt](../shared/src/androidMain/kotlin/io/github/nesfeder/supabase/OfflineBucketApi.android.kt):

| Function | Returns | Behavior |
|----------|---------|----------|
| `ensureLocalFile(objectPath, isPublic)` | `File?` | Returns the cached `File` if present; otherwise downloads and returns the resulting file. Returns `null` on failure. |
| `ensureLocalUri(objectPath, isPublic)` | `Uri?` | Same as `ensureLocalFile` but wraps in a `file://` `Uri`. |
| `localUriIfCached(objectPath)` | `Uri?` | Returns the cached `Uri` only — never triggers a download. |
| `storageCacheMetadata(objectPath)` | `StorageCacheMetadata?` | Returns metadata only if both the DB row exists and the file is still on disk. |

### iOS

Defined in
[shared/src/iosMain/kotlin/io/github/nesfeder/supabase/OfflineBucketApi.ios.kt](../shared/src/iosMain/kotlin/io/github/nesfeder/supabase/OfflineBucketApi.ios.kt):

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
[shared/src/commonMain/kotlin/io/github/nesfeder/supabase/StorageCacheMetadata.kt](../shared/src/commonMain/kotlin/io/github/nesfeder/supabase/StorageCacheMetadata.kt):

```11:19:shared/src/commonMain/kotlin/io/github/nesfeder/supabase/StorageCacheMetadata.kt
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
