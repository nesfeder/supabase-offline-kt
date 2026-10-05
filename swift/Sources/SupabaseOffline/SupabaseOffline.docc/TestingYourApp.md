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

## Values for your own fakes

When your app hides the client behind a protocol of its own, its fakes return the package's values.
Most of them have no public initializer, so the testing library builds them: `pendingMutation(…)`,
`rejectedMutation(…)`, `serverError(…)`, `syncReport(…)`, `syncStatus(…)`, `querySnapshot(…)`,
`offlineResponse(…)`, `cachedFile(…)`, and the errors the client throws (`cacheMissError(…)`,
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
