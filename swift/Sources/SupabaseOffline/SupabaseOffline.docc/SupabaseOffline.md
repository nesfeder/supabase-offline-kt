# ``SupabaseOffline``

Offline-first reads and queued writes for Supabase's PostgREST and Storage.

## Overview

SupabaseOffline keeps a copy of what your app reads on the device and a queue of what it writes.
A read is answered from the server or from that copy, as its read strategy says; a write made
without a connection waits in the queue and is sent, in the order it was made, when the network
comes back. Storage downloads are kept on disk and served from there offline.

Calls read as supabase-swift's do: `from("todos").select().eq("done", value: false).execute()`.
Keep supabase-swift for Auth and Functions; the ``SupabaseOfflineClient`` takes the signed-in
user's token from it. The tables ``SupabaseOfflineClient/Config/realtimeTables`` names it keeps
current through Realtime itself; broadcast and presence stay with supabase-swift.

The testing product, `SupabaseOfflineTesting`, runs the same client in memory against a server
your test scripts: see <doc:TestingYourApp>.

## Topics

### Essentials

- <doc:GettingStarted>
- <doc:ComingFromSupabaseSwift>
- <doc:OfflineBehavior>
- <doc:ResolvingConflicts>
- <doc:HandlingErrors>
- <doc:TestingYourApp>

### Setting up

- ``SupabaseOfflineClient``
- ``SupabaseOfflineClient/Config``
- ``SupabaseOfflineClient/CacheSize``
- ``SupabaseOfflineClient/LogLevel``
- ``RealtimeTable``
- ``RealtimeFilter``

### Reading

- ``OfflinePostgrestQueryBuilder``
- ``OfflineSelectRequestBuilder``
- ``ReadStrategy``
- ``OfflineResponse``
- ``Count``
- ``QuerySnapshotSequence``
- ``SequenceReadStrategy``
- ``QuerySnapshot``
- ``RefreshState``

### Filtering and shaping a request

- ``OfflinePostgrestFilterBuilder``
- ``OfflinePostgrestRequestBuilder``
- ``OfflineFilterValue``
- ``FilterOperator``
- ``OfflineTextSearchType``
- ``OfflineQueryChange``

### Writing

- ``OfflineMutationRequestBuilder``
- ``MutationStrategy``
- ``MutationOutcome``
- ``OfflineJSON``

### Calling database functions

- ``OfflineRPCRequestBuilder``

### Files

- ``OfflineStorage``
- ``OfflineBucketApi``
- ``CachedFile``
- ``UploadOutcome``
- ``PendingUpload``
- ``PendingUploadSequence``
- ``RejectedUpload``
- ``RejectedUploadSequence``

### The queue and sync

- ``PendingMutation``
- ``MutationOperation``
- ``PendingMutationSequence``
- ``PendingMutationCountSequence``
- ``RejectedMutation``
- ``RejectedMutationSequence``
- ``SyncReport``
- ``SyncStopReason``

### Conflicts

- ``MutationConflict``
- ``ConflictResolution``
- ``ConflictedMutation``
- ``ConflictedMutationSequence``

### Status and the network

- ``SyncStatusSequence``
- ``SyncStatus``
- ``NetworkStatus``

### Realtime

- ``RealtimeStatusSequence``
- ``RealtimeTableStatusSequence``
- ``RealtimeStatus``

### Errors

- ``OfflineError``
