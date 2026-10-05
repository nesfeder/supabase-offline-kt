# Handling errors

Tell a missing network from a refusing server, and show the user what waits and what was refused.

## Overview

Every failure the package throws is an ``OfflineError``; a canceled task throws Swift's
`CancellationError` instead. Match an error by its ``OfflineError/code``, as you match a `URLError`:
`catch OfflineError.cacheMiss`. A later release may add codes, so a `switch` over them keeps a
`default`.

<!-- swift-listing: HandlingErrorsListings.swift:catch-codes -->
```swift
do {
    todos = try await offline.from("todos").select().execute().value
} catch let error as OfflineError where error.isNetworkOrConnectivityFailure {
    message = "You're offline, and this list has not been loaded on this device yet."
} catch let error as OfflineError where error.code == .server {
    message = error.serverError?.message ?? error.message  // the server's refusal
} catch is CancellationError {
    // The task was canceled: nothing to show.
} catch {
    message = error.localizedDescription
}
```

``OfflineError/isNetworkOrConnectivityFailure`` is the question most screens ask: it is true for
``OfflineError/Code/connectivity``, ``OfflineError/Code/networkDisabled`` and
``OfflineError/Code/mutationNotQueueable``, and for a ``OfflineError/Code/syncFailed`` the network
caused. When the server refused, ``OfflineError/serverError`` holds its status, its Postgres or
PostgREST code (such as `23505` or `42501`), message, details and hint, as the server sent them.

A read that may use only the device's copy throws ``OfflineError/Code/cacheMiss`` when there is
none:

<!-- swift-listing: HandlingErrorsListings.swift:cache-miss -->
```swift
do {
    return try await offline.from("todos").select(readStrategy: .cacheOnly).execute().value
} catch OfflineError.cacheMiss {
    return nil  // nothing on the device yet for this query
}
```

## The codes

| Code | When |
| --- | --- |
| ``OfflineError/Code/server`` | The server refused the request. |
| ``OfflineError/Code/connectivity`` | The request could not reach the server, or had no answer in time. |
| ``OfflineError/Code/networkDisabled`` | A request that must reach the server was made after ``SupabaseOfflineClient/disableNetwork()``. |
| ``OfflineError/Code/cacheMiss`` | A read that may use only the device's copy found none. |
| ``OfflineError/Code/mutationNotQueueable`` | A write the queue cannot hold could not be sent for want of a connection. |
| ``OfflineError/Code/syncFailed`` | A sync stopped with writes still queued; found in ``SyncStatus/lastSyncError``, never thrown. |
| ``OfflineError/Code/invalidRequest`` | A request was refused before it was sent; see <doc:OfflineBehavior#Refused-before-sending>. |
| ``OfflineError/Code/accessToken`` | The access-token closure threw. |
| ``OfflineError/Code/decoding``, ``OfflineError/Code/encoding`` | A response did not decode as the type asked for, or a value did not encode; ``OfflineError/underlyingError`` holds Swift's error. |
| ``OfflineError/Code/clientClosed`` | The client was closed. |
| ``OfflineError/Code/configuration`` | The client could not open its local database; every call throws it. |
| ``OfflineError/Code/unexpected`` | A defect in the package, reported instead of ending the app. |

The package's own messages never quote a token, a request's URL or its values; a server's text is
passed on as the server wrote it, and its details may quote your values.

## Writes refused later

A queued write that the server refuses for good when the queue is sent is never thrown: it moves to
the rejected list, which is kept on the device until the app retries or dismisses each entry. Show
it to the user, then decide:

<!-- swift-listing: HandlingErrorsListings.swift:rejected -->
```swift
for rejected in try await offline.rejectedMutations().first() {
    if rejected.isRetryable, rejected.serverError?.code == "23503", parentsNowExist {
        try await offline.retryRejectedMutation(id: rejected.id)  // its parent row exists now
    } else {
        try await offline.dismissRejectedMutation(id: rejected.id)  // after showing it
    }
}
```

Each ``RejectedMutation`` holds the write, the server's ``RejectedMutation/serverError`` and the
time it was refused. A failure that may pass, such as no connection or a `503`, leaves the write
queued for the next sync instead.

## Showing what waits

``SupabaseOfflineClient/syncStatus`` is one value to draw a banner from: the network, the queued and
rejected counts, whether a sync is running, and why the last one stopped.

<!-- swift-listing: HandlingErrorsListings.swift:status -->
```swift
struct SyncBanner: View {
    let offline: SupabaseOfflineClient
    @State private var status: SyncStatus?

    var body: some View {
        Group {
            if let status, status.network == .offline || status.network == .disabled {
                Text("Offline: \(status.pendingMutationCount) changes waiting")
            } else if let status, status.rejectedMutationCount > 0 {
                Text("\(status.rejectedMutationCount) changes were refused")
            } else if status?.lastSyncError != nil {
                Text("Some changes could not be sent yet")
            }
        }
        .task {
            do {
                for try await next in offline.syncStatus {
                    status = next
                }
            } catch {
                // Iterating a closed client's status throws clientClosed.
            }
        }
    }
}
```

``SyncStatusSequence/value`` reads the status without waiting, before the client has opened and
after it has closed. Iterating the sequence after ``SupabaseOfflineClient/close()`` throws
``OfflineError/Code/clientClosed``.
