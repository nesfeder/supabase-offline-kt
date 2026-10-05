# Guided tour

A walk through Offline Tasks that shows each thing the library does, in order: reads that keep
working offline, writes that wait and are sent in order, refusals, conflicts and photos. It takes
about ten minutes on the [Android sample](android.md), with your project's dashboard open beside
it. The iOS sample is being rebuilt.

## Online basics

1. Open the app. It signs in anonymously and shows an empty list.
2. Add a task with **New task**, open it, add some notes and tick it done.
3. In the dashboard, open the **Table Editor** and the `tasks` table: the row is there, with your
   anonymous user's id in `user_id` and the id the app made on the device in `id`.

Each change was sent at once, because nothing waited ahead of it.

## Go offline

On the **Sync** tab, switch on **Simulate offline**. It turns the library's network access off,
as airplane mode would, for this app only and until you switch it back or the app restarts. The
banner and the subtitle say the app is offline, and **Sync now** is switched off.

## Write while offline

Add a task, clear another's notes, tick one done and delete one. Each change shows at once, with
a cloud mark on the rows whose change has not reached the server; the banner and the Sync tab's
badge count the changes waiting, and the Sync tab lists them per table. Pull to refresh: the app
says it is showing what is saved on the device.

Open a task you have never opened before: it opens offline too, because the app reads its list
once and finds the task in it.

## Come back online

Switch **Simulate offline** off. The library sends the waiting changes by itself, in the order you
made them, and the count drops to **Nothing waiting**.

## Check the dashboard

Refresh the `tasks` table in the Table Editor: the new task is there, the notes are cleared, the
task is done, and the deleted one is gone. Each row's `updated_at` is the server's time, set when
the change arrived.

## Cold start offline

Simulate offline does not survive a restart, so use the device's airplane mode for this one.

1. Turn on airplane mode and close the app completely.
2. Open it again. The list is there, read from the device; the session is the same anonymous user,
   restored from the device too.
3. Add a task, then close the app again. The change is kept on the device.
4. Turn airplane mode off and open the app: the task is sent.

## Photos

1. Online, open a task and choose **Add photo**. The upload shows its progress, and the photo joins
   the task once its row is saved.
2. Open the **Photos** tab: a photo that has been shown is marked as available offline.
3. Go offline. **Add photo** and **Delete photo** are switched off, because Storage writes are
   never queued, and the photos already on the device still show.

## A refused write

On the Sync tab, under **Try it**, choose **Queue an invalid title** while offline. It adds a task
with a blank title, which the schema refuses, and the change waits like any other. Come back
online: the server refuses it (HTTP 400, Postgres code 23514), and it moves to **Refused changes**
with the server's answer. **Retry** sends it again; **Dismiss** removes it.

Online, the same button's write is refused as soon as it is sent, and the app says why in a
message: a write sent at once that the server refuses is thrown to the app, not listed.

## Try a conflict

Two devices are two anonymous users, so play the second writer from the Table Editor.

1. Go offline in the app and tick a task done.
2. In the Table Editor, change the same task's title.
3. Come back online and pull to refresh. Both changes are there: the app's queued update sets
   only `done`, so it leaves the title the dashboard wrote.
4. Now go offline, change a task's title in the app, change the same title in the Table Editor,
   and come back online. The app's title wins: the last write to reach the server wins, and the
   app's arrived last.

The library does not merge or resolve conflicts; see
[Conflicts and failures](../behaviors/failures.md#conflicts).

## Start again

Under **Local data** on the Sync tab:

- **Clear local cache** deletes what the device holds, the queue included, then reads both lists
  again from your project.
- **Start over** deletes this user's photos, photo rows and tasks from your project, clears the
  device and signs in as a new anonymous user. It needs the network.
