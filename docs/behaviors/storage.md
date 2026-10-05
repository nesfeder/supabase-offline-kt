# Storage caching

A download through the offline bucket goes to the network first and is kept on disk; when the
network fails, the kept copy is served instead. Writes (uploads, updates, deletes, moves and
copies) are online only and never queued, and one that reaches the server drops the cached copies
it made stale.

```kotlin
--8<-- "StorageExamples.kt:bucket"
```

`supabase.offline.storage.from(bucket)` is an `OfflineBucketApi`: every operation of supabase-kt's
`BucketApi`, under its name, plus the file cache. Bucket management (creating, listing and
emptying buckets) stays on `supabase.storage`.

## Downloads

```kotlin
--8<-- "StorageExamples.kt:download"
```

`downloadAuthenticated` and `downloadPublic`, to bytes or to a channel, go to the network first:

- **A success** is written to the cache, whole or not at all. A failure to write the copy is
  logged and never fails the download.
- **A "not found"** from the server drops the cached copy, and the error is rethrown.
- **Any other failure** is answered from the cached copy when there is one, and thrown when there
  is none.

While a stored session restores, or after its refresh failed, a download goes out on the anon key,
and Storage answers an object that key may not read as "not found". That answer keeps the cached
copy and is answered from it; with nothing cached, it is thrown. Download private objects after
`supabase.auth.awaitInitialization()`.

A download with an image transform is cached per transform, apart from the original. A channel
download streams through a file on disk instead of being held in memory, and its answer's status
is checked first: an error answer throws as the bytes download does, and nothing of it is cached
or handed on.

## Cached files

```kotlin
--8<-- "StorageExamples.kt:cached-file"
```

`cachedFile(path)` returns the cached copy, or `null`, and never touches the network.
`ensureCachedFile(path)` downloads the object into the cache first when there is no copy; it
streams to disk. Only an untransformed download or `ensureCachedFile` caches the copy these
return.

A `CachedFile` is the cache's own copy at an absolute `path`, with `fetchedAt` and `sizeBytes`.
Read it, open it, hand it to an image loader, but do not move or write it. The cache may delete
it when the object is uploaded, updated, deleted, moved or copied over through the offline bucket,
when the server reports it gone, when the cache passes its bound and the file has not been used
for 30 seconds, when the cache passes to another user, and when local data is cleared. Open it
soon after you get it, or copy it if it must outlive those.

**Nothing revalidates a cached file.** A copy is returned however old: compare `fetchedAt` with
your own rule, and download again when it is too old.

On Android, three helpers on the offline bucket give the cached copy in the platform's terms, for
an image loader:

```kotlin
--8<-- "StorageExamples.kt:android-helpers"
```

`ensureLocalUri` and `ensureLocalFile` are `ensureCachedFile` as a `Uri` or a `File`, and throw what
it throws; `localUriIfCached` is `cachedFile` as a `Uri`, `null` when nothing is cached. On iOS,
`ensureLocalPath(path)` and `localPathIfCached(path)` give the absolute path the same way.

## Writes

Uploads, updates, deletes, moves and copies are online only, and nothing is queued:

- **Offline**, a write is tried whatever the connectivity monitor says, and fails with
  supabase-kt's own connectivity error, as it does without the library.
- **With network access off** (`disableNetwork()`), every Storage call other than a download
  throws `NetworkDisabledException` and sends nothing; a download is answered from the file
  cache, or throws `NetworkDisabledException` when nothing is cached.
- **A write that reaches the server** drops the cached copies it made stale, so a later read never
  serves the old bytes. A resumable upload drops them once it finishes.
- **An upload does not put its bytes in the cache**, so the next read downloads them. A file
  uploaded just before the device goes offline is not available offline until it is downloaded.

Signed URLs, listings, `info` and `exists` always ask the server. `publicUrl` and the other URL
builders send nothing.

## Paths that are never cached

An object path that cannot be mapped safely onto a file (a leading `/`, an empty, `.` or `..`
segment, a `\`, a control character) still downloads and writes, uncached, and
`ensureCachedFile` refuses it with `IllegalArgumentException`.

So does a name holding `%`, `?` or `#` on supabase-kt 3.6, which puts the name in the URL as
written: a `%` fails the request, and a `?` or `#` can fetch a different object, so the library
never caches it. Keep these characters out of object names.

## Whose files they are

With Auth installed, the file cache belongs to one user at a time, like the query cache: when the
session settles as someone else, the files are deleted, and a download that started before the
change is not written into the new user's cache. `clearLocalData()` deletes every file. See
[Signing out and switching users](sign-out.md).

The bound, 256 MiB by default, is `fileCacheSizeBytes`; see
[Cache size and eviction](eviction.md#the-file-cache). Where the files live is on
[On-device data](../reference/on-device-data.md).

## Behavior matrix

Facts about the engine; one row per condition, the conditions those of the
[one-shot read matrix](read-strategies.md#behavior-matrix), with warm and cold saying whether the
object is cached. Each cell names the test that pins it. Writes are `upload`, `update`, `delete`,
`move`, `copy` and the resumable and signed-URL uploads.

| Condition | `downloadAuthenticated` / `downloadPublic` | `ensureCachedFile` | `cachedFile` | Writes |
|---|---|---|---|---|
| **Online** | The server's bytes, written to the cache whole or not at all. Not found: the cached copy is dropped and the error thrown.<br><small>`OfflineBucketApiDownloadTest.downloadAuthenticated_success_cachesBytesAndReturnsThem`, `StorageStreamingDownloadTest.a_channel_download_streams_from_the_network_through_the_cache_to_the_caller`, `StorageAtomicWriteTest.a_write_that_fails_part_way_leaves_the_previous_copy_whole`, `OfflineBucketApiTest.not_found_download_evicts_cache_and_rethrows`</small> | The cached file; if there is none, the object is downloaded to disk once.<br><small>`StorageCachedFileTest.ensureCachedFile_streams_a_missing_object_to_disk_once`</small> | The cached file or `null`; never a request.<br><small>`StorageCachedFileTest.cachedFile_is_null_before_anything_is_cached_and_never_downloads`, `StorageCachedFileTest.cachedFile_describes_a_downloaded_object`</small> | Sent; the cached copy of what they change is dropped. A write the server rejected keeps it.<br><small>`StorageWriteInvalidationTest.an_update_never_leaves_the_old_bytes_to_be_served`, `StorageWriteInvalidationTest.a_vararg_delete_removes_the_cached_copy`, `StorageWriteInvalidationTest.a_write_the_server_rejected_keeps_the_cached_copy`</small> |
| **Offline, warm** | The cached bytes.<br><small>`OfflineBucketApiTest.successful_download_caches_bytes_then_serves_cache_on_network_failure`, `OfflineBucketApiDownloadTest.downloadAuthenticatedToChannel_fallsBackToCacheOnFailure`</small> | The cached file, no request.<br><small>`StorageCachedFileTest.ensureCachedFile_streams_a_missing_object_to_disk_once`</small> | The cached file.<br><small>`StorageCachedFileTest.cachedFile_describes_a_downloaded_object`</small> | Throw the connectivity failure; the cache is left as it was.<br><small>`StorageWriteInvalidationTest.storage_writes_fail_offline_with_the_connectivity_error_and_leave_the_cache_alone`</small> |
| **Offline, cold** | Throws the failure.<br><small>`OfflineBucketApiDownloadTest.downloadAuthenticated_throwsWhenNoCacheAndDelegateFails`, `OfflineBucketApiDownloadTest.downloadPublic_throwsWhenNoCacheAndDelegateFails`</small> | Throws the failure.<br><small>`StorageCachedFileTest.ensureCachedFile_throws_the_download_failure_when_nothing_is_cached`</small> | `null`.<br><small>`StorageCachedFileTest.cachedFile_is_null_before_anything_is_cached_and_never_downloads`</small> | As offline, warm.<br><small>`StorageWriteInvalidationTest.storage_writes_fail_offline_with_the_connectivity_error_and_leave_the_cache_alone`</small> |
| **Session restoring** | Not held for the session: sent at once on the anon key, and its answer cached. A `401` or `403` keeps the cached copy; cold, the refusal is thrown. Storage answers an object the anon key may not read as "not found", which keeps the cached copy too and is answered from it; cold, it is thrown: download private objects after `supabase.auth.awaitInitialization()`.<br><small>`StorageWhileRestoringTest.a_download_is_sent_at_once_on_the_anon_key_and_cached`, `StorageNotFoundWhileUnsettledTest.a_not_found_while_the_session_restores_is_answered_from_the_cached_copy`, `OfflineBucketApiTest.transient_auth_failure_serves_cache_and_does_not_evict`, `OfflineBucketApiDownloadTest.downloadAuthenticated_throwsWhenNoCacheAndDelegateFails`</small> | As a download.<br><small>`StorageCachedFileTest.ensureCachedFile_streams_a_missing_object_to_disk_once`, `StorageCachedFileTest.ensureCachedFile_throws_the_download_failure_when_nothing_is_cached`</small> | Unchanged: never a request.<br><small>`StorageCachedFileTest.cachedFile_is_null_before_anything_is_cached_and_never_downloads`</small> | Sent at once on the anon key; a refusal keeps the cached copy.<br><small>`StorageWhileRestoringTest.an_upload_is_sent_at_once_on_the_anon_key`, `StorageWriteInvalidationTest.a_write_the_server_rejected_keeps_the_cached_copy`</small> |

A channel download whose answer is an error throws it, and caches nothing.
<small>`ChannelDownloadStatusTest`, `DownloadToFileStatusAndroidTest`</small>

While network access is off, a download is answered from the file cache, and an upload, a delete
or a move throws `NetworkDisabledException`, leaving the cache as it was.
<small>`StorageWhileNetworkDisabledTest.a_download_while_access_is_off_is_answered_from_the_file_cache`,
`StorageWhileNetworkDisabledTest.uploads_and_deletes_while_access_is_off_throw_and_leave_the_cache_alone`</small>
