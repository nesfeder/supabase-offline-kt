# Run the Android sample

Building and running Offline Tasks on Android, and what to try once it runs.

The app runs on the library against your own Supabase project. Its lists are live queries on the
device's cache, every change goes through the library's queue, and photos go through the offline
Storage bucket, so what you do offline reaches your project when the network comes back.

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
app's `BuildConfig`. Without them the app still builds, and opens on a screen that names the two
keys to add.

## Build and run

From the repository root, with a device connected or an emulator running:

```bash
./gradlew :samples:android:installDebug
```

Or open the repository in Android Studio and run the app module in `samples/android`.

The first launch signs in anonymously and opens on an empty list: add a task with **New task**.
Every row and photo belongs to that anonymous user, and the session is kept on the device, so the
next launch is the same user, offline too.

If the device is offline on the very first launch, there is no user yet. The list says it is not
signed in and **New task** is hidden: every row needs its user, and only the server can create
one. The app signs in as soon as it reaches the internet.

![The task list before the first sign-in, offline: "Not signed in yet", and no New task button](../assets/samples/android/not-signed-in.png){ width="270" loading=lazy }

## Things to try

### Go offline and keep working

On the Sync tab, switch on **Simulate offline**. It cuts this app off from the server, as airplane
mode would, until you switch it back or the app restarts; the rest of the device stays online.

Now add a task, clear another's notes, tick one done and delete one. Each change shows at once.
A cloud mark on a row means its change has not reached the server yet; the banner and the Sync
tab's badge count the changes waiting. Pull to refresh, and the app says it is showing what is
saved on the device. A task you have never opened still opens offline: it is read from the list
the device already holds.

![The task list offline: three tasks marked as not synced yet, and a banner counting five changes saved on the device](../assets/samples/android/offline-changes.png){ width="270" loading=lazy }

### Come back and watch it send

The Sync tab lists the changes waiting, per table. **Sync now** needs the network, so offline it
is switched off.

Switch **Simulate offline** off. The changes are sent in the order you made them, and the count
drops to **Nothing waiting**. To see the server's copy, use **Clear local cache** under **Local
data**: it deletes what the device holds, then reads both lists again from your project.

Closing the app while offline loses nothing either: the queue is kept on the device and sent on
the next launch that has the network.

![The Sync tab offline: five changes waiting, four to tasks and one to photos, with Sync now switched off](../assets/samples/android/changes-waiting.png){ width="270" loading=lazy }

### Queue an invalid title

The schema refuses a blank title. Under **Try it**, **Queue an invalid title** adds a task with
one.

Online, the server refuses it as soon as it is sent: the app says why in a message, and nothing is
kept. Offline, it waits in the queue like any other change. When it is sent the server refuses it
(HTTP 400, Postgres code 23514), and it moves to **Refused changes** with the server's answer.
**Retry** sends it again; **Dismiss** removes it.

![The Sync tab's Refused changes: "Couldn't save (no title)", the reason, "The server answered HTTP 400 · 23514", and Retry and Dismiss](../assets/samples/android/refused-change.png){ width="270" loading=lazy }

### Attach a photo

Online, open a task and tap **Add photo**. The upload shows its progress, and the photo joins the
task once its row is saved. Once a photo has been shown it stays on the device: the Photos tab
marks it available offline.

Storage changes are never queued. Offline, **Add photo** and **Delete photo** are switched off,
and the photos already on the device still show.

![A task's detail with one photo attached](../assets/samples/android/photo-attached.png){ width="270" loading=lazy }

### Start over

**Start over**, under **Local data**, deletes this user's photos, photo rows and tasks from your
project, clears the device, and signs in as a new anonymous user with an empty list. It needs the
network: an anonymous user cannot sign back in to reach what it left behind, so offline it
refuses and deletes nothing.

## Troubleshooting

- **The app says there is no Supabase project yet.** `local.properties` has no `SUPABASE_URL` or
  `SUPABASE_PUBLISHABLE_KEY`. Add them and build again.
- **It cannot sign in.** Check that anonymous sign-ins are on (Authentication, Sign In /
  Providers) and that the project is not paused: a free project pauses after a week without
  activity ([If the project pauses](supabase-setup.md#if-the-project-pauses)). The app tries
  again each time the network comes back; until then the list says it is not signed in yet.
- **A photo will not upload.** Check that the `task-photos` bucket exists, private, as
  [Set up Supabase](supabase-setup.md) creates it. The app shows Storage's own answer.
- **The build asks for platform 37.** Install Android SDK Platform 37 in Android Studio's SDK
  Manager.
