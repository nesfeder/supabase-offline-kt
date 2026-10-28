# Storage caching

A download through the offline bucket goes to the network first and is kept on disk; when the
network fails, the kept copy is served instead. An upload made with a `MutationStrategy` can wait
in the mutation queue with the row writes around it, its bytes on the device
([below](#queued-uploads)). Every other write (an upload without one, updates, deletes, moves and
copies) is online only and never queued, and one that reaches the server drops the cached copies it
made stale.

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
is answered from the cached copy, or thrown when there is none. An image transform is cached per
transform, apart from the original. A channel download streams through a file on disk rather than
memory, and its answer's status is checked first: an error answer throws as the bytes download
does, and nothing of it is cached or handed on. A download whose `httpOverride` asks for part of
the object (a `Range` or `If-Range` header) or none of it (a `HEAD`) is the caller's alone: it is
never cached, and offline it is not answered from the whole copy. A download still on the wire
when a write of the same object lands through the offline bucket is handed to its caller but never
becomes the cached copy.

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
cache's own file at an absolute `path`, with `fetchedAt` and `sizeBytes`. `cachedFile(path) { … }`
returns the copy of one image transform instead, the one a download with the same
`transform { … }` keeps, so a cached thumbnail can be handed to an image loader as a file; a
transform that sets nothing is the untransformed copy, as it is for a download. A moved object's
copy keeps the `fetchedAt` of the download that made it. Read it or hand it to an image loader, but
do not move or write it: the cache may delete it when the object is uploaded, updated, deleted,
moved or copied over through the offline bucket, when the server reports it gone, when the cache
passes its bound and the file has not been used for 30 seconds, when the cache passes to another
user, when it is evicted or the file cache cleared ([below](#evicting-and-clearing)), and when
local data is cleared. A file whose size on disk is not the one recorded for it, which a power loss
right after a download can leave, is deleted too, and the object is downloaded again when next
asked for. Open it soon, or copy it if it must outlive those. **Nothing revalidates a cached
file**: compare `fetchedAt` with your own rule, and download again when it is too old.

On Android, four helpers give the cached copy in the platform's terms: `ensureLocalUri` and
`ensureLocalFile` are `ensureCachedFile` as a `Uri` or a `File`, throwing what it throws, and
`localUriIfCached` is `cachedFile` as a `Uri`, with a transform or without, and `localFileIfCached`
as a `File`; on iOS, `ensureLocalPath(path)` and `localPathIfCached(path)` give the absolute path
the same way, and `localPathIfCached(path) { … }` a transform's. The sample draws thumbnails from
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

## Evicting and clearing

```kotlin
--8<-- "StorageExamples.kt:evict-and-clear"
```

Three calls delete cached files on the device and send nothing; the objects stay in their buckets,
and the next download of each fetches it again. A bucket's `evictCachedFile(path)` deletes one
object's copies, the untransformed one and every image transform, and no other object's;
`evictCachedFile(path) { … }` deletes one transform's copy alone. `offline.storage.clearFileCache()`
deletes every downloaded file in every bucket, with its record, and leaves the mutation queue, the
cached query results and the rejected and conflicted lists: it frees the device's storage without
dropping writes waiting to sync, so a "Free up space" button can call it. At sign-out, call
`clearLocalData()` instead ([Signing out and switching users](sign-out.md)): it deletes these
files too, with the user's queued writes, and rewrites the database without what it deleted, which
`clearFileCache()` does not.

A download still on the wire when its copies are evicted is handed to its caller but not cached,
as when the object is written; an `ensureCachedFile` running meanwhile downloads it once more and
caches that. A download in flight when the file cache is cleared is handed to its caller, or fails,
but is not cached, as across `clearLocalData()`: an `ensureCachedFile` running meanwhile throws
`IllegalStateException`. The Swift package has the same calls: `evictCachedFile(path:)`,
`evictCachedFile(path:transform:)` and `storage.clearFileCache()`.

## Queued uploads

```kotlin
--8<-- "StorageExamples.kt:queued-upload"
```

`upload(path, data, mutationStrategy)` makes an upload as a row write is made
([Writes and the offline queue](mutations.md#storage-uploads-in-the-queue)). Under
`QueueIfOffline` the bytes are first written to the device and the upload queued, then it is sent
at once when nothing of the user waits ahead of it: the result is `UploadOutcome.Applied` with
Storage's response. Otherwise it waits and is `UploadOutcome.Queued`, with a `PendingUpload`: no
connection or no answer, an answer that says the request did not finish (a `408`, `429`, `502`,
`503`, `504`, `520`, `522` or `524`), a token Storage refused, earlier writes still pending, network
access off, or a session still restoring. A later pass sends it in the order it was made among the
user's writes, so a row written after it that names the file reaches the server after the file, and
one written before it, a task the photo belongs to, say, before it. Sent at once and refused, or
failing with another `5xx`, it throws Storage's error and is not kept. `OnlineOnly` is the plain
upload, its response wrapped in `Applied`.

- **Where the bytes wait**: a file of their own under `storage-uploads/` in the data directory
  ([On-device data](../reference/on-device-data.md#the-files)), written through to the disk before
  the upload is queued. The file cache's bound never evicts it and `clearFileCache()` leaves it.
- **While it waits**, `cachedFile(path)` and `ensureCachedFile(path)` return that file, with no
  request, and so do the platform helpers built on them, so a photo shows at once: the newest
  upload of the path wins over any cached copy, and its `fetchedAt` is when it was queued. A
  refused upload's file is never answered there, since Storage may hold other bytes at the path:
  its entry's `localPath` names it, for the app to show what was refused. Once Storage has the
  file its bytes become the object's cached copy, and the file under `storage-uploads/` goes. A transform's copy is never answered from it, and a download still asks
  the server, which does not hold the file yet.
- **Options**: `upsert`, `contentType` and `userMetadata` are kept with it. An upload whose options
  add an `httpOverride`, one through a bucket with a header set by `setHeader`, one whose user
  cannot be told, one whose bytes the device cannot write, and one on its way into the queue while
  local data is cleared are sent at once instead; offline they throw
  `MutationNotQueueableException` with the connectivity failure as its cause.
- **Replay**: it is a write of the user's queue, sent with that user's token and never another's,
  through the same order and the same stops as a row ([Replaying the queue](sync.md#what-happens-to-each-write)).
  An upload whose earlier send's answer was lost is sent again. When Storage refuses that send, as
  it refuses a duplicate of an upload that may not overwrite, the upload counts as sent only when
  the object at its path is verifiably its bytes: the same size, and the same MD5 by Storage's
  ETag or, when the ETag is not an MD5 one, the same bytes downloaded. Any other object there
  leaves the refusal standing, on the rejected list, and its cached copy is never the device's
  bytes.
- **Refusals**: an upload Storage refuses for good moves to the rejected list with its bytes, as
  `storage.rejectedUploads()` lists it, with Storage's status, error name (`code`) and message, and
  the file's path on the device as `localPath` while it is whole;
  `retryRejectedMutation(id)` queues it again at the end of the queue, and
  `dismissRejectedMutation(id)` drops it with its file. A refused upload does not hold the writes
  behind it: a row that names its file is still sent, so show the refusal and let the user retry.

```kotlin
--8<-- "StorageExamples.kt:queued-upload-lists"
```

`storage.pendingUploads()` lists the current user's uploads still to send, in queue order, each a
`PendingUpload` with its `id`, `bucketId`, `path`, `createdAt`, `sizeBytes`, `contentType` and
`upsert`; `discardPendingMutation(id)` drops one, with its file. They count as writes in
`syncStatus` and in `pendingMutationCount()` with no table; `pendingMutations()` lists the row
writes beside them. They stay with the user who made them across a sign-out, as queued rows do,
and go with `clearLocalData()` ([Signing out and switching users](sign-out.md)). Resumable uploads,
and every other write, are never queued.

## Writes

Uploads without a strategy, updates, deletes, moves and copies are online only, and nothing is
queued. Offline, a write is tried whatever the connectivity monitor says and fails with
supabase-kt's own connectivity error; with network access off, every Storage call but a download throws
`NetworkDisabledException` and sends nothing, and a download is answered from the file cache (or
throws `NetworkDisabledException` with nothing cached). A write that reaches the server drops the
cached copies it made stale, a resumable upload once it finishes. Such an upload does not put its
bytes in the cache, so a file uploaded just before the device goes offline is not available offline
until it is downloaded; a [queued upload](#queued-uploads) does. The Android sample adds a photo
through the queued upload, the iOS sample from its own queue of photos waiting to upload
([below](#photos-picked-offline)); both replace a file in place, and move or copy one to another
task's folder, writing the photo's row too, since the schema ties a row to its path:

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

## Photos picked offline

The Android sample adds every photo through the queued upload ([above](#queued-uploads)); the iOS
sample keeps a queue of its own, written before the library could queue an upload.

### Android: the queued upload

- **Every photo goes through it**: New task's **Add photo**, several at once with their thumbnails,
  adds its photos once the task is added (a queued insert offline), and the task detail's **Add
  photo** adds one the same way. Each is `upload(path, jpeg, QueueIfOffline)` of the photo's file
  at `<user id>/<task id>/<photo id>.jpg` (the ids made on the device), through a bucket without the
  app's `setHeader`, since a bucket with a header sends an upload at once instead of queuing it;
  then the `task_photos` row, a write queued behind the file. Offline both wait, and a later sync
  sends the task, the file and the row in that order. Online the file is sent at once and the row
  after it, from the repository's scope rather than the screen's, and the photo shows meanwhile,
  marked waiting; a refusal then is the app's error message, and nothing is kept. The mark has no
  progress yet.
- **Shown from the device at once.** The queued row is in the photo lists as any queued write is,
  and its file is drawn from `cachedFile`, which answers the queued copy; a refused one is drawn
  from its entry's `localPath`, which `cachedFile` never answers. The lists mark a photo
  "Waiting to upload" while `storage.pendingUploads()` holds its path, and "Upload failed", with
  Storage's message, while `storage.rejectedUploads()` does. A failed one offers **Retry**
  (`retryRejectedMutation`, switched off when its file is gone) and **Remove**, a waiting one
  **Remove**. The viewer hides Details, Share link, Replace, Move and Duplicate for either, which
  need the object in the bucket, with a line saying so.
- **Failures.** A file whose answer was lost is sent again, and Storage's duplicate refusal counts
  it stored once the object at its path is the photo's bytes. One Storage refuses on replay keeps its file on the rejected list; its row,
  queued behind it, is sent all the same, so the photo stays listed, marked failed, until Retry or
  Remove.
- **Deletes and clears.** Remove discards a waiting upload, or dismisses a refused one, with the
  row's queued writes; then it deletes the row and, online, the file, wherever an upload that ended
  meanwhile left them. A photo still being added is removed once the add ends. Deleting a task
  discards its photos' waiting uploads first, since a discard waits for one on the wire, then
  deletes its files, one that landed meanwhile too, then dismisses its refused uploads. Start over
  discards the waiting uploads the same way before `clearLocalData()`, which does not wait for an
  upload on the wire, and its deletes. Clear local cache needs nothing more than
  `clearLocalData()`, which drops the queued uploads with their files.

From the samples: [TaskDetailViewModel.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/ui/detail/TaskDetailViewModel.kt) · [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt)

```kotlin
--8<-- "ui/detail/TaskDetailViewModel.kt:pending-photo-add"

--8<-- "data/supabase/OfflineTaskRepository.kt:pending-photo-add"

--8<-- "data/supabase/OfflineTaskRepository.kt:pending-photo-merge"

--8<-- "data/supabase/OfflineTaskRepository.kt:pending-photo-upload"

--8<-- "data/supabase/OfflineTaskRepository.kt:pending-photo-clear"
```

### iOS: the app's own queue

The iOS sample holds a photo in a queue of its own until the upload lands, and then references the
library's cached copy. Its uploads are made without a strategy, so they need a connection, as
[Writes](#writes) says.

- **A directory the app owns**, in Application Support beside the library's data directory: per
  photo, a JPEG named by the photo's id and, written after it, a small JSON record with the task's
  id, the photo's id, its Storage path, when it was picked, its state (waiting, uploading, or
  failed with the app's sentence and the number of tries) and its content type.
- **Shown from the device at once.** The task's photos and the Photos tab merge the `task_photos`
  rows with the records, each photo once, marked "Waiting to upload", "Uploading" or "Upload
  failed", with **Retry** and **Remove**.
- **One uploader** follows `syncStatus` and uploads the waiting photos one at a time, oldest
  first: the file, then, only once Storage has it, the row through the library, then
  `ensureCachedFile`, then the app's file and record are deleted. A retry first asks Storage
  whether an earlier try stored the file, and the row is an `upsert` that ignores a duplicate.
  Through the Swift package Storage's refusal keeps its own `Duplicate` code whatever the HTTP
  status, so the upload's "already exists" counts as stored there too.
- **Deletes and clears.** Deleting a task deletes its pending photos, and Remove deletes one. Clear
  local cache and Start over delete the whole directory, which `clearLocalData()` never touches;
  Start over first waits for an upload in progress.

From the samples: [PendingPhotoQueue.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/PendingPhotoQueue.swift) · [TaskDetailModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Detail/TaskDetailModel.swift) · [TaskRepository+PendingPhotos.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+PendingPhotos.swift) · [TaskRepository+Photos.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository+Photos.swift) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

```swift
--8<-- "Data/PendingPhotoQueue.swift:pending-photo-record"

--8<-- "Detail/TaskDetailModel.swift:pending-photo-add"

--8<-- "Data/TaskRepository+PendingPhotos.swift:pending-photo-add"

--8<-- "Data/TaskRepository+PendingPhotos.swift:pending-photo-upload"

--8<-- "Data/TaskRepository+Photos.swift:upload"

--8<-- "Data/TaskRepository+Photos.swift:pending-photo-upload"

--8<-- "Data/TaskRepository+PendingPhotos.swift:pending-photo-merge"

--8<-- "Data/TaskRepository.swift:pending-photo-clear"
```

## Paths that are never cached

An object path that cannot be mapped safely onto a file (a leading `/`, an empty, `.` or `..`
segment, a `\`, a control character) still downloads and writes, uncached, and `ensureCachedFile`
refuses it with `IllegalArgumentException`. So does a name holding `%`, `?` or `#` on supabase-kt
3.6, which puts the name in the URL as written: a `?` or `#` can fetch or overwrite a different
object (`x?y.png` fetches `x`, and an upload of it writes `x`), and a `%` that does not begin a
percent-escape (`100%.png`) cannot be put in a URL at all, so every call naming it throws
`IllegalArgumentException` before anything is sent. Keep these characters out of object names. A
segment that starts with a letter and a colon, like `u:42`, is cached, under a hash of the name
([On-device data](../reference/on-device-data.md#the-files)).

## Whose files they are

With Auth installed, the file cache belongs to one user at a time, like the query cache, and
`clearLocalData()` deletes every file ([Signing out and switching users](sign-out.md)); a download
in flight keeps its temporary file until it ends, then deletes it. A queued upload's file belongs to
the user who made it, as the upload does: it stays when the file cache passes to another user, and
goes with `clearLocalData()`. Its bound is
`fileCacheSizeBytes` ([Cache size and eviction](eviction.md#the-file-cache)); where the files live
is on [On-device data](../reference/on-device-data.md).

## Behavior matrix

Facts about the engine; one row per condition, the conditions those of the
[one-shot read matrix](read-strategies.md#behavior-matrix), with warm and cold saying whether the
object is cached. Writes are `upload`, `update`, `delete`, `move`, `copy` and the resumable and
signed-URL uploads; an upload made with a strategy follows the queue instead
([Queued uploads](#queued-uploads)), and while it waits `cachedFile` and `ensureCachedFile`
answer its file under every condition.

| Condition | `downloadAuthenticated` / `downloadPublic` | `ensureCachedFile` | `cachedFile` | Writes |
|---|---|---|---|---|
| **Online** | The server's bytes, written to the cache whole or not at all. Not found: the cached copy is dropped and the error thrown. | The cached file; if there is none, the object is downloaded to disk once. | The cached file or `null`; never a request. | Sent; the cached copy of what they change is dropped. A write the server rejected keeps it. |
| **Offline, warm** | The cached bytes. | The cached file, no request. | The cached file. | Throw the connectivity failure; the cache is left as it was. |
| **Offline, cold** | Throws the failure. | Throws the failure. | `null`. | As offline, warm. |
| **Session restoring** | Not held for the session (nor after its refresh failed): sent at once on the anon key, and its answer cached. A `401` or `403` keeps the cached copy; cold, the refusal is thrown. Storage answers an object the anon key may not read as "not found", which keeps the cached copy too and is answered from it; cold, it is thrown: download private objects after `supabase.auth.awaitInitialization()`. | As a download. | Unchanged: never a request. | Sent at once on the anon key; a refusal keeps the cached copy. |
