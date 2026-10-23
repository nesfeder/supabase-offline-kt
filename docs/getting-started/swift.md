# iOS apps in Swift

The Swift package gives an iOS app the library's offline reads, queued writes and file cache through
a native Swift API. Its calls read as supabase-swift's do, and supabase-swift stays in the app for
Auth and Functions, and for Realtime's broadcast and presence; the tables `Config.realtimeTables`
names, the client keeps current through Realtime itself ([Applying Realtime events](../behaviors/realtime.md)).

The full Swift API, with articles on offline behavior, errors and testing, is the
[Swift API reference](https://nesfeder.github.io/supabase-offline-kt/swift/documentation/supabaseoffline/).

## Requirements

- iOS 16 or later, and Xcode 26 or later.
- arm64 simulators only: a simulator build that includes x86_64 (such as Any iOS Simulator Device)
  fails to link. Build for a specific simulator, or with `ARCHS=arm64`.

## Add the package

In Xcode, choose **File > Add Package Dependencies…**, enter
`https://github.com/nesfeder/supabase-offline-swift`, and add the `SupabaseOffline` library to your
app's target and `SupabaseOfflineTesting` to its test target. In a package manifest:

<!-- not compiled: a line of a package manifest -->
```swift
.package(url: "https://github.com/nesfeder/supabase-offline-swift", .upToNextMinor(from: "0.2.0"))
```

and `.product(name: "SupabaseOffline", package: "supabase-offline-swift")` in your target's
dependencies. Use `.upToNextMinor`: before 1.0, a minor release may change the API.

## Declare a row

A row is a `Codable`, `Sendable` type whose keys are the table's columns:

*From the sample: [TaskRows.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRows.swift)*

```swift
--8<-- "samples/ios/OfflineTasks/Data/TaskRows.swift:row"
```

Make the id on the device: a write whose answer was lost is sent again, and with its id the server
recognizes the row it already has. The sample makes every id with `UUID().uuidString.lowercased()`,
since Postgres writes a UUID in lower case.

`nonisolated` matters in an app whose code runs on the main actor by default, as Xcode 26 sets new
apps up. A value type declared there encodes and decodes on the main actor only, and the package
works off it, so declare `nonisolated` every value type you hand to the package or read back from
it: rows, and the patch bodies, Realtime records and filter values of your own that an actor or a
`@Sendable` callback uses. Without it, a read that decodes the row, or a write from an actor of your
own, does not compile. The sample's typed patch for a task's text is one:

*From the sample: [TaskRows.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRows.swift)*

```swift
--8<-- "samples/ios/OfflineTasks/Data/TaskRows.swift:off-main-actor"
```

## Create the client

Create one client for the app and share it. The access-token closure gives it the signed-in user's
token; with supabase-swift's Auth, as the sample signs in anonymously:

*From the sample: [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)*

```swift
--8<-- "samples/ios/OfflineTasks/App/AppModel.swift:install"
```

The session's token, which supabase-swift's `session` refreshes first when it has expired:

*From the sample: [AnonymousSession.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/AnonymousSession.swift)*

```swift
--8<-- "samples/ios/OfflineTasks/Data/AnonymousSession.swift:access-token"
```

The key is the project's publishable key. When nobody is signed in the closure returns `nil`, and
requests go with the publishable key alone. The sample's `ClientSettings.config` writes out every
other setting with the default it keeps; see [Configuration](../reference/configuration.md).

## When the user changes

The client learns who the user is only from the token the closure returns. With
`identityFromAccessToken: true` in the `Config`, it tells users apart by the token's `sub` claim:
each user's queued writes wait for that user's token, the cache is dropped when the token names
another user, and the routine below is not needed
([Telling users apart by their token](../behaviors/sign-out.md#telling-users-apart-by-their-token)).
The closure then returns `nil` only when nobody is signed in; when a refresh fails offline, it
returns `auth.currentSession?.accessToken`, which still names the user.

By default the client cannot tell one user's cached rows and queued writes from another's: clear
them with `clearLocalData()` when the user signs out, with the client's network turned off from
before the sign-out until the next user is signed in. Sync the user's queued writes first if they
should still go; turn the network off;
sign out, so the closure returns `nil`, clearing even if the sign-out throws; clear; and once the
next user is signed in, turn the network back on, which makes open live reads fetch again with
their token. The sample's **Start over** does it, discarding the queued writes rather than sending
them, since its anonymous user leaves for good:

*From the sample: [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)*

```swift
--8<-- "samples/ios/OfflineTasks/Data/TaskRepository.swift:start-over"
```

Why the network is off: a live read that refreshes fetches again right after the clear, with
whatever token the closure returns then, so a clear made while it still returns the outgoing
user's token puts that user's rows back for the next one; and a sync that runs between the
sign-out and the clear sends the outgoing user's queued writes with the publishable key alone.
Clearing after a sign-out that failed is safe with supabase-swift, which ends the session on the
device before it calls the server; with an Auth that keeps its session when the sign-out fails, end
the session on the device yourself first.

Signing out and back in as the same user needs nothing. If your app reads while nobody is signed
in, turn the network back on right after the clear instead, and restart open live reads once the
next user signs in. If a session can end without your sign-out code running, clear local data
once it has ended, before the next user signs in.

## Read

*From the sample: [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)*

```swift
--8<-- "samples/ios/OfflineTasks/Data/TaskRepository.swift:first-read"
```

A read asks the server, and answers from the device's copy when the server cannot be reached, unless
it names another read strategy; the sample's pull to refresh names `.networkFirst(maxAge:)`, so a
saved list older than a day is not shown as an answer. Each read is kept on the device under its own
columns, filters, order and range, so a read the app has never made online has nothing to show
offline.

## Write

*From the sample: [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)*

```swift
--8<-- "samples/ios/OfflineTasks/Data/TaskRepository.swift:first-write"
```

A write returns a `MutationOutcome`: the server applied it, or it waits in the queue on the device.
Reads show a queued write at once, and the client sends the queue by itself when the network comes
back. Nothing runs when the app returns to the foreground: call `syncPendingMutations()` then.

## Read live

A live read yields the rows again whenever a write, a sync or a refresh changes them. In SwiftUI,
iterate it in a `.task`, which ends it when the view goes away; the sample's root view runs this one
in `.task(id: session.userID)`, so it starts again for each user:

*From the sample: [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)*

```swift
--8<-- "samples/ios/OfflineTasks/Data/TaskRepository.swift:first-observed-read"
```

and the screen's model iterates it, keeping each snapshot's rows:

*From the sample: [TasksModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Tasks/TasksModel.swift)*

```swift
--8<-- "samples/ios/OfflineTasks/Tasks/TasksModel.swift:live-read"
```

## Conflicts

By default the last write to reach the server wins. Name a table's version column in
`Config.versionColumns`, optionally set `Config.conflictResolver`, an `async throws` closure from a
`MutationConflict` to a `ConflictResolution` (`.keepLocal`, `.keepServer`, `.apply(columns:)`,
`.hold`), and a queued update or delete of one cached row is checked when it replays. What the
closure holds waits on `conflictedMutations()`, a `ConflictedMutationSequence`, for
`resolveConflictedMutation(id:resolution:)` or `dismissConflictedMutation(id:)`; `SyncReport` and
`SyncStatus` count it. The sample's version columns and closure are on
[Configuration](../reference/configuration.md#versioncolumns), beside the Android ones.

The closure runs in a task of its own, off the caller's actor; a throw holds the write, and so do
30 seconds without an answer, after which a late answer is dropped. The Swift API reference's
Resolving conflicts article and [Conflicts and merging](../behaviors/conflicts.md) have the rest.

## Next

The [Swift API reference](https://nesfeder.github.io/supabase-offline-kt/swift/documentation/supabaseoffline/)
has the rest: moving calls from supabase-swift, what is kept, queued and refused offline, telling
failures apart, and testing an app against the in-memory client. Every code sample on this page is
the [iOS sample](../samples/ios.md)'s own code, which CI builds and tests; the
[guided tour](../samples/tour.md) shows the rest of the API in it. [Under the hood](../help/under-the-hood.md) says what the
package's binary holds and what that means for an app.
