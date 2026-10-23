# Testing your app

Run the real client in memory against a server your test scripts, in tests and in previews.

## Overview

The `SupabaseOfflineTesting` library adds `SupabaseOfflineClient.inMemory(…)`: a client
with the same cache, queue and sync as the one your app ships, kept in memory, whose every request a
`FakeServer` closure answers. Add the library to your test target, and to your app's target too if
your previews use it. Its requests never leave the process, so your tests need no network and no
Supabase project.

## A test against the in-memory client

`FakeServer` receives each request (its method, URL, headers and body) and returns a response:
`.json(_:status:headers:)`, `.status(_:headers:body:)`, or `.noConnection` for a network that never
reaches the server. Turn the network off with ``SupabaseOfflineClient/disableNetwork()`` to test
what your app does offline:

<!-- swift-listing: TestingYourAppListings.swift:in-memory-test -->
```swift
@Test func aTodoAddedOfflineIsSentWhenTheNetworkIsBack() async throws {
    // Given a client whose server accepts every write, with the network turned off
    let offline = SupabaseOfflineClient.inMemory(
        config: .init(autoSync: false),
        server: FakeServer { _ in .json("[]", status: 201) }
    )
    try await offline.disableNetwork()

    // When a to-do is added, then the network comes back and the queue is sent
    let outcome = try await offline.from("todos").insert(Todo(id: UUID(), title: "Buy milk", done: false)).execute()
    try await offline.enableNetwork()
    let report = try await offline.syncPendingMutations()

    // Then the write waited on the device, and the sync sent it
    #expect(outcome.isQueued)
    #expect(report.syncedCount == 1)
    try await offline.close()
}
```

Pass `autoSync: false` when the test decides when the queue is sent, as above; with automatic sync
on, a sync may run whenever the network comes back. Close each client at the end of its test.

A server that refuses a write shows how your app handles the rejected list:

<!-- swift-listing: TestingYourAppListings.swift:refusal-test -->
```swift
@Test func aWriteRefusedWhenTheQueueIsSentIsListedWithTheServersCode() async throws {
    // Given a server that refuses every write, as a row-level security policy would
    let offline = SupabaseOfflineClient.inMemory(
        config: .init(autoSync: false),
        server: FakeServer { _ in
            .json(#"{"code":"42501","message":"new row violates row-level security policy"}"#, status: 403)
        }
    )
    try await offline.disableNetwork()
    try await offline.from("todos").insert(Todo(id: UUID(), title: "Buy milk", done: false)).execute()
    try await offline.enableNetwork()

    // When the queue is sent
    let report = try await offline.syncPendingMutations()

    // Then the write is on the rejected list, with the server's code
    #expect(report.rejected.first?.serverError?.code == "42501")
    #expect(try await offline.rejectedMutations().first().count == 1)
    try await offline.close()
}
```

The in-memory client retries no failed read, so a failure the server scripts arrives at once. A
`ManualClock`, passed as `clock`, is the time the client sees: move it to test a read's `maxAge` or
when a write was queued.

## Test a relaunch

Each in-memory client starts empty. To see what your app does after it is killed and relaunched,
build both clients over one `InMemoryStore`. The second finds what the first left when it closed:
the queued writes, the rejected and conflicted lists, the cached rows and files. One client holds
the store at a time, so close the first before building the second; a client built while another
holds it throws ``OfflineError/Code/configuration`` from every call.

<!-- swift-listing: TestingYourAppListings.swift:restart-test -->
```swift
@Test func aTodoQueuedBeforeTheAppIsKilledIsSentAfterItRelaunches() async throws {
    // Given a client that queues a to-do with the network off, then is closed as the app is killed
    let store = InMemoryStore()
    let server = FakeServer { _ in .json("[]", status: 201) }
    let killed = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server, store: store)
    try await killed.disableNetwork()
    try await killed.from("todos").insert(Todo(id: UUID(), title: "Buy milk", done: false)).execute()
    try await killed.close()

    // When the app relaunches over the same store and sends the queue
    let relaunched = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server, store: store)
    let report = try await relaunched.syncPendingMutations()

    // Then the write queued before the relaunch is the one sent
    #expect(report.syncedCount == 1)
    try await relaunched.close()
}
```

## Report Realtime changes

A test of an app that names tables in ``SupabaseOfflineClient/Config/realtimeTables`` gives the
in-memory client a `FakeRealtime` in place of a Realtime channel. The client joins the fake's
channel as it would Realtime's (the network not known down, the subscription on), and
`subscribedTables` says which tables it follows now. Wait for ``SupabaseOfflineClient/realtimeStatus``
to read ``RealtimeStatus/subscribed``, or for the fake's `joinCount` to move, which it does once the
client has also asked for the named tables to be read again, before reporting a change, since
nothing is replayed. Each report reaches the cached queries as ``SupabaseOfflineClient/applyServerRecord(table:record:primaryKeys:schema:)-(_,[String:OfflineJSON],_,_)``
applies a record; a report to a table nothing follows is dropped and counted in
`droppedReportCount`, and so is an insert or an update whose record does not meet the table's
``RealtimeFilter``, while a delete reaches the client whatever the filter. `failChannel(message:)`
ends the channel as a server that refuses it would, and `dropChannel()` as a lost socket: the status
reads ``RealtimeStatus/stalled(cause:)``, and the client joins again after a short wait.

The `FakeServer` plays the server, and the fake only the channel: the client reads the named tables
from the server on every join, and again after a change it cannot apply exactly or that met a read
in flight, so the server must answer with the rows as they are after each change the test reports.

<!-- swift-listing: TestingYourAppListings.swift:realtime-test -->
```swift
@Test func aChangeTheServerReportsReachesTheRowsOnTheDevice() async throws {
    // Given a client that follows `todos` through a fake Realtime, and one row read into the cache
    let realtime = FakeRealtime()
    let offline = SupabaseOfflineClient.inMemory(
        config: .init(realtimeTables: [RealtimeTable("todos")]),
        server: FakeServer { _ in .json(#"[{"id":"3f1e9c2a-6b4d-4e8f-9a0b-1c2d3e4f5a6b","title":"Buy milk","done":false}]"#) },
        realtime: realtime
    )
    _ = try await offline.from("todos").select().execute([Todo].self)
    for try await status in offline.realtimeStatus where status == .subscribed { break }

    // When the server reports a row inserted
    try realtime.reportInsert(
        table: "todos",
        record: ["id": .string("8c2b7d1e-0f3a-4b5c-8d6e-7f8a9b0c1d2e"), "title": .string("Call mom"), "done": .bool(false)]
    )

    // Then a live read of the device's copy yields both rows, with no request
    var titles: [String] = []
    for try await snapshot in offline.from("todos").selectAsSequence(as: [Todo].self, readStrategy: .cacheOnly) {
        titles = snapshot.result?.map(\.title) ?? []
        if titles.count == 2 { break }
    }
    #expect(titles.sorted() == ["Buy milk", "Call mom"])
    try await offline.close()
}
```

## Previews

The same client gives a SwiftUI preview rows without a network:

<!-- swift-listing: TestingYourAppListings.swift:preview -->
```swift
#Preview {
    TodoList(offline: .inMemory(server: FakeServer { _ in
        .json(#"[{"id":"7f4c8e2a-1b3d-4e5f-8a9b-0c1d2e3f4a5b","title":"Buy milk","done":false}]"#)
    }))
}
```

## Files in tests and previews

The in-memory client keeps downloaded files in memory too: the `CachedFile` that
``OfflineBucketApi/ensureCachedFile(path:isPublic:)`` and ``OfflineBucketApi/cachedFile(path:)``
return names no file on disk, so reading its ``CachedFile/url`` fails in a test, a preview or a demo
mode. A view that shows a stored image reads the bytes through
``OfflineBucketApi/download(path:options:isPublic:)``, which the device's copy answers offline, and
then works with either client:

<!-- swift-listing: TestingYourAppListings.swift:photo -->
```swift
struct PhotoView: View {
    let offline: SupabaseOfflineClient
    let path: String
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                Color.secondary
            }
        }
        .task {
            // The bytes, from the server or from the device's copy. An in-memory client's copy is
            // not a file, so a view that previews read them this way, not from a file URL.
            if let data = try? await offline.storage.from("photos").download(path: path) {
                image = UIImage(data: data)
            }
        }
    }
}
```

## Values for your own fakes

When your app hides the client behind a protocol of its own, its fakes return the package's values.
Most of them have no public initializer, so the testing library builds them: `pendingMutation(…)`,
`rejectedMutation(…)`, `serverError(…)`, `syncReport(…)`, `syncStatus(…)`, `querySnapshot(…)`,
`offlineResponse(…)`, `cachedResponse(…)` for rows the cache answered with, `cachedFile(…)`, and
the errors the client throws (`cacheMissError(…)`,
`syncFailedError(…)`, `offlineError(code:message:…)` and the rest).

<!-- swift-listing: TestingYourAppListings.swift:factories -->
```swift
let waiting = syncStatus(network: .offline, pendingMutationCount: 2)
let refused = rejectedMutation(
    id: 7,
    table: "todos",
    operation: .insert,
    createdAt: .now,
    serverError: serverError(status: 403, code: "42501", message: "new row violates row-level security policy"),
    message: "new row violates row-level security policy",
    rejectedAt: .now
)
```
