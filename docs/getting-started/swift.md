# iOS apps in Swift

The Swift package gives an iOS app the library's offline reads, queued writes and file cache through
a native Swift API. Its calls read as supabase-swift's do, and supabase-swift stays in the app for
Auth, Realtime and Functions.

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
.package(url: "https://github.com/nesfeder/supabase-offline-swift", .upToNextMinor(from: "0.1.0"))
```

and `.product(name: "SupabaseOffline", package: "supabase-offline-swift")` in your target's
dependencies. Use `.upToNextMinor`: before 1.0, a minor release may change the API.

## Declare a row

A row is a `Codable`, `Sendable` type whose keys are the table's columns:

```swift
--8<-- "swift/Tests/SupabaseOfflineTests/DocumentationListings/GettingStartedListings.swift:row"
```

Make the id on the device: a write whose answer was lost is sent again, and with its id the server
recognizes the row it already has. `nonisolated` lets the package decode the row off the main actor
in an app whose code runs on the main actor by default, as Xcode 26 sets new apps up; without it, a
read that decodes the type does not compile.

## Create the client

Create one client for the app and share it. The access-token closure gives it the signed-in user's
token; with supabase-swift's Auth, where `supabase` is your supabase-swift client:

```swift
--8<-- "swift/Tests/SupabaseOfflineTests/DocumentationListings/GettingStartedListings.swift:client"
```

The key is the project's publishable key. When nobody is signed in the closure returns `nil`, and
requests go with the publishable key alone.

## When the user changes

The client learns who the user is only from the token the closure returns, so it cannot tell one
user's cached rows and queued writes from another's. Call `clearLocalData()` before the closure can
return another user's token, most simply at sign-out:

```swift
--8<-- "swift/Tests/SupabaseOfflineTests/DocumentationListings/GettingStartedListings.swift:sign-out"
```

Signing out and back in as the same user needs nothing. If a session can end without your sign-out
code running, clear local data before the next user signs in too.

## Read

```swift
--8<-- "swift/Tests/SupabaseOfflineTests/DocumentationListings/GettingStartedListings.swift:first-read"
```

A read asks the server, and answers from the device's copy when the server cannot be reached, unless
it names another read strategy. Each read is kept on the device under its own columns, filters,
order and range, so a read the app has never made online has nothing to show offline.

## Write

```swift
--8<-- "swift/Tests/SupabaseOfflineTests/DocumentationListings/GettingStartedListings.swift:queued-write"
```

A write returns a `MutationOutcome`: the server applied it, or it waits in the queue on the device.
Reads show a queued write at once, and the client sends the queue by itself when the network comes
back. Nothing runs when the app returns to the foreground: call `syncPendingMutations()` then.

## Read live

A live read yields the rows again whenever a write, a sync or a refresh changes them. In SwiftUI,
iterate it in a `.task`, which ends it when the view goes away:

```swift
--8<-- "swift/Tests/SupabaseOfflineTests/DocumentationListings/GettingStartedListings.swift:live-read"
```

## Next

The [Swift API reference](https://nesfeder.github.io/supabase-offline-kt/swift/documentation/supabaseoffline/)
has the rest: moving calls from supabase-swift, what is kept, queued and refused offline, telling
failures apart, and testing an app against the in-memory client. Every code sample on this page is
compiled with the package's tests. [Under the hood](../help/under-the-hood.md) says what the
package's binary holds and what that means for an app.
