# Storage caching

A download through the offline bucket goes to the network first and is kept on disk; when the
network fails, the kept copy is served instead. Writes (uploads, updates, deletes, moves and
copies) are online only and never queued, and one that reaches the server drops the cached copies
it made stale.

From the samples: [TaskPhotoFiles.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/TaskPhotoFiles.kt) · [TaskRepository+Photos.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+Photos.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/TaskPhotoFiles.kt:bucket"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository+Photos.swift:bucket"
    ```

    The Swift package's bucket has no `setHeader`.

`supabase.offline.storage.from(bucket)` is an `OfflineBucketApi`: every operation of supabase-kt's
`BucketApi`, under its name, plus the file cache. Bucket management stays on `supabase.storage`.

## Downloads

```kotlin
--8<-- "StorageExamples.kt:download"
```

`downloadAuthenticated` and `downloadPublic`, to bytes or to a channel, go to the network first. A
success is written to the cache, whole or not at all (a failure to write the copy is logged and
never fails the download); a "not found" drops the cached copy and is rethrown; any other failure
is answered from the cached copy, or thrown when there is none. While a stored session restores,
or after its refresh failed, a download goes out on the anon key, and Storage answers an object
that key may not read as "not found": that answer keeps the cached copy and is answered from it,
and with nothing cached it is thrown, so download private objects after
`supabase.auth.awaitInitialization()`. An image transform is cached per transform, apart from the
original. A channel download streams through a file on disk rather than memory, and its answer's
status is checked first: an error answer throws as the bytes download does, and nothing of it is
cached or handed on.

## Cached files

From the samples: [TaskPhotoFiles.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/TaskPhotoFiles.kt) · [LibraryMappings.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LibraryMappings.kt) · [TaskRepository+Photos.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+Photos.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/TaskPhotoFiles.kt:cached-file"

    --8<-- "data/supabase/LibraryMappings.kt:cached-file"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository+Photos.swift:cached-file"
    ```

`cachedFile(path)` returns the cached copy, or `null`, and never touches the network;
`ensureCachedFile(path)` first streams the object to disk when there is no copy. Only an
untransformed download or `ensureCachedFile` caches the copy these return: a `CachedFile`, the
cache's own file at an absolute `path`, with `fetchedAt` and `sizeBytes`. Read it or hand it to an
image loader, but do not move or write it: the cache may delete it when the object is uploaded,
updated, deleted, moved or copied over through the offline bucket, when the server reports it
gone, when the cache passes its bound and the file has not been used for 30 seconds, when the cache
passes to another user, and when local data is cleared. Open it soon, or copy it if it must
outlive those. **Nothing revalidates a cached file**: compare `fetchedAt` with your own rule, and
download again when it is too old.

On Android, three helpers give the cached copy in the platform's terms: `ensureLocalUri` and
`ensureLocalFile` are `ensureCachedFile` as a `Uri` or a `File`, throwing what it throws, and
`localUriIfCached` is `cachedFile` as a `Uri`; on iOS, `ensureLocalPath(path)` and
`localPathIfCached(path)` give the absolute path the same way. The sample draws thumbnails from
`localUriIfCached` and the viewer from `ensureLocalUri`, and Share copies the file from
`ensureLocalFile` out of the cache, since another app reads it later and a `file:` URI cannot leave
the app:

From the samples: [TaskPhotoFiles.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/TaskPhotoFiles.kt) · [Sharing.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/ui/components/Sharing.kt) · [TaskRepository+Photos.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+Photos.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/TaskPhotoFiles.kt:android-helpers"

    --8<-- "ui/components/Sharing.kt:share-photo"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository+Photos.swift:share-photo"
    ```

## Writes

Uploads, updates, deletes, moves and copies are online only, and nothing is queued. Offline, a
write is tried whatever the connectivity monitor says and fails with supabase-kt's own
connectivity error; with network access off, every Storage call but a download throws
`NetworkDisabledException` and sends nothing, and a download is answered from the file cache (or
throws `NetworkDisabledException` with nothing cached). A write that reaches the server drops the
cached copies it made stale, a resumable upload once it finishes. An upload does not put its bytes
in the cache, so a file uploaded just before the device goes offline is not available offline
until it is downloaded. The sample uploads a photo with progress, from its own queue of photos
waiting to upload ([below](#photos-picked-offline-the-apps-own-queue)), replaces a file in place,
and moves or copies one to another task's folder, writing the photo's row too, since the schema
ties a row to its path:

From the samples: [TaskPhotoFiles.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/TaskPhotoFiles.kt) · [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository+Photos.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+Photos.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/TaskPhotoFiles.kt:upload"

    --8<-- "data/supabase/TaskPhotoFiles.kt:replace-photo"

    --8<-- "data/supabase/OfflineTaskRepository.kt:move-photo"

    --8<-- "data/supabase/OfflineTaskRepository.kt:copy-photo"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository+Photos.swift:upload"

    --8<-- "Data/TaskRepository+Photos.swift:replace-photo"

    --8<-- "Data/TaskRepository+Photos.swift:move-photo"

    --8<-- "Data/TaskRepository+Photos.swift:copy-photo"
    ```

Signed URLs, listings, `info` and `exists` always ask the server; `publicUrl` and the other URL
builders send nothing. The sample's Details sheet, Share link, Share all photos of this task and
the Sync tab's Storage section make each of them. A signed URL with an image transform serves
Storage's resized copy, which needs image transformations on the project (Pro plans and up on
hosted Supabase); without them the link answers with an error.

From the samples: [TaskPhotoFiles.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/TaskPhotoFiles.kt) · [TaskRepository+Photos.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+Photos.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/TaskPhotoFiles.kt:photo-details"

    --8<-- "data/supabase/TaskPhotoFiles.kt:share-link"

    --8<-- "data/supabase/TaskPhotoFiles.kt:share-all-links"

    --8<-- "data/supabase/TaskPhotoFiles.kt:list-bucket"

    --8<-- "data/supabase/TaskPhotoFiles.kt:signed-upload"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository+Photos.swift:photo-details"

    --8<-- "Data/TaskRepository+Photos.swift:share-link"

    --8<-- "Data/TaskRepository+Photos.swift:share-all-links"

    --8<-- "Data/TaskRepository+Photos.swift:list-bucket"

    --8<-- "Data/TaskRepository+Photos.swift:signed-upload"
    ```

## Photos picked offline: the app's own queue

The library never queues a Storage write, so an app that lets a user add a photo offline keeps the
photo in a queue of its own until the upload lands, and then references the library's cached copy.
Both samples do this; the queue is theirs, not the library's, and Storage writes still need a
connection, as [Writes](#writes) says.

- **A directory the app owns**, never the library's data directory or file cache: per photo, a JPEG
  named by the photo's id (made on the device, encoded as every upload is) and, written after it, a
  small JSON record with the task's id, the photo's id, its Storage path
  (`<user id>/<task id>/<photo id>.jpg`), when it was picked, its state (waiting, uploading, or
  failed with the app's sentence and the number of tries) and its content type.
- **Every photo goes through it**: New task's **Add photo**, several at once with their thumbnails,
  files its photos as waiting once the task is added (a queued insert offline), and the task
  detail's **Add photo** files one the same way; online it uploads at once.
- **Shown from the device at once.** The task's photos and the Photos tab merge the `task_photos`
  rows with the records, each photo once; a pending photo shows from its own file, marked "Waiting
  to upload", "Uploading" or "Upload failed". A failed one offers **Retry** (switched off when no
  try can succeed) and **Remove**, a waiting one **Remove**, not while it uploads. The viewer opens
  it from its file and hides Details, Share link, Replace, Move and Duplicate, which need the
  object in the bucket, with a line saying so.
- **One uploader** watches `syncStatus`. While the network is neither `OFFLINE` nor `DISABLED` (so
  Simulate offline pauses it, as it pauses the mutation queue) and a user is signed in, it uploads
  the waiting photos one at a time, oldest first: `upload` of the file; only once Storage has it,
  the `task_photos` row through the library, a write the queue holds like any other, so it waits
  behind its task's queued insert; then `ensureCachedFile` (if that fails the photo is stored all
  the same, and downloaded when next shown); then the app's file and record are deleted. The server
  never holds a row that names a missing object. Uploads run while the app runs.
- **Failures.** A failure marks the photo failed and keeps its file, for the next online transition
  or Retry; one for want of the network ends the pass. A refusal no try can change (Storage's
  policies answering 403, say, or the row refused because its task is gone) stays failed until
  Remove. A lost answer never stores anything twice: a retry first asks Storage whether an earlier
  try stored the file, Storage's "already exists" counts as stored (the path names a photo id this
  device made), and the row is an `upsert` that ignores a duplicate. Against hosted Storage the
  `exists` check is what catches it: supabase-kt reports a duplicate there as HTTP 400, not 409.
- **Deletes and clears.** Deleting a task deletes its pending photos, and Remove deletes one. Clear
  local cache and Start over delete the whole directory, which `clearLocalData()` never touches. A
  photo removed any of these ways while its upload is on the wire gets no row: once the upload
  returns, its file is deleted from the bucket. Start over first waits for an upload in progress and
  holds new ones, so none lands after its deletes, and clears the directory with the sign-out step.

From the samples: [PendingPhoto.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/PendingPhoto.kt) · [OfflineTasksApplication.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/OfflineTasksApplication.kt) · [PendingPhotoQueue.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/PendingPhotoQueue.swift)

=== "Android"

    ```kotlin
    --8<-- "data/PendingPhoto.kt:pending-photo-record"

    --8<-- "OfflineTasksApplication.kt:pending-photo-record"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/PendingPhotoQueue.swift:pending-photo-record"
    ```

    The iOS sample keeps the directory in Application Support, beside the library's data directory.

Picking a photo files it, and the uploader is told; nothing waits for the network:

From the samples: [TaskDetailViewModel.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/ui/detail/TaskDetailViewModel.kt) · [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [PendingPhotoDirectory.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/PendingPhotoDirectory.kt) · [TaskDetailModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Detail/TaskDetailModel.swift) · [TaskRepository+PendingPhotos.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+PendingPhotos.swift)

=== "Android"

    ```kotlin
    --8<-- "ui/detail/TaskDetailViewModel.kt:pending-photo-add"

    --8<-- "data/supabase/OfflineTaskRepository.kt:pending-photo-add"

    --8<-- "data/PendingPhotoDirectory.kt:pending-photo-add"
    ```

=== "iOS"

    ```swift
    --8<-- "Detail/TaskDetailModel.swift:pending-photo-add"

    --8<-- "Data/TaskRepository+PendingPhotos.swift:pending-photo-add"
    ```

The uploader's one photo: the file, then the row, then the hand-over to the cache:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository+PendingPhotos.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+PendingPhotos.swift) · [TaskRepository+Photos.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+Photos.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:pending-photo-upload"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository+PendingPhotos.swift:pending-photo-upload"

    --8<-- "Data/TaskRepository+Photos.swift:upload"

    --8<-- "Data/TaskRepository+Photos.swift:pending-photo-upload"
    ```

    Through the Swift package Storage's refusal keeps its own `Duplicate` code whatever the HTTP
    status, so the upload's "already exists" counts as stored there too.

An upload does not put its bytes in the file cache, so `ensureCachedFile` downloads the photo the
device has just sent; a 0.2 release of the library could seed the cache from the upload instead.

The rows and the records, merged for a task, or for every task on the Photos tab:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [PendingPhoto.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/PendingPhoto.kt) · [TaskRepository+PendingPhotos.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+PendingPhotos.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:pending-photo-merge"

    --8<-- "data/PendingPhoto.kt:pending-photo-merge"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository+PendingPhotos.swift:pending-photo-merge"
    ```

What Clear local cache and Start over remove, the library's data and then the app's directory:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:pending-photo-clear"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:pending-photo-clear"
    ```

    Start over clears the directory at its own clear step, right after the library's
    `clearLocalData()`.

## Paths that are never cached

An object path that cannot be mapped safely onto a file (a leading `/`, an empty, `.` or `..`
segment, a `\`, a control character) still downloads and writes, uncached, and `ensureCachedFile`
refuses it with `IllegalArgumentException`. So does a name holding `%`, `?` or `#` on supabase-kt
3.6, which puts the name in the URL as written: a `?` or `#` can fetch or overwrite a different
object (`x?y.png` fetches `x`, and an upload of it writes `x`), and a `%` that does not begin a
percent-escape (`100%.png`) cannot be put in a URL at all, so every call naming it throws
`IllegalArgumentException` before anything is sent. Keep these characters out of object names.

## Whose files they are

With Auth installed, the file cache belongs to one user at a time, like the query cache, and
`clearLocalData()` deletes every file ([Signing out and switching users](sign-out.md)). Its bound
is `fileCacheSizeBytes` ([Cache size and eviction](eviction.md#the-file-cache)); where the files
live is on [On-device data](../reference/on-device-data.md).

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
