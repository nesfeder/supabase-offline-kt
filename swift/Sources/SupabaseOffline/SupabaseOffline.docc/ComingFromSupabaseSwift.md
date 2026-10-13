# Coming from supabase-swift

Move your app's table and file calls to the offline client: the same chains, on a new receiver.

## Overview

Keep supabase-swift for Auth and Functions, and move the PostgREST and Storage calls that should
work offline to a ``SupabaseOfflineClient``. Its filters, transforms, function arguments and
Storage operations take supabase-swift's names and labels, so most calls change only their receiver:

<!-- swift-listing: ComingFromSupabaseSwiftListings.swift:same-calls -->
```swift
let todos: [Todo] = try await offline.from("todos").select().eq("done", value: false)
    .order("title").execute().value
try await offline.from("todos").insert(todo).execute()
try await offline.from("todos").update(["done": true]).eq("id", value: todo.id).execute()
try await offline.from("todos").delete().eq("id", value: todo.id).execute()
let photo = try await offline.storage.from("photos").download(path: photoPath)
try await offline.storage.from("photos").upload(photoPath, data: photo, options: .init(contentType: "image/jpeg"))
```

Each of those lines compiles against supabase-swift with `supabase` in place of `offline`.

## What changes

**A write says where it went.** It returns a ``MutationOutcome``: `.applied(result:)` once the
server applied it, `.queued(pendingMutation:)` while it waits on the device. Code that reads a row
back handles both:

<!-- swift-listing: ComingFromSupabaseSwiftListings.swift:outcome -->
```swift
let saved: Todo
switch try await offline.from("todos").insert(todo).select().single().execute(Todo.self) {
case .applied(let response):
    saved = response.value  // the row as the server stored it
case .queued:
    saved = todo  // the row as sent; reads show it already, and a sync sends it
}
```

**A read takes a strategy.** `select(readStrategy:)` defaults to ``ReadStrategy/networkFirst``,
which online answers as supabase-swift does and offline answers from the device's copy. A write
takes a ``MutationStrategy``; ``MutationStrategy/onlineOnly`` is supabase-swift's behavior: sent now,
or thrown.

**Live reads are new.** ``OfflinePostgrestQueryBuilder/selectAsSequence(_:as:readStrategy:)``
yields the rows again whenever a write, a sync or a refresh changes them, with no Realtime channel
of your own; name the tables the server should keep current in
``SupabaseOfflineClient/Config/realtimeTables``, and the client joins one channel for them.

**The token comes from a closure.** ``SupabaseOfflineClient/Config/accessToken`` returns the user's
token for each request, and the app clears local data when the user changes
(<doc:GettingStarted#When-the-user-changes>).

**Errors are one type.** Every failure is an ``OfflineError`` with a code; supabase-swift's
`PostgrestError` and `StorageError` are never thrown, so a `catch` for either never matches. The
server's code, message, details and hint are in ``OfflineError/serverError``.
See <doc:HandlingErrors>.

**Some values are written differently.** A `UUID` in a row or a filter is written in lower case, a
`Date` in UTC to the microsecond, and two conditions on one column are sent together in one `and`
group, which PostgREST reads the same way. See <doc:OfflineBehavior>.

### Storage

The bucket's operations take supabase-swift's names and labels, with these differences:

- An option value is written `.init(…)`: ``OfflineBucketApi/UploadOptions`` has `contentType`,
  `upsert` and `userMetadata` (supabase-swift's `metadata`), and no `cacheControl`, `duplex` or
  `headers`. ``OfflineBucketApi/ImageTransformation/resize`` is an enum and its `format` a string;
  a list's sort order is `.asc` or `.desc`, and needs a column.
- `getPublicURL(path:)` is ``OfflineBucketApi/publicURL(path:)``, which is `async`.
- ``OfflineBucketApi/createSignedUploadURL(path:upsert:)`` takes `upsert:`, and
  ``OfflineBucketApi/move(from:to:destinationBucket:)`` and
  ``OfflineBucketApi/copy(from:to:destinationBucket:)`` take `destinationBucket:`, in place of
  supabase-swift's option values.
- ``OfflineBucketApi/remove(paths:)`` and ``OfflineBucketApi/copy(from:to:destinationBucket:)``
  return nothing; an upload's ``OfflineBucketApi/FileUploadResponse`` names the full path `key`;
  ``OfflineBucketApi/createSignedURLs(paths:expiresIn:)`` returns
  ``OfflineBucketApi/SignedURL`` values, and fails whole when one path cannot be signed.
- An upload from a file URL is not offered, and an empty upload is refused.

### Not offered

A count or `head` on a live read, a count on a write, `order` or `limit` on an update or a delete,
`explain()`, function arguments sent by GET, and resumable uploads.
