# Guided tour

Offline Tasks calls every part of the library's public API from a screen you can see, so this page
is the API told as an app: each screen, what it shows, the calls behind it, and the page that
explains each call. Every call sits in the samples' data layer, and the docs pages show those very
lines. The Android and iOS samples are one product; where they differ, the table says so. After
the screens, a ten-minute walk through the app shows the library at work, with your project's
dashboard open beside it.

Two things hold on every screen. The client is built once, with every setting written out at its
default, and the app's own connectivity signal
([Configuration](../reference/configuration.md)). Every failure a call can throw goes through one
mapping, a sentence for each kind ([Conflicts and failures](../behaviors/failures.md)).

## Tasks

The list, open tasks first, with a search field and four chips above it.

| What it shows | The calls | On iOS | Explained on |
| --- | --- | --- | --- |
| The list, live, with a cloud mark on each row whose change waits | `selectAsFlow`, with `CacheFirstThenNetwork`, or `NetworkFirstThenCache` while the Sync tab's **Prefer fresh data** is on; `pendingMutations("tasks")` and its `rowKeys` | `selectAsSequence`, with `.cacheFirstThenNetwork` or `.networkFirstThenCache`; `pendingMutations(table: "tasks")` | [Reactive reads](../behaviors/reactive-reads.md#the-three-strategies), [Optimistic overlay](../behaviors/overlay.md) |
| Search, and the All / Open / Done / Recent chips, as filters the server applies | `ilike` on the title; `eq` and `is` on `done`; Recent as `gte` and `lt` on `created_at` in one `and` group | `ilike`; `neq` and `is` on `done`; Recent as `gte` and `lt`, which Swift sends in one group by itself | [Reactive reads](../behaviors/reactive-reads.md#filters-search-and-pages), [One-shot reads](../behaviors/read-strategies.md#edge-cases) |
| How many photos each task has | A live read of the photo rows with `in` on `task_id` | `in("task_id", values:)` on the live read | [Reactive reads](../behaviors/reactive-reads.md#filters-search-and-pages) |
| Pull to refresh, and the caption under the title: "Updated just now", "From this device, 3 minutes ago", and how many changes wait | `select(readStrategy = NetworkFirst(maxAge))` of the list shown, chip and search included, since each query is cached on its own, and the result's `isFromCache`, `fetchedAt` and `hasPendingMutations` | `select(readStrategy: .networkFirst(maxAge:))`, and the `OfflineResponse`'s `isFromCache`, `fetchedAt` and `hasPendingMutations` | [One-shot reads](../behaviors/read-strategies.md#where-the-rows-came-from) |
| Tick a task done | `update` with a JSON body | `update` with a `[String: OfflineJSON]` body | [Writes and the offline queue](../behaviors/mutations.md) |
| Swipe a task away, with Undo | Its photo files (online only); its photos still waiting to upload, on the device; then `delete` of its photo rows and of the task | The same, with no Undo | [Writes and the offline queue](../behaviors/mutations.md), [Storage caching](../behaviors/storage.md#writes) |
| **Mark all done**, in the menu | `rpc("complete_all_tasks")`, a database function, network only; then the list shown and the whole list are read again, since a function's changes reach the device only by a read | `rpc("complete_all_tasks")`, from the Tasks tab's menu | [Coming from supabase-kt](../getting-started/migrating.md#what-is-never-cached-or-queued) |
| An alert when the server refused a waiting change | `rejectedMutations().first()` at launch, then the flow | The same | [Conflicts and failures](../behaviors/failures.md#the-rejected-list) |
| The badges: open tasks on Tasks, waiting changes on Sync, red when one was refused | `select(readStrategy = CacheFirst(maxAge))` and `pendingMutationCount()` at launch, then the live list and `syncStatus` | `select(readStrategy: .cacheFirst(maxAge:))` and `pendingMutationCount()` at launch, then the live list and `syncStatus` | [One-shot reads](../behaviors/read-strategies.md), [Replaying the queue](../behaviors/sync.md#the-status) |

## New task

| What it shows | The calls | On iOS | Explained on |
| --- | --- | --- | --- |
| Title, notes, and **Needs a connection** | `insert(row, mutationStrategy) { select() }`: `QueueIfOffline`, the default, or `OnlineOnly` with the switch on, whose stored row the app then puts in the cache with `applyServerRecord`, since an online-only write is never patched in | `insert(row, mutationStrategy:).select().single()`: `.queueIfOffline` or `.onlineOnly`, then `applyServerRecord` of the stored row | [Writes and the offline queue](../behaviors/mutations.md#the-two-strategies) |
| "Saved at 10:42", or "saved on this device" | `MutationOutcome.Applied.result`, the row the server stored, or `Queued` | `MutationOutcome.applied(result:)`, the row the server stored, or `.queued(pendingMutation:)` | [Writes and the offline queue](../behaviors/mutations.md#what-a-write-returns) |
| **Add photo**, several at once, with their thumbnails, online or offline | None at first: once the task is saved, each photo is filed in the app's own pending-photo directory, and the uploader takes it from there (see Task detail) | The same, with a `PhotosPicker` of up to ten photos; a task that needs a connection and is not saved files none | [Storage caching](../behaviors/storage.md#photos-picked-offline-the-apps-own-queue) |

## Task detail

| What it shows | The calls | On iOS | Explained on |
| --- | --- | --- | --- |
| The task, even one never opened before, offline | The list's own query, with the row picked out on the device | The same | [FAQ](../help/faq.md#the-first-read-offline-is-empty) |
| Title and notes, saved as you type | `update` with a typed value | `update` with a typed `Encodable` patch that writes a cleared note as `null` | [Writes and the offline queue](../behaviors/mutations.md) |
| **Duplicate task**, in the menu | `upsert(row) { select() }`, then `isQueued` and `resultOrNull()` | `upsert(copy)`, under an id made from the task's, so a second copy writes over the first; then `isQueued` | [Writes and the offline queue](../behaviors/mutations.md) |
| **Delete task**, the bin beside the done button, offline too | As swiping a task away | The same, from the last row of the task's form | [Writes and the offline queue](../behaviors/mutations.md) |
| The photos; with Simulate offline on, "Showing photos saved on this device" | `selectAsFlow` of the photo list, `FlowReadStrategy.CacheOnly` while network access is off | `selectAsSequence` of the photo list, `.cacheOnly` while Simulate offline is on | [Reactive reads](../behaviors/reactive-reads.md#the-three-strategies) |
| **Add photo**, online or offline: the photo shows at once from the device, marked "Waiting to upload", "Uploading" (with its progress) or "Upload failed" with **Retry** and **Remove** | The app's pending-photo directory merged with the photo rows; the uploader watches `syncStatus.network`, then, one photo at a time, `exists` (on a retry), supabase-kt's `uploadAsFlow` on the offline bucket, `upsert` of the photo's row with `ignoreDuplicates`, and `ensureCachedFile` | In the task's Photos section, with no progress: the uploader follows `syncStatus`, then `exists(path:)` (on a retry), `upload(_:data:options:)` with `UploadOptions` (`contentType`, `upsert`, `userMetadata`), `upsert(_:onConflict:ignoreDuplicates:)` of the row and `ensureCachedFile(path:)` | [Storage caching](../behaviors/storage.md#photos-picked-offline-the-apps-own-queue) |
| **Share all photos of this task**, in the menu | `createSignedUrls` | `createSignedURLs(paths:expiresIn:)` | [Storage caching](../behaviors/storage.md#writes) |

## Photo viewer

| What it shows | The calls | On iOS | Explained on |
| --- | --- | --- | --- |
| The photo, offline too | `ensureLocalUri`; the thumbnails before it, `localUriIfCached` | `cachedFile`, else `ensureCachedFile`, read from `CachedFile.url` | [Storage caching](../behaviors/storage.md#cached-files) |
| **Details**: in the bucket or not, size, type, times, ETag, the public and authenticated URLs, and the copy on this device | `exists`, `info`, `publicUrl`, `authenticatedUrl`, `cachedFile` and its `fetchedAt` and `sizeBytes` | `exists`, `info`, `publicURL`, `authenticatedURL`, `cachedFile` and its `fetchedAt`, `sizeBytes` and `path` | [Storage caching](../behaviors/storage.md#writes) |
| **Share** | `ensureLocalFile`, copied out of the cache for the share sheet | `ensureCachedFile`'s `CachedFile.url`, handed to the share sheet | [Storage caching](../behaviors/storage.md#cached-files) |
| **Share link**: a copy 512 pixels wide, for an hour | `createSignedUrl` with an image transformation | `createSignedURL(path:expiresIn:transform:)` with an `ImageTransformation` | [Storage caching](../behaviors/storage.md#writes) |
| **Replace photo**, **Move to another task**, **Duplicate to another task**, **Delete photo** | `update`, `move` with an `update` of the row, `copy` with an `insert`, `delete`; all online only | `update(_:data:options:)`, `move(from:to:)` with an `update` of the row, `copy(from:to:)` with an `insert`, `remove(paths:)` | [Storage caching](../behaviors/storage.md#writes) |
| A photo waiting to upload: its own file, a line saying the bucket's actions wait for the upload, **Share**, and **Remove** (not while it uploads), with **Retry** once it failed, offline too | No library call: the file and its record are the app's | The same | [Storage caching](../behaviors/storage.md#photos-picked-offline-the-apps-own-queue) |

## Photos

| What it shows | The calls | On iOS | Explained on |
| --- | --- | --- | --- |
| Every photo, newest first, a page at a time, each marked when it is on the device | `selectAsFlow` with `order` and `range`; **Show more** reads a longer page | `selectAsSequence` with `order` and `limit` | [Reactive reads](../behaviors/reactive-reads.md#filters-search-and-pages) |
| The photos waiting to upload, among them with their marks | The app's pending-photo directory, merged with the page | The same | [Storage caching](../behaviors/storage.md#photos-picked-offline-the-apps-own-queue) |

## Sync

| Section | What it shows | The calls | On iOS | Explained on |
| --- | --- | --- | --- | --- |
| Connection | The network, and **Simulate offline** | `syncStatus.network`; `disableNetwork()` and `enableNetwork()` | The same | [Replaying the queue](../behaviors/sync.md#the-network-switch) |
| Settings | **Prefer fresh data**, and **Live updates** with where its channel stands | The list's read strategy; supabase-kt's Realtime, each change applied with `applyServerRecord` or `applyServerDelete`, typed for tasks and as JSON for photos | supabase-swift's Realtime (`RealtimeClientV2`), each change applied with `applyServerRecord` or `applyServerDelete`, typed for tasks and as `[String: OfflineJSON]` for photos | [Reactive reads](../behaviors/reactive-reads.md#the-three-strategies), [Applying Realtime events](../behaviors/realtime.md) |
| Waiting changes | The count per table, one row per change ("Added “Buy milk”, 2 minutes ago") with **Discard**, when it last synced or why it stopped, and **Sync now** with every count of its report | `pendingMutationCountFlow(table)`, `pendingMutations()`, `discardPendingMutation(id)`, `lastSyncedAt`, `lastSyncError`, `syncPendingMutations()` and its `SyncReport` | `pendingMutationCountSequence(table:)`, `pendingMutations()`, `discardPendingMutation(id:)`, then the same | [Writes and the offline queue](../behaviors/mutations.md#seeing-and-dropping-queued-writes), [Replaying the queue](../behaviors/sync.md) |
| Refresh | Where the list's refresh stands | `QuerySnapshot.refresh` | The same | [Refresh lifecycle](../behaviors/refresh-lifecycle.md) |
| Refused changes | Each refused change, when it was made and refused, its rows and the server's answer, with **Retry** (off when it cannot be retried) and **Dismiss** | `rejectedMutations()`, `retryRejectedMutation(id)`, `dismissRejectedMutation(id)` | The same, with what the change did, and the server's details and hint | [Conflicts and failures](../behaviors/failures.md#the-rejected-list) |
| Server | "On the server: 7 of 12 done · 3 this week" | A `count`, and `rpc("tasks_done_count", …)` with typed and JSON parameters | The same | [One-shot reads](../behaviors/read-strategies.md#edge-cases), [Coming from supabase-kt](../getting-started/migrating.md#what-is-never-cached-or-queued) |
| Storage | Your folders in the bucket with their files, and **Upload through a signed URL** | `list` with a limit, an offset, a sort and a search; `createSignedUploadUrl` and `uploadToSignedUrl` | **Browse photo files**: `list(path:options:)` with a `StorageListFilter`; the signed-URL upload is under Try it | [Storage caching](../behaviors/storage.md#writes) |
| Try it | **Queue an invalid title**, **Add five sample tasks**, **Import the starter list**, **Delete done tasks offline**, **Two filters on one column**, **Count open tasks on the server**; each says what it called and what happened | `insert` of a JSON body, `insert` of a list, `upsert` with `onConflict` and `ignoreDuplicates`, a grouped `delete` refused offline with `MutationNotQueueableException`, a read refused with `IllegalArgumentException`, a `count` with `filter(column, operator, value)` | **Count tasks with notes** (`filter("notes", operator: "not.is", value: "null")`), **Delete last week's done tasks** and **Upload through a signed URL** in place of the count, the grouped delete and the two filters, which Swift sends in one group | [Writes and the offline queue](../behaviors/mutations.md#two-filters-on-one-column), [One-shot reads](../behaviors/read-strategies.md#edge-cases) |
| Local data | **Clear local cache**, and **Start over** | `clearLocalData()`, then the app's pending-photo directory, which the library does not touch; Start over clears, deletes the user's files and rows `OnlineOnly`, clears the pending photos, then signs in as a new user | Clear local cache the same; Start over waits for a photo upload in progress, discards the user's waiting changes, deletes their files and rows `.onlineOnly`, turns the network off, signs out, clears the device and then the pending photos, signs in anew and turns the network back on | [Signing out and switching users](../behaviors/sign-out.md#deleting-an-account) |

Coming back to the app sends what waits: the app calls `syncPendingMutations()` as it comes to the
foreground ([Replaying the queue](../behaviors/sync.md#when-to-call-sync)).

## Ten minutes with the app

### Online basics

1. Open the app. It signs in anonymously and shows an empty list.
2. Add a task with **New task**: the app says it was saved, with the time the server stored it
   (the row's `updated_at`, which the server sets; `created_at` is the time the device made it).
   Open it, add some notes and tick it done.
3. In the dashboard, open the **Table Editor** and the `tasks` table: the row is there, with your
   anonymous user's id in `user_id` and the id the app made on the device in `id`.

Each change was sent at once, because nothing waited ahead of it.

### Live updates

With **Live updates** on (the Sync tab's Settings), change a task's title in the Table Editor. The
app shows the new title within a second or two: Realtime sent the row, and the app applied it to
the list it keeps on the device.

### Go offline

On the **Sync** tab, switch on **Simulate offline**. It turns the library's network access off,
as airplane mode would, for this app only and until you switch it back or the app restarts. The
banner and the subtitle say the app is offline, and **Sync now** is switched off.

### Write while offline

Add a task, clear another's notes, tick one done and delete one. Each change shows at once, with
a cloud mark on the rows whose change has not reached the server; the banner and the Sync tab's
badge count the changes waiting, and the Sync tab lists each one. Pull to refresh: the caption
under the title says the list is from this device, and how old it is.

Open a task you have never opened before: it opens offline too, because the app reads its list
once and finds the task in it. Tap a chip or search for a word you never asked for online: there is
nothing saved for it, and the app says so.

### Come back online

Switch **Simulate offline** off. The library sends the waiting changes by itself, in the order you
made them, and the count drops to **Nothing waiting**.

### Check the dashboard

Refresh the `tasks` table in the Table Editor: the new task is there, the notes are cleared, the
task is done, and the deleted one is gone. Each row's `updated_at` is the server's time, set when
the change arrived.

### Cold start offline

Simulate offline does not survive a restart, so use the device's airplane mode for this one.

1. Turn on airplane mode and close the app completely.
2. Open it again. The list is there, read from the device; the session is the same anonymous user,
   restored from the device too.
3. Add a task, then close the app again. The change is kept on the device.
4. Turn airplane mode off and open the app: the task is sent.

### Photos

1. Online, open a task and choose **Add photo**. The photo shows at once, marked while it
   uploads (on Android with its progress), and the mark goes once the upload is done.
2. Open the photo, then **Details**: the file is in the bucket and on this device. Try **Share
   link** and **Move to another task**.
3. Switch on **Simulate offline**, open **New task**, give it a title and **Add photo** (pick two),
   then save. Open the task: its photos show at once, from the device, each marked "Waiting to
   upload". The **Photos** tab has them too. Open one: the viewer shows it, and says why Details,
   Share link, Replace, Move and Duplicate need the photo on the server.
4. In the dashboard, the `task_photos` table and the bucket's folder for the task have nothing new:
   Storage writes are never queued, so the photos wait in the app's own queue.
5. Switch **Simulate offline** off. The queued task is sent and the photos upload one at a time;
   each photo's row follows once Storage has its file, behind its task's, and its mark goes.
   Refresh `task_photos` and the bucket: both photos are there.
6. Every other change to a photo (Replace, Move, Duplicate, Delete) still needs the network:
   offline they are switched off, and the photos already on the device still show.

### A refused write

On the Sync tab, under **Try it**, choose **Queue an invalid title** while offline. It adds a task
with a blank title, which the schema refuses, and the change waits like any other. Come back
online: the server refuses it (HTTP 400, Postgres code 23514), and it moves to **Refused changes**
with the server's answer and the Tasks tab says so. **Retry** sends it again; **Dismiss** removes
it.

Online, the same item's write is refused as soon as it is sent, and the line under it says why: a
write sent at once that the server refuses is thrown to the app, not listed.

### Writes the queue cannot hold

Still under **Try it**, with Simulate offline on, choose **Delete done tasks offline**. A delete
filtered by a group of conditions cannot wait in the queue, so the library refuses it at once and
says which kind of write it was; nothing is queued. **Two filters on one column** is refused online
and offline alike, before anything is sent. On iOS the item is **Delete last week's done tasks**,
refused offline in the same way, and there is no **Two filters on one column**: the Swift package
sends both conditions in one group by itself.

### Try a conflict

Two devices are two anonymous users, so play the second writer from the Table Editor. Switch
**Live updates** off first, so the app shows only what it reads.

1. Go offline in the app and tick a task done.
2. In the Table Editor, change the same task's title.
3. Come back online and pull to refresh. Both changes are there: the app's queued update sets
   only `done`, so it leaves the title the dashboard wrote.
4. Now go offline, change a task's title in the app, change the same title in the Table Editor,
   and come back online. The app's title wins: the last write to reach the server wins, and the
   app's arrived last.

The library does not merge or resolve conflicts; see
[Conflicts and failures](../behaviors/failures.md#conflicts).

### Start again

Under **Local data** on the Sync tab:

- **Clear local cache** deletes what the device holds, the queue and the photos waiting to upload
  included, then reads both lists again from your project.
- **Start over** deletes this user's photos, photo rows and tasks from your project, clears the
  device and the photos waiting to upload, and signs in as a new anonymous user. It needs the
  network.
