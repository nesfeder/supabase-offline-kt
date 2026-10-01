# Sample apps

The repository's sample app is **Offline Tasks**: a to-do list with photos that keeps working
offline. It is one product with two native expressions, on Android in Jetpack Compose with
Material 3 Expressive and on iOS in SwiftUI. Both use the same Supabase schema, and both show the
same offline behavior.

| | Where | Page |
| --- | --- | --- |
| The Supabase project both samples need | [`samples/supabase/`](https://github.com/nesfeder/supabase-offline-kt/tree/main/samples/supabase) | [Set up Supabase](supabase-setup.md) |
| The Android sample | [`samples/android/`](https://github.com/nesfeder/supabase-offline-kt/tree/main/samples/android) | [Run the Android sample](android.md) |
| The iOS sample | being rebuilt | [Run the iOS sample](ios.md) |

## What the app shows

Every screen is built to make the library's offline behavior visible:

- **Tasks**: the list, with a subtitle and a banner that always say where syncing stands
  (offline, syncing, changes waiting, or showing saved data because the server cannot be reached),
  a mark on each task whose change has not reached the server yet, swipe to delete with undo, and
  pull to refresh.
- **Task detail**: the title and notes, saved as you type; the done switch; photos, which upload
  only while online.
- **Photos**: every photo across tasks, each marked when it is available offline.
- **Sync**: the network, a switch to simulate going offline, the changes waiting per table with
  **Sync now**, the list's refresh state, the changes the server refused (dismiss or retry), and
  the two ways to start again: clearing the local cache, and starting over as a new anonymous user.

When something fails, the app says so in a message and carries on: a write the server refuses as
soon as it is sent, a refresh that cannot reach the server, a photo Storage will not take.

The [guided tour](tour.md) walks through these in order.

## The Android sample today

The Android sample runs against an in-memory stand-in for the library, with a simulated server, so
every screen and offline state can be tried before the library's 0.1.0 API is final. The app
already signs in anonymously to your Supabase project; its tasks move to the library, and to that
project, in the change that adopts the 0.1.0 API.
