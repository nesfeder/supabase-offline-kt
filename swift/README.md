# SupabaseOffline

Offline-first reads and queued writes for Supabase's PostgREST and Storage, for iOS apps in Swift.
Reads are served from a cache on the device when the network is gone, and writes made offline wait
in a queue and are sent when it comes back.

## Requirements

- iOS 16 or later
- Xcode 26 or later
- arm64 simulators only: a simulator build that includes x86_64 (such as Any iOS Simulator
  Device) fails to link; build for a specific simulator, or with `ARCHS=arm64`

## Installation

In Xcode, choose **File > Add Package Dependencies…**, enter
`https://github.com/nesfeder/supabase-offline-swift`, and add the `SupabaseOffline` product to your
app's target (and `SupabaseOfflineTesting` to its test target). In a `Package.swift`:

<!-- not compiled: a line of a package manifest -->
```swift
.package(url: "https://github.com/nesfeder/supabase-offline-swift", .upToNextMinor(from: "0.3.0"))
```

and `.product(name: "SupabaseOffline", package: "supabase-offline-swift")` in your target's
dependencies. Use `.upToNextMinor`: before 1.0, a minor release may change the API.

## Quick start

Import the package where you use it:

<!-- not compiled: an import, which every compiled listing's file carries -->
```swift
import SupabaseOffline
```

A row is a `Codable`, `Sendable` type, `nonisolated` so the package can decode it off the main actor
(Xcode 26 makes new apps' code main-actor by default). Make its id on the device:

<!-- swift-listing: GettingStartedListings.swift:row -->
```swift
nonisolated struct Todo: Codable, Sendable, Identifiable {
    var id: UUID
    var title: String
    var done: Bool
}
```

Create one client for the app. Its access-token closure gives it the signed-in user's token, here from
supabase-swift's Auth, which stays in the app:

<!-- swift-listing: GettingStartedListings.swift:client -->
```swift
let offline = SupabaseOfflineClient(
    supabaseURL: URL(string: "https://your-project.supabase.co")!,
    supabaseKey: "your-publishable-key",
    config: .init(accessToken: { try? await supabase.auth.session.accessToken })
)
```

Read: from the server when it answers, from the device's copy when it does not.

<!-- swift-listing: GettingStartedListings.swift:first-read -->
```swift
let todos: [Todo] = try await offline.from("todos")
    .select()
    .eq("done", value: false)
    .order("title")
    .execute()
    .value
```

Write: sent now when online, queued on the device and sent later when not.

<!-- swift-listing: GettingStartedListings.swift:queued-write -->
```swift
let todo = Todo(id: UUID(), title: "Buy milk", done: false)
let outcome = try await offline.from("todos").insert(todo).execute()
// .applied: the server has the row. .queued(let pending): the row waits on the device,
// reads show it already, and the client sends it when the network is back.
```

When the user signs out, turn the client's network off with `disableNetwork()`, sign out of Auth
(so the closure returns `nil`), call `clearLocalData()` even if the sign-out throws, and turn the
network back on with `enableNetwork()` once the next user is signed in: the guide below shows the
order and why.

## Documentation

The guide for Swift apps, and how reads, writes, sync and Storage behave:
<https://nesfeder.github.io/supabase-offline-kt/getting-started/swift/>

## License

MIT. See [LICENSE](LICENSE).
