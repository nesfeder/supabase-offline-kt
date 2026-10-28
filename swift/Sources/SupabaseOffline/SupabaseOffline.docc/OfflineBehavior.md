# Offline behavior

What the client keeps on the device, what waits in the queue, and what it refuses before sending.

## Overview

A client keeps three things in its data directory: the answers to list reads, a queue of the
writes it has not sent yet, and copies of downloaded files. Reads choose between the server and
that copy with a strategy; writes wait in the queue when they cannot be sent; downloads are served
from their copies offline.

## Reads

| Strategy | What it does |
| --- | --- |
| ``ReadStrategy/networkFirst(maxAge:)``, the default | Asks the server, and answers from the device's copy when the server cannot be reached. A refusal is thrown even with a copy on the device. |
| ``ReadStrategy/cacheFirst(maxAge:)`` | Answers from the device's copy, and asks the server when there is none, when it is older than `maxAge`, or when a write made it stale. |
| ``ReadStrategy/cacheOnly(maxAge:)`` | Answers from the device's copy and sends nothing; with no copy it may use, throws ``OfflineError/Code/cacheMiss``. |
| ``ReadStrategy/networkOnly`` | Asks the server, and throws when it cannot be reached. |

<!-- swift-listing: OfflineBehaviorListings.swift:strategies -->
```swift
// The server, and the device's copy when the server cannot be reached (the default).
let current: [Todo] = try await offline.from("todos").select().execute().value
// The device's copy when it is under five minutes old; the server otherwise.
let recent: [Todo] = try await offline.from("todos")
    .select(readStrategy: .cacheFirst(maxAge: .seconds(300))).execute().value
// The device's copy only; nothing is sent.
let saved: [Todo] = try await offline.from("todos").select(readStrategy: .cacheOnly).execute().value
```

Each read is kept on its own, under its columns, filters, order and range. Offline, a read the app
has never made online has nothing to show, even when another kept read holds its rows: read a detail
screen's row from the list, with the list's own request. Only a list of rows is kept: a count, a
`head` read, ``OfflinePostgrestRequestBuilder/single()`` and the other formats go to the server
under every strategy but `cacheOnly`, and fail offline. An answer says where it came from:
``OfflineResponse/isFromCache``, ``OfflineResponse/fetchedAt`` and
``OfflineResponse/hasPendingMutations``, and ``OfflineResponse/pendingRowKeys`` names the rows a
queued write changed, as ``QuerySnapshot/pendingRowKeys`` does for a live read.

A live read, ``OfflinePostgrestQueryBuilder/selectAsSequence(_:as:readStrategy:)``, takes its first
rows as its ``SequenceReadStrategy`` says and then follows every write, sync, refresh and server
record that changes them. A failing refresh leaves the rows in place and shows in the snapshot's
``QuerySnapshot/refresh``: ``RefreshState/paused`` while the network is known to be down,
``RefreshState/stalled(cause:)`` while it keeps failing, retried at most a minute apart.

## Writes

A write made with ``MutationStrategy/queueIfOffline``, the default, is saved to the queue on the
device first. It is sent at once when nothing of the user's waits ahead of it, and otherwise by a
later sync, in the order the writes were made, across tables. Reads show it at once.

| When the write is made | What it returns |
| --- | --- |
| Nothing waits ahead of it, and the server accepts it | `.applied` |
| Something waits ahead of it, the network is out of reach, the server's answer says a later try may pass (a `503`, a `429`, a token the server holds expired), or network access is turned off | `.queued` |
| It is sent at once and the server refuses it | It throws ``OfflineError/Code/server`` and leaves the queue |
| Its task is canceled while it is on the wire | It stays queued, since the server may have it |

The queue holds a write only when it can replay it exactly. An update or a delete whose filter it
cannot record (a group of conditions, an embedded table's column, a negated condition, a limit or an
order, or no filter at all), and a write to a table of another schema, is sent at once instead; with
no connection it throws ``OfflineError/Code/mutationNotQueueable``. ``MutationStrategy/onlineOnly``
sends any write that way:

<!-- swift-listing: OfflineBehaviorListings.swift:online-only -->
```swift
// Never queued: it is sent now, or it throws.
try await offline.from("orders").update(["status": "paid"], mutationStrategy: .onlineOnly)
    .eq("id", value: orderID).execute()
```

A database function is never kept and never queued, since it may change data.

## Sync

The client sends the queue by itself when the network comes back (unless
``SupabaseOfflineClient/Config/autoSync`` is off) and whenever a write is queued behind another.
Nothing runs when your app returns to the foreground: call
``SupabaseOfflineClient/syncPendingMutations(table:)`` then.

A sync sends the user's writes one at a time, in order. A failure that may pass (no connection, a
`401`, `408`, `429` or `5xx`) stops it with the rest still queued, and the report's
``SyncReport/stopReason`` and ``SyncStatus/lastSyncError`` say why. A write the server refuses for
good moves to the rejected
list, ``SupabaseOfflineClient/rejectedMutations()``, and the sync goes on. The list is kept across
launches until the app dismisses or retries each entry, and holds the newest 100.

The network is a hint, never a gate: the client watches the system's path monitor, or your own
``SupabaseOfflineClient/Config/networkAvailability``, to decide when to try by itself, and still
tries every request your app makes. ``SupabaseOfflineClient/disableNetwork()`` is the switch that
stops requests, as airplane mode does.

## Files

A download asks the network first and keeps the file it receives; when the network fails, the kept
copy answers, and a "not found" drops it. ``OfflineBucketApi/ensureCachedFile(path:isPublic:)``
returns the kept copy, downloading it first when there is none, and
``OfflineBucketApi/cachedFile(path:)`` never touches the network. Uploads, updates, moves, copies and
removals are never queued, fail at once offline, and drop the copies they make stale, but for
``OfflineBucketApi/upload(_:data:mutationStrategy:options:)``: under
``MutationStrategy/queueIfOffline`` its bytes are kept on the device and it waits in the mutation
queue, sent in the order it was made among the row writes, so a row written after it that names the
file is sent after the file. While it waits, ``OfflineStorage/pendingUploads()`` lists it and
``OfflineBucketApi/cachedFile(path:)`` returns its bytes on the device; a refusal lands on
``OfflineStorage/rejectedUploads()`` with its bytes, for a retry.

A transformed download is kept apart from the original, and
``OfflineBucketApi/cachedFile(path:transform:)`` finds its copy. To free the device's storage,
``OfflineBucketApi/evictCachedFile(path:)`` deletes one object's copies and
``OfflineStorage/clearFileCache()`` every kept file; neither sends anything, and both leave the
queued writes and the cached query results.

An object name holding `%`, `?` or `#` is never kept on the device, and other requests than the URL
builders, signing and uploads to a signed URL can mistake it for another object: keep those
characters out of object names. A name that cannot be mapped onto a file safely (a leading `/`, an
empty, `.` or `..` segment, a `\` or a control character) downloads without being kept.

## Two conditions on one column

The builder sends two conditions on one column, and two `or` groups on one table, together in one
`and` group, which PostgREST reads the same way:

<!-- swift-listing: OfflineBehaviorListings.swift:same-column -->
```swift
// Both conditions are sent, in one group, so the read means what it says.
let thisWeek: [Todo] = try await offline.from("todos").select()
    .gte("created_at", value: start)
    .lt("created_at", value: end)
    .execute().value
```

On an update or a delete, that group is a filter the queue cannot record, so the write is sent at
once.

## Refused before sending

These fail with ``OfflineError/Code/invalidRequest`` and send nothing:

- a pattern list (``OfflinePostgrestFilterBuilder/likeAllOf(_:patterns:)`` and its three siblings)
  that is empty, or holds a pattern with none of `,.:()` that is empty, `NULL`, edged with white
  space or holding a brace;
- a value in an `in` list, or inside an `or` group, holding `"` or `\` and none of `,.:()`; a range
  filter inside a group; a range written other than as exclusive bounds, `(lower,upper)`;
- filter text the package cannot read, and a negative `maxAge`;
- an empty upload, or one of 2 GiB or more;
- ``OfflineBucketApi/ensureCachedFile(path:isPublic:)`` for a name the cache cannot hold;
- retrying a rejected write whose ``RejectedMutation/isRetryable`` is `false`.

## How values are written

Keys are written as the type names them. A `nil` property of a `Codable` row is left out, so the
column keeps its value or takes its default; send ``OfflineJSON`` to write `null`. A `UUID` is
written in lower case, except one a type's own `encode(to:)` hands straight to the encoder. A `Date`
is written in UTC, ISO 8601, to the microsecond, and read from any ISO 8601 form. `Data` is written
as base64 text.

## Limits

- A client owns its data directory: close it before opening another client over the same directory.
- Kept answers may take ``SupabaseOfflineClient/Config/cacheSize``, 100 MiB by default, and kept
  files ``SupabaseOfflineClient/Config/fileCacheSize``, 256 MiB; past that, the least recently
  fetched or used go first.
- A live read carries no count.
- ``OfflineBucketApi/createSignedURLs(paths:expiresIn:)`` fails whole when one path cannot be signed.
- The client keeps only the tables ``SupabaseOfflineClient/Config/realtimeTables`` names current
  through Realtime, with one filter each, and reads them again on every join, since Realtime does
  not replay what was missed. It joins the channel with the token ``SupabaseOfflineClient/Config/accessToken``
  returns at the join and does not join again by itself when the user changes: bracket a sign-in
  or sign-out with ``SupabaseOfflineClient/disableRealtime()`` and ``SupabaseOfflineClient/enableRealtime()``.
  Records from a channel of your own still go through
  ``SupabaseOfflineClient/applyServerRecord(table:record:primaryKeys:schema:)-(_,Encodable,_,_)`` and
  ``SupabaseOfflineClient/applyServerDelete(table:oldRecord:primaryKeys:schema:)-(_,Encodable,_,_)``.
