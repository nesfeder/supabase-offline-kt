# Resolving conflicts

Find the queued writes whose row changed on the server, and decide what happens to them.

## Overview

A write made offline replays later, and by then another device may have changed the same row. By
default the last write wins. Name a table's version column, a column a server-side trigger changes
on every update of a row (such as `updated_at` kept by `moddatetime`, or an integer), and the client
replays a queued update or delete of one cached row only while the server's row still has the
version the device saw:

<!-- swift-listing: ResolvingConflictsListings.swift:configure -->
```swift
let config = SupabaseOfflineClient.Config(
    versionColumns: ["todos": "updated_at"],
    conflictResolver: { conflict in
        // Another device renamed the task: keep its title, send the rest.
        guard conflict.conflictingColumns == ["title"], var columns = conflict.local else {
            return .keepLocal
        }
        columns.removeValue(forKey: "title")
        return columns.isEmpty ? .keepServer : .apply(columns: columns)
    }
)
let offline = SupabaseOfflineClient(supabaseURL: supabaseURL, supabaseKey: supabaseKey, config: config)
```

When the row moved, the client reads it again and compares it with the row as the device saw it. A
change on the server to columns the write does not set is no conflict: the write is resent against
the new version. Otherwise the ``SupabaseOfflineClient/Config/conflictResolver`` is shown a
``MutationConflict`` and answers a ``ConflictResolution``. Without one, the device's change wins a
moved row and an update of a row that is gone is dropped. Inserts, upserts, writes sent at once and
writes the queue cannot pin to one cached row are not checked.

## The conflicted list

A resolver that answers ``ConflictResolution/hold``, throws, or does not answer within 30 seconds
parks the write on ``SupabaseOfflineClient/conflictedMutations()``, and the sync goes on with the
next write. Each ``ConflictedMutation`` keeps the write, the row as the device saw it and as the
server had it; settle it later:

<!-- swift-listing: ResolvingConflictsListings.swift:settle -->
```swift
for parked in try await offline.conflictedMutations().first() {
    try await offline.resolveConflictedMutation(id: parked.id, resolution: .keepLocal)
}
```

``SyncReport/conflictsResolvedCount``, ``SyncReport/conflictsKeptServerCount`` and
``SyncReport/conflicted`` say what a sync did, and ``SyncStatus/conflictedMutationCount`` how many
entries wait.
