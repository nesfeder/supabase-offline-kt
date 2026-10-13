# Getting started

Add the package, create a client, and make your first offline read and queued write.

## Overview

SupabaseOffline works beside supabase-swift. Auth and Functions stay with supabase-swift's
client, and so do Realtime's broadcast and presence; the tables and files your app reads and writes
go through a ``SupabaseOfflineClient``, which answers reads from a copy on the device when the
server cannot be reached, keeps writes made offline in a queue until the network comes back, and
keeps the tables ``SupabaseOfflineClient/Config/realtimeTables`` names current through Realtime.

### Add the package

In Xcode, choose **File > Add Package Dependencies…**, enter
`https://github.com/nesfeder/supabase-offline-swift`, and add the `SupabaseOffline` library to your
app's target, and `SupabaseOfflineTesting` to its test target. In a package manifest:

<!-- not compiled: a line of a package manifest -->
```swift
.package(url: "https://github.com/nesfeder/supabase-offline-swift", .upToNextMinor(from: "0.1.0"))
```

Use `.upToNextMinor`: before 1.0, a minor release may change the API. The package needs iOS 16 and
Xcode 26, and runs on arm64 simulators only: a simulator build that includes x86_64 fails to link,
so build for a specific simulator, or with `ARCHS=arm64`.

### Declare a row

A row is a `Codable`, `Sendable` type whose keys are the table's columns; the package converts no
key case, so name a snake-case column with a `CodingKeys` entry.

<!-- swift-listing: GettingStartedListings.swift:row -->
```swift
nonisolated struct Todo: Codable, Sendable, Identifiable {
    var id: UUID
    var title: String
    var done: Bool
}
```

Make the id on the device. A write whose answer was lost is sent again, and with its id the server
recognizes the row it already has instead of inserting a second one. `nonisolated` matters in an
app whose code runs on the main actor by default; see
<doc:GettingStarted#Apps-whose-default-isolation-is-the-main-actor>.

### Create the client

Create one client for the app and share it: it is `Sendable`, and it opens its local database in
the background. The access-token closure gives it the signed-in user's token. With supabase-swift's
Auth, where `supabase` is your supabase-swift client:

<!-- swift-listing: GettingStartedListings.swift:client -->
```swift
let offline = SupabaseOfflineClient(
    supabaseURL: URL(string: "https://your-project.supabase.co")!,
    supabaseKey: "your-publishable-key",
    config: .init(accessToken: { try? await supabase.auth.session.accessToken })
)
```

The key is the project's publishable key. When nobody is signed in the closure returns `nil`, and
requests go with the publishable key alone.

### When the user changes

The client learns who the user is only from the token the closure returns, so it cannot tell one
user's cached rows and queued writes from another's: clear them with
``SupabaseOfflineClient/clearLocalData()`` when the user signs out, with the client's network
turned off from before the sign-out until the next user is signed in:

1. if the user's queued writes should still be sent, sync while they are signed in;
2. turn the network off with ``SupabaseOfflineClient/disableNetwork()``;
3. sign out, so the closure returns `nil`, and clear even if the sign-out throws;
4. clear local data;
5. once the next user is signed in, turn the network back on with
   ``SupabaseOfflineClient/enableNetwork()``: open live reads then fetch again with their token.

Why the network is off: a live read fetches again right after the clear, with whatever token the
closure returns then, so a clear made while it still returns the outgoing user's token puts that
user's rows back for the next one; and a sync that runs between the sign-out and the clear sends
the outgoing user's queued writes with the publishable key alone. An open live read does not fetch
again by itself when only the token changes: turning the network back on is what starts it.

<!-- swift-listing: GettingStartedListings.swift:sign-out -->
```swift
func signOut() async throws {
    // Send the user's queued writes while they are signed in. Offline this returns at once,
    // with the writes still queued, and the clear below drops them.
    try await offline.syncPendingMutations()
    // Nothing is sent from here until the next user is signed in.
    try await offline.disableNetwork()
    // Sign out, so the access-token closure returns nil, and clear even if that throws:
    // supabase-swift ends the session on the device before it tells the server.
    do {
        try await supabase.auth.signOut()
    } catch {
        try await offline.clearLocalData()
        throw error
    }
    try await offline.clearLocalData()
}

func didSignIn() async throws {
    // The next user's token is live: open live reads fetch again with it.
    try await offline.enableNetwork()
}
```

Clearing in the `catch` is safe with supabase-swift, which ends the session on the device before
it calls the server, so the closure returns `nil` even after a sign-out that throws; with an Auth
that keeps its session when the sign-out fails, end the session on the device yourself before
clearing.

Signing out and back in as the same user needs nothing. If your app reads while nobody is signed
in, turn the network back on right after the clear instead, and restart open live reads once the
next user signs in. If a session can end without your sign-out code running, clear local data once
it has ended, before the next user signs in.

### Read

<!-- swift-listing: GettingStartedListings.swift:first-read -->
```swift
let todos: [Todo] = try await offline.from("todos")
    .select()
    .eq("done", value: false)
    .order("title")
    .execute()
    .value
```

A read uses ``ReadStrategy/networkFirst`` unless it names another: it asks the server, and answers
from the device's copy when the server cannot be reached. Each read is kept on the device under its
own columns, filters, order and range, so a read the app has never made online has nothing to show
offline. <doc:OfflineBehavior> describes every strategy.

### Write

<!-- swift-listing: GettingStartedListings.swift:queued-write -->
```swift
let todo = Todo(id: UUID(), title: "Buy milk", done: false)
let outcome = try await offline.from("todos").insert(todo).execute()
// .applied: the server has the row. .queued(let pending): the row waits on the device,
// reads show it already, and the client sends it when the network is back.
```

A write returns a ``MutationOutcome``: the server applied it, or it waits in the queue on the
device. Reads show a queued write at once, and the client sends the queue by itself when the network
comes back; ``SupabaseOfflineClient/syncPendingMutations(table:)`` sends it now.

### Read live

``OfflinePostgrestQueryBuilder/selectAsSequence(_:as:readStrategy:)`` is a read that changes with
the rows. In SwiftUI, iterate it in a `.task`, which ends it when the view goes away:

<!-- swift-listing: GettingStartedListings.swift:live-read -->
```swift
struct TodoList: View {
    let offline: SupabaseOfflineClient
    @State private var todos: [Todo] = []

    var body: some View {
        List(todos) { todo in
            Text(todo.title)
        }
        .task {
            // Each snapshot is the rows now: the device's copy at once, then the server's, then
            // again after every write, sync or refresh that changes them. Leaving the view ends it.
            do {
                for try await snapshot in offline.from("todos").selectAsSequence(as: [Todo].self).order("title") {
                    todos = snapshot.result ?? []
                }
            } catch {
                // The read ended with an error it cannot recover from, such as a decoding failure.
            }
        }
    }
}
```

### Apps whose default isolation is the main actor

Xcode 26 makes new apps' code isolated to the main actor by default (the Default Actor Isolation
build setting, or `.defaultIsolation(MainActor.self)` in a package). A value type declared there
encodes and decodes on the main actor only, and the package works off it. Declare `nonisolated`
every value type you hand to the package or read back from it: rows, as `Todo` is above, and the
patch bodies, Realtime records and filter values of your own that code off the main actor uses.

Without it, a read that decodes a row does not compile ("main actor-isolated conformance of
'Todo' to 'Decodable' cannot satisfy conformance requirement for a 'Sendable' type parameter"), and
neither does a write from an actor of your own, or from a `@Sendable` callback such as a Realtime
handler ("main actor-isolated conformance of 'TodoPatch' to 'Encodable' cannot be used in
actor-isolated context"):

<!-- swift-listing: GettingStartedListings.swift:off-main-actor -->
```swift
nonisolated struct TodoPatch: Encodable, Sendable {
    var done: Bool
}

actor TodoRepository {
    let offline: SupabaseOfflineClient

    init(offline: SupabaseOfflineClient) {
        self.offline = offline
    }

    func markDone(id: UUID) async throws {
        try await offline.from("todos").update(TodoPatch(done: true)).eq("id", value: id).execute()
    }
}
```

The client, the request builders, the sequences and the values they yield are `Sendable`, and are
used from the main actor, or any other, as they are.
