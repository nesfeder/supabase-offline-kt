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

| What it shows | The calls | Explained on |
| --- | --- | --- |
| The list, live, with a cloud mark on each row whose change waits | `selectAsFlow`, with `CacheFirstThenNetwork`, or `NetworkFirstThenCache` while the Sync tab's **Prefer fresh data** is on; `pendingMutations("tasks")` and its `rowKeys` | [Reactive reads](../behaviors/reactive-reads.md#the-three-strategies), [Optimistic overlay](../behaviors/overlay.md) |
| Search, and the All / Open / Done / Recent chips, as filters the server applies | `ilike` on the title; `eq` and `is` on `done`; Recent as `gte` and `lt` on `created_at` in one `and` group | [Reactive reads](../behaviors/reactive-reads.md#filters-search-and-pages), [One-shot reads](../behaviors/read-strategies.md#edge-cases) |
| How many photos each task has | A live read of the photo rows with `in` on `task_id` | [Reactive reads](../behaviors/reactive-reads.md#filters-search-and-pages) |
| Pull to refresh, and the caption under the title: "Updated just now", "From this device, 3 minutes ago", and how many changes wait | `select(readStrategy = NetworkFirst(maxAge))`, and the result's `isFromCache`, `fetchedAt` and `hasPendingMutations` | [One-shot reads](../behaviors/read-strategies.md#where-the-rows-came-from) |
| Tick a task done | `update` with a JSON body | [Writes and the offline queue](../behaviors/mutations.md) |
| Swipe a task away, with Undo | Its photo files (online only), then `delete` of its photo rows and of the task | [Writes and the offline queue](../behaviors/mutations.md), [Storage caching](../behaviors/storage.md#writes) |
| **Mark all done**, in the menu | `rpc("complete_all_tasks")`, a database function, network only | [Coming from supabase-kt](../getting-started/migrating.md#what-is-never-cached-or-queued) |
| An alert when the server refused a waiting change | `rejectedMutations().first()` at launch, then the flow | [Conflicts and failures](../behaviors/failures.md#the-rejected-list) |
| The badges: open tasks on Tasks, waiting changes on Sync, red when one was refused | `select(readStrategy = CacheFirst(maxAge))` and `pendingMutationCount()` at launch, then the live list and `syncStatus` | [One-shot reads](../behaviors/read-strategies.md), [Replaying the queue](../behaviors/sync.md#the-status) |

## New task

| What it shows | The calls | Explained on |
| --- | --- | --- |
| Title, notes, and **Needs a connection** | `insert(row, mutationStrategy) { select() }`: `QueueIfOffline`, the default, or `OnlineOnly` with the switch on, whose stored row the app then puts in the cache with `applyServerRecord`, since an online-only write is never patched in | [Writes and the offline queue](../behaviors/mutations.md#the-two-strategies) |
| "Saved at 10:42", or "saved on this device" | `MutationOutcome.Applied.result`, the row the server stored, or `Queued` | [Writes and the offline queue](../behaviors/mutations.md#what-a-write-returns) |

## Task detail

| What it shows | The calls | Explained on |
| --- | --- | --- |
| The task, even one never opened before, offline | The list's own query, with the row picked out on the device | [FAQ](../help/faq.md#the-first-read-offline-is-empty) |
| Title and notes, saved as you type | `update` with a typed value | [Writes and the offline queue](../behaviors/mutations.md) |
| **Duplicate task**, in the menu | `upsert(row) { select() }`, then `isQueued` and `resultOrNull()` | [Writes and the offline queue](../behaviors/mutations.md) |
| The photos; with Simulate offline on, "Showing photos saved on this device" | `selectAsFlow` of the photo list, `FlowReadStrategy.CacheOnly` while network access is off | [Reactive reads](../behaviors/reactive-reads.md#the-three-strategies) |
| **Add photo**, with its progress | supabase-kt's `uploadAsFlow` on the offline bucket, then `insert` of the photo's row | [Storage caching](../behaviors/storage.md#writes) |
| **Share all photos of this task**, in the menu | `createSignedUrls` | [Storage caching](../behaviors/storage.md#writes) |

## Photo viewer

| What it shows | The calls | Explained on |
| --- | --- | --- |
| The photo, offline too | `ensureLocalUri`; the thumbnails before it, `localUriIfCached` | [Storage caching](../behaviors/storage.md#cached-files) |
| **Details**: in the bucket or not, size, type, times, ETag, the public and authenticated URLs, and the copy on this device | `exists`, `info`, `publicUrl`, `authenticatedUrl`, `cachedFile` and its `fetchedAt` and `sizeBytes` | [Storage caching](../behaviors/storage.md#writes) |
| **Share** | `ensureLocalFile`, copied out of the cache for the share sheet | [Storage caching](../behaviors/storage.md#cached-files) |
| **Share link**: a copy 512 pixels wide, for an hour | `createSignedUrl` with an image transformation | [Storage caching](../behaviors/storage.md#writes) |
| **Replace photo**, **Move to another task**, **Duplicate to another task**, **Delete photo** | `update`, `move` with an `update` of the row, `copy` with an `insert`, `delete`; all online only | [Storage caching](../behaviors/storage.md#writes) |

## Photos

| What it shows | The calls | Explained on |
| --- | --- | --- |
| Every photo, newest first, a page at a time, each marked when it is on the device | `selectAsFlow` with `order` and `range`; **Show more** reads a longer page | [Reactive reads](../behaviors/reactive-reads.md#filters-search-and-pages) |

## Sync

| Section | What it shows | The calls | Explained on |
| --- | --- | --- | --- |
| Connection | The network, and **Simulate offline** | `syncStatus.network`; `disableNetwork()` and `enableNetwork()` | [Replaying the queue](../behaviors/sync.md#the-network-switch) |
| Settings | **Prefer fresh data**, and **Live updates** with where its channel stands | The list's read strategy; supabase-kt's Realtime, each change applied with `applyServerRecord` or `applyServerDelete`, typed for tasks and as JSON for photos | [Reactive reads](../behaviors/reactive-reads.md#the-three-strategies), [Applying Realtime events](../behaviors/realtime.md) |
| Waiting changes | The count per table, one row per change ("Added “Buy milk”, 2 minutes ago") with **Discard**, when it last synced or why it stopped, and **Sync now** with every count of its report | `pendingMutationCountFlow(table)`, `pendingMutations()`, `discardPendingMutation(id)`, `lastSyncedAt`, `lastSyncError`, `syncPendingMutations()` and its `SyncReport` | [Writes and the offline queue](../behaviors/mutations.md#seeing-and-dropping-queued-writes), [Replaying the queue](../behaviors/sync.md) |
| Refresh | Where the list's refresh stands | `QuerySnapshot.refresh` | [Refresh lifecycle](../behaviors/refresh-lifecycle.md) |
| Refused changes | Each refused change, when it was made and refused, its rows and the server's answer, with **Retry** (off when it cannot be retried) and **Dismiss** | `rejectedMutations()`, `retryRejectedMutation(id)`, `dismissRejectedMutation(id)` | [Conflicts and failures](../behaviors/failures.md#the-rejected-list) |
| Server | "On the server: 7 of 12 done · 3 this week" | A `count`, and `rpc("tasks_done_count", …)` with typed and JSON parameters | [One-shot reads](../behaviors/read-strategies.md#edge-cases), [Coming from supabase-kt](../getting-started/migrating.md#what-is-never-cached-or-queued) |
| Storage | Your folders in the bucket with their files, and **Upload through a signed URL** | `list` with a limit, an offset, a sort and a search; `createSignedUploadUrl` and `uploadToSignedUrl` | [Storage caching](../behaviors/storage.md#writes) |
| Try it | **Queue an invalid title**, **Add five sample tasks**, **Import the starter list**, **Delete done tasks offline**, **Two filters on one column**, **Count open tasks on the server**; each says what it called and what happened | `insert` of a JSON body, `insert` of a list, `upsert` with `onConflict` and `ignoreDuplicates`, a grouped `delete` refused offline with `MutationNotQueueableException`, a read refused with `IllegalArgumentException`, a `count` with `filter(column, operator, value)` | [Writes and the offline queue](../behaviors/mutations.md#two-filters-on-one-column), [One-shot reads](../behaviors/read-strategies.md#edge-cases) |
| Local data | **Clear local cache**, and **Start over** | `clearLocalData()`; Start over clears, deletes the user's files and rows `OnlineOnly`, then signs in as a new user | [Signing out and switching users](../behaviors/sign-out.md#deleting-an-account) |

Coming back to the app sends what waits: the app calls `syncPendingMutations()` as it comes to the
foreground ([Replaying the queue](../behaviors/sync.md#when-to-call-sync)).

## Ten minutes with the app

### Online basics

1. Open the app. It signs in anonymously and shows an empty list.
2. Add a task with **New task**: the app says it was saved, with the time the server stored it.
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

1. Online, open a task and choose **Add photo**. The upload shows its progress, and the photo joins
   the task once its row is saved.
2. Open the photo, then **Details**: the file is in the bucket and on this device. Try **Share
   link** and **Move to another task**.
3. Open the **Photos** tab: a photo that has been shown is marked as available offline.
4. Go offline. **Add photo** and every change to a photo are switched off, because Storage writes
   are never queued, and the photos already on the device still show.

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
and offline alike, before anything is sent.

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

- **Clear local cache** deletes what the device holds, the queue included, then reads both lists
  again from your project.
- **Start over** deletes this user's photos, photo rows and tasks from your project, clears the
  device and signs in as a new anonymous user. It needs the network.
