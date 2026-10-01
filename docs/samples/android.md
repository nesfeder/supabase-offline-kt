# Run the Android sample

Building and running Offline Tasks on Android.

> **Today the sample's tasks live in memory.** It runs against an in-memory stand-in for the
> library, with a simulated server, until the library's 0.1.0 API is final. Your Supabase keys are
> already used to sign in anonymously; the tasks themselves reach your project once the sample
> adopts the library.

## Prerequisites

- JDK 17.
- The Android SDK with platform 37 (Android 17). Android Studio installs it.
- A device or emulator on Android 7.0 (API 24) or later. Dynamic color needs Android 12.
- A Supabase project with the samples' schema: [Set up Supabase](supabase-setup.md).

## Add your keys

Put the project URL and its publishable key in `local.properties` at the repository root, beside
`sdk.dir` (see `local.properties.example`):

```properties
sdk.dir=/path/to/your/Android/sdk
SUPABASE_URL=https://<ref>.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_…
```

Git ignores `local.properties`, so the keys never reach a commit. The build copies them into the
app's `BuildConfig`. Without them the app still builds and runs, and its Sync screen says the
project is not configured.

## Build and run

From the repository root, with a device connected or an emulator running:

```bash
./gradlew :samples:android:installDebug
```

Or open the repository in Android Studio and run the app module in `samples/android`.

The first launch signs in anonymously and shows a few sample tasks. Then try the Sync tab's
**Simulate offline** switch, change some tasks, and switch it off again.

The Sync tab's **Try it** group shows how failures reach you. **Queue an invalid title** online is
refused as soon as it is sent, and the app says why in a message; offline, the same write is
refused when the queue replays and waits under **Refused changes**. **Make the server
unreachable** stalls the refresh, and **Make Storage refuse** fails photo uploads, downloads and
deletes.

## Troubleshooting

- **The Sync screen says Supabase is not configured.** `local.properties` has no
  `SUPABASE_URL` or `SUPABASE_PUBLISHABLE_KEY`. Add them and build again.
- **It cannot sign in.** Check that anonymous sign-ins are on (Authentication, Sign In /
  Providers) and that the project is not paused: a free project pauses after a week without
  activity ([If the project pauses](supabase-setup.md#if-the-project-pauses)). The app tries
  again each time the network comes back.
- **The build asks for platform 37.** Install Android SDK Platform 37 in Android Studio's SDK
  Manager.
