# SupabaseOffline

Offline-first reads and queued writes for Supabase's PostgREST and Storage, for iOS apps in Swift.
Reads are served from a cache on the device when the network is gone, and writes made offline wait
in a queue and are sent when it comes back.

## Requirements

- iOS 16 or later
- Xcode 26 or later
- For the simulator, a Mac with Apple silicon: the package has no x86_64 simulator build

## Installation

In Xcode, choose **File > Add Package Dependencies…**, enter
`https://github.com/nesfeder/supabase-offline-swift`, and add the `SupabaseOffline` product to your
app's target (and `SupabaseOfflineTesting` to its test target). In a `Package.swift`:

```swift
.package(url: "https://github.com/nesfeder/supabase-offline-swift", .upToNextMinor(from: "0.1.0"))
```

and `.product(name: "SupabaseOffline", package: "supabase-offline-swift")` in your target's
dependencies. Use `.upToNextMinor`: before 1.0, a minor release may change the API.

## Quick start

```swift
import SupabaseOffline

struct Todo: Codable, Sendable { var id: Int; var title: String }

let client = SupabaseOfflineClient(
    supabaseURL: URL(string: "https://your-project.supabase.co")!,
    supabaseKey: "your-publishable-key"
)
// From the cache when it holds the rows, from the server when it does not.
let todos = try await client.from("todos").select(readStrategy: .cacheFirst).execute([Todo].self).value
// Sent now when online; queued and sent later when not.
let outcome = try await client.from("todos").insert(Todo(id: 1, title: "Buy milk")).execute()
```

## Documentation

The guide for Swift apps, and how reads, writes, sync and Storage behave:
<https://nesfeder.github.io/supabase-offline-kt/getting-started/swift/>

## License

MIT. See [LICENSE](LICENSE).
