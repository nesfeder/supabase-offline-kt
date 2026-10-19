# Supported features

What the library does with each part of supabase-kt 3.6, the version it is built against. The
library caches PostgREST lists and Storage downloads, and queues PostgREST writes; everything else
goes to the server as supabase-kt sends it.

| Term | Meaning |
| --- | --- |
| **Cached** | The server's answer is kept on the device, and served from there as the read's strategy says, when the server cannot be reached |
| **Queued** | The write is saved on the device first and sent in order; offline, it is sent when the network comes back |
| **Sent directly** | Sent at once, never queued, and the cache is kept in step with what the server applied; offline it fails |
| **Passed through** | Sent as supabase-kt sends it, and nothing is kept; offline it fails with supabase-kt's own error |
| **Refused** | The library throws before anything is sent |

While network access is switched off (`disableNetwork()`), whatever must reach the server throws
`NetworkDisabledException` instead of being sent.

## PostgREST

Reached through `supabase.offline.from(table)` or `from(schema, table)`.

| Call | What the library does |
| --- | --- |
| `select` of a list of rows | **Cached**, under its query: filters, order, columns, `limit`, `range`. The user's queued writes are laid over every answer |
| `selectAsFlow` of a list of rows | **Cached**, and emitted again as the cached rows change; refreshed from the server as its strategy says |
| `select` of another shape: `single()`, `head`, a `count` (with rows or without), `csv()`, `geojson()`, `stripNulls()`, `explain()` | **Passed through**, never stored. Under `CacheOnly`, refused with `CacheMissException` |
| `selectAsFlow` of another shape | The server's answer, emitted once; offline the flow fails |
| `insert`, `upsert` (`QueueIfOffline`) | **Queued**, with `onConflict`, `ignoreDuplicates` and `defaultToNull` kept for the replay. `select()` brings the server's rows back when the write is sent at once |
| `update`, `delete` (`QueueIfOffline`) with a filter the queue can record | **Queued**. The queue records filters on the table's own columns, joined as AND, with any of supabase-kt's operators |
| `update`, `delete` with a filter the queue cannot record | **Sent directly**: no filter, an `or { }` or `and { }` group, a negated filter (`filterNot`), an embedded table's column, `limit`, `order` or `offset`. Offline, `MutationNotQueueableException` |
| `update`, `delete` (`QueueIfOffline`) of one cached row, on a table with a version column | **Queued**, and replayed only while the server's row still has the version the device saw; a row that moved meanwhile is a conflict, settled by the app's resolver or held ([Conflicts and merging](../behaviors/conflicts.md)). Not checked: the send at once, inserts and upserts, which replay as made |
| Two top-level filters on one column, in a read, an update or a delete | **Refused** with `IllegalArgumentException`: supabase-kt would send only the first. Use one `and { }` group |
| A write carrying `Prefer: tx=rollback` (a dry run) | **Sent directly**, never queued. A rolled-back write is not patched into the cache |
| A write to a schema other than the client's default | **Sent directly**: the queue replays to the default schema only |
| Any write with `mutationStrategy = OnlineOnly` | **Passed through**; the cached lists change when they are fetched again |
| `supabase.offline.rpc(…)` | **Passed through**: a function may change data, so it is neither cached nor queued |
| `applyServerRecord`, `applyServerDelete` | Applies a row the server reports to every cached list of the table; sends nothing |

Not in supabase-kt 3.6, so not here: `maybeSingle()`, `dryRun()` (add the `Prefer` header
yourself) and the `isdistinct` operator.

## Storage

Reached through `supabase.offline.storage.from(bucket)`.

| Call | What the library does |
| --- | --- |
| `downloadAuthenticated`, `downloadPublic`, to bytes or to a channel | **Cached**: network first, the cached copy when the network fails. A "not found" drops the copy. An image transform is cached apart, per transform |
| `cachedFile`, `ensureCachedFile`, and the platform helpers (`ensureLocalFile`, `ensureLocalUri`, `localUriIfCached` on Android; `ensureLocalPath`, `localPathIfCached` on iOS) | The file cache itself: `cachedFile` never touches the network, `ensureCachedFile` downloads only when nothing is cached |
| `upload`, `update`, `delete`, `move`, `copy`, `uploadAsFlow`, resumable uploads, uploads to a signed URL | **Passed through**, online only, never queued. A write that reaches the server drops the cached copies it made stale |
| `createSignedUrl`, `createSignedUrls`, `list`, `info`, `exists` | **Passed through** |
| `publicUrl`, `authenticatedUrl` and the other URL builders | Built on the device; nothing is sent |
| Bucket management: `createBucket`, `listBuckets`, `emptyBucket`, `deleteBucket` and the rest | Not on the offline client: call it on `supabase.storage` |
| An object name with `%`, `?` or `#` | **Passed through**, never cached: supabase-kt 3.6 puts it in the URL as written. `ensureCachedFile` refuses it |
| An object path that cannot map onto a file (a leading `/`, an empty, `.` or `..` segment, a `\`, a control character) | **Passed through**, never cached. `ensureCachedFile` refuses it |

`purgeCache` is not in supabase-kt 3.6. On an app that forces 3.8, call it on `supabase.storage`'s
bucket, not on the offline one.

## Auth, Realtime and Functions

| Plugin | What the library does |
| --- | --- |
| Auth | Optional. **Passed through**: sign-in, sign-out and the session are supabase-kt's. The library reads the session to know whose cache and queue it serves, and sends each queued write with its own user's token |
| Realtime | Optional. **Used** for the tables `realtime { }` names ([Configuration](configuration.md#realtime)): their inserts, updates and deletes are applied to the cache as they happen, and each join reads them again. Broadcast and presence stay on supabase-kt. A channel's records can also be applied by hand with `applyServerRecord` and `applyServerDelete`; see [Applying Realtime events](../behaviors/realtime.md) |
| Functions | Not used: call Edge Functions through supabase-kt |

GraphQL is not covered.

## Platforms

| | |
| --- | --- |
| Android | API 23 and later |
| iOS, from Kotlin | 15 and later: `iosArm64` and `iosSimulatorArm64` |
| iOS, from Swift | Through the Swift package; see [iOS apps in Swift](../getting-started/swift.md) |
| JVM, JS, Wasm | Not supported |

One client per database, and one database per process: see
[Project setup](../getting-started/setup.md#create-the-client).
