# Sample apps

The repository's sample app is **Offline Tasks**: a to-do list with photos that keeps working
offline. It is one product with two native expressions, on Android in Jetpack Compose with
Material 3 Expressive and on iOS in SwiftUI. Both use the same Supabase schema, and both show the
same offline behavior.

| | Where | Page |
| --- | --- | --- |
| The Supabase project both samples need | [`samples/supabase/`](https://github.com/nesfeder/supabase-offline-kt/tree/main/samples/supabase) | [Set up Supabase](supabase-setup.md) |
| The Android sample | [`samples/android/`](https://github.com/nesfeder/supabase-offline-kt/tree/main/samples/android) | [Run the Android sample](android.md) |
| The iOS sample | [`samples/ios/`](https://github.com/nesfeder/supabase-offline-kt/tree/main/samples/ios) | [Run the iOS sample](ios.md) |

## What the app shows

Every screen is built to make the library's offline behavior visible:

- **Tasks**: the list, with a subtitle and a banner that always say where syncing stands
  (offline, syncing, changes waiting, or showing saved data because the server cannot be reached),
  a mark on each task whose change has not reached the server yet, a search field and All / Open /
  Done / Recent chips the server applies, each task's photo count, swipe to delete with undo, pull
  to refresh with a caption saying where the rows came from, and Mark all done.
- **New task**: the title and notes, a **Needs a connection** switch, and **Add photo** for several
  photos at once; the app says whether the task reached the server or is saved on the device.
- **Task detail**: the title and notes, saved as you type; the done switch; **Delete task**;
  Duplicate task; and the task's photos, with **Add photo**. A photo added offline shows at once
  from the device, marked **Waiting to upload**, and uploads when the network is back; the menu
  shares links to all of a task's stored photos.
- **Photo viewer**: the photo, its Details (Storage's record and the copy on the device), Share,
  Share link, and Replace, Move or Duplicate it to another task; for a photo still waiting to
  upload, Share, Retry and Remove.
- **Photos**: every photo across tasks, a page at a time, each marked when it is available offline
  or still waiting to upload.
- **Sync**, in sections: **Connection** (the network, and **Simulate offline**); **Settings**
  (**Prefer fresh data**, **Live updates**); **Waiting changes**, each with **Discard**, and
  **Sync now**; **Refresh**, where the list's refresh stands; **Refused changes**, to retry or
  dismiss; **Server**, a count and a database function; **Storage**, the files in the bucket;
  **Try it**, the calls the library refuses or sends at once; **Local data**, to clear the local
  cache or start over as a new anonymous user; and **About this build**, whom the app is signed in
  as.

When something fails, the app says so in a message and carries on: a write the server refuses as
soon as it is sent, a refresh that cannot reach the server, a photo Storage will not take.

The [guided tour](tour.md) takes each screen in turn, with the calls behind it and the page that
explains each one; every docs page shows the samples' own code for the call it explains.

## The Android sample

The Android sample runs on the library, against your own Supabase project: its lists are live
queries on the device's cache, its changes go through the mutation queue, and its photos through
the offline Storage bucket. [Run the Android sample](android.md#things-to-try) lists the things to
try, from going offline to starting over.

## The iOS sample

The iOS sample is the same app in SwiftUI, on the Swift package: the same live queries, mutation
queue and offline Storage bucket, through `SupabaseOfflineClient`, and every screen above. A
swiped-away task is deleted without an undo, a photo uploads without a progress bar, **Delete
task** is the last row of the task's form, and the sync status sits above the tab bar. Without a project it runs in a demo mode kept in memory, whose
stand-in server answers every call and reports changes as Realtime would.
[Run the iOS sample](ios.md#things-to-try) lists the same things to try.
