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
- **New task**: the title and notes, and a **Needs a connection** switch; the app says whether the
  task reached the server or is saved on the device.
- **Task detail**: the title and notes, saved as you type; the done switch; Duplicate task; photos,
  which upload only while online, and links to all of them to share.
- **Photo viewer**: the photo, its Details (Storage's record and the copy on the device), Share,
  Share link, and Replace, Move or Duplicate it to another task.
- **Photos**: every photo across tasks, a page at a time, each marked when it is available offline.
- **Sync**: the network and a switch to simulate going offline; the **Prefer fresh data** and
  **Live updates** settings; each change waiting, with **Discard** and **Sync now**; the list's
  refresh state; the changes the server refused (dismiss or retry); what the server says, through a
  count and a database function; the files in the bucket; a **Try it** corner for the calls the
  library refuses or sends at once; and the two ways to start again: clearing the local cache, and
  starting over as a new anonymous user.

When something fails, the app says so in a message and carries on: a write the server refuses as
soon as it is sent, a refresh that cannot reach the server, a photo Storage will not take.

The [guided tour](tour.md) takes each screen in turn, with the calls behind it and the page that
explains each one; every docs page shows the samples' own code for the call it explains.

## The Android sample today

The Android sample runs on the library, against your own Supabase project: its lists are live
queries on the device's cache, its changes go through the mutation queue, and its photos through
the offline Storage bucket. [Run the Android sample](android.md#things-to-try) lists the things to
try, from going offline to starting over.

## The iOS sample

The iOS sample is the same app in SwiftUI, on the Swift package: the same live queries, mutation
queue and offline Storage bucket, through `SupabaseOfflineClient`. It has the Tasks and Sync
screens, task detail and New task; it has no Photos tab, and a swiped-away task is deleted without
an undo. The sync status sits above the tab bar. Without a project it runs in a demo mode kept in
memory. [Run the iOS sample](ios.md#things-to-try) lists the same things to try.
