# On-device data

Everything the library keeps on the device is in one SQLite database, `supabase_offline.db`, and
one directory of downloaded files, both in the app's private storage. This page lists where they
are, what they hold, and what removes it.

## Where it lives

| | Database | Downloaded files |
| --- | --- | --- |
| Android, by default | The app's database directory: `getDatabasePath("supabase_offline.db")` | `<filesDir>/supabase_offline/` |
| iOS, by default | `Library/Application Support/supabase_offline.db` | `Library/Application Support/supabase_offline/` |
| With `dataDirectory` set | `<dataDirectory>/supabase_offline.db` | `<dataDirectory>/` |

On iOS, a database found in `Documents`, where an earlier build kept it, is moved to the default
place with its side files and its file cache when the client opens, unless the default place
already holds a database (then it is left, and a warning logged). `Documents` is for the user's
own files: with file sharing on, the Files app shows them, and the user can delete them.

Beside the database, SQLite keeps its write-ahead log and its index, `supabase_offline.db-wal` and
`supabase_offline.db-shm`. On iOS the plugin excludes its files from iCloud and device backups; on
Android you leave them out of Auto Backup yourself
([Project setup](../getting-started/setup.md#backups)).

The Kotlin library runs the database on the SQLite it bundles, on Android and on iOS, so every
install of it runs the same SQLite version. The Swift package links the system's SQLite instead and
bundles none ([Under the hood](../help/under-the-hood.md#what-the-package-holds)).

## The files

A downloaded object is stored at `storage/<bucket>/<object path>` under the files directory, when
its bucket and path are short printable names. Any other name, and every image-transform variant,
is stored under `storage-hashed/`, in a file named by a hash of its key. So is a name with a
segment that starts with a letter and a colon, like `u:42`, which a path library would read as a
Windows drive. A download is written to a temporary file first, written through to the disk, and
moved into place whole; a temporary file a crash left behind is deleted once it is more than a day
old. The row records the file's size. A file whose size differs from its row, which a power loss
right after a download can leave, is not served: it is deleted with its row, and the object is
downloaded again when next asked for.

## The database

Room schema version 2, identity hash `5f1a878933e35eebaa2b64aac7fccab0`.

### `object_cache`

One cached list per row, and the library's own records in reserved rows. Primary key: `table_name`, `signature`.

| Column | Type | Null | What it holds |
| --- | --- | --- | --- |
| `table_name` | TEXT | no | The table, as `schema.table` outside the default schema; or the reserved name of the library's records |
| `signature` | TEXT | no | The cache key: the table, its sorted query parameters and its range |
| `payload` | TEXT | no | The JSON array PostgREST answered, as text; for a reserved row, the record |
| `updated_at` | INTEGER | no | When the server sent the list, in milliseconds since 1970; negated while the list is stale |
| `synced_through` | INTEGER, default `0` | no | The newest replayed write the list has seen, so the overlay skips it |

### `storage_objects`

One downloaded Storage object per row; the file itself is on disk. Primary key: `key`.
It is unique on (`bucketId`, `path`).

| Column | Type | Null | What it holds |
| --- | --- | --- | --- |
| `key` | TEXT | no | `<bucket>::<path>` |
| `bucketId` | TEXT | no | The bucket |
| `path` | TEXT | no | The object's path in the bucket |
| `localPath` | TEXT | no | Where the file was written; its place is always derived from the bucket and path |
| `sizeBytes` | INTEGER | yes | The file's size |
| `eTag` | TEXT | yes | Not used: always `NULL` |
| `lastFetchedEpochMs` | INTEGER | no | When the file was downloaded, in milliseconds since 1970: `CachedFile.fetchedAt` |

### `mutation_cache`

The mutation queue: one write per row, in `id` order. Primary key: `id`, autoincrement.
It is indexed on (`owner`, `synced_at_ms`) and indexed on (`table_name`).

| Column | Type | Null | What it holds |
| --- | --- | --- | --- |
| `id` | INTEGER | no | The write's id and its place in the queue: `PendingMutation.id` |
| `table_name` | TEXT | no | The table the write targets |
| `operation` | TEXT | no | `INSERT`, `UPSERT`, `UPDATE` or `DELETE` |
| `body_json` | TEXT | no | The body the write sends, as JSON text; `{}` for a DELETE |
| `filter_json` | TEXT | yes | The UPDATE's or DELETE's filter, encoded so a replay rebuilds it |
| `created_at_ms` | INTEGER | no | When the write was made, by the device's clock |
| `synced_at_ms` | INTEGER | yes | When a pass replayed it; `NULL` while it waits |
| `default_to_null` | INTEGER | yes | The insert or upsert's `defaultToNull` |
| `on_conflict` | TEXT | yes | The upsert's `onConflict` columns |
| `ignore_duplicates` | INTEGER | yes | The upsert's `ignoreDuplicates` |
| `owner` | TEXT | yes | Who made the write: `user:` and the user's id, or `anonymous` when nobody was signed in; `NULL` when none was recorded |
| `sync_sequence` | INTEGER | yes | The order in which replayed writes were accepted, against the lists that have seen them |
| `schema_cache_misses` | INTEGER, default `0` | no | How many times PostgREST's schema cache did not know the table or a column yet |
| `send_unconfirmed` | INTEGER, default `0` | no | Whether a send may have reached the server without its answer arriving |
| `base_version` | TEXT | yes | The version of the row an UPDATE or DELETE targets, as the device last saw it; `NULL` when the write replays unchecked |
| `base_json` | TEXT | yes | That row's columns the write sets, and its version, as JSON text; `NULL` with `base_version` |


The library's own records (the rejected list, the conflicted list, whose cache it is, and each
cached list's query shape) are JSON text in reserved rows of `object_cache`, under a table name 94
bytes long: Postgres cuts identifiers at 63 bytes, so no table of yours can have that name, and
`from()` refuses it. A reserved row is never evicted, counted against `cacheSizeBytes`, read as a
query or overlaid.

The conflicted list is one more such row, bounded as the
rejected list is (its newest 100 entries, within 512 K characters). Each entry holds a held write's
body and filter keys, its base (the columns it sets and the version, as the device saw them) and
the server's row as the replay read it: the key, the version and the columns the write sets or
filters on.

## What removes it

| Event | What goes | What stays |
| --- | --- | --- |
| `clearLocalData()` | Every cached list, every downloaded file, every queued write (never sent), the rejected list and the conflicted list. The database is rewritten without them, as far as open reads allow ([details](../behaviors/sign-out.md#what-clearlocaldata-deletes)) | The database file itself, empty |
| `storage.clearFileCache()` | Every downloaded file, in every bucket, with its row in `storage_objects` | The cached lists, the mutation queue, the rejected list and the conflicted list. The database is not rewritten without the deleted rows, as it is by `clearLocalData()` |
| A bucket's `evictCachedFile(path)` | That object's downloaded files, the untransformed one and every image transform, with their rows; with a transform, that transform's file and row alone | Every other object's files, and everything else |
| A user signs out, or another signs in (with Auth) | Once the session settles as someone else: the cached lists and the downloaded files | The mutation queue, the rejected list and the conflicted list: each user's writes wait for that user |
| Sign-out without Auth, or with your own token provider | Nothing: there is one identity for the life of the install | Everything |
| `close()` | Nothing | Everything, for the next client |
| Eviction | Cached lists fetched longest ago, past `cacheSizeBytes`; files used least recently, past `fileCacheSizeBytes` | The queue, which is never evicted; the rejected list keeps its newest 100 entries (see [Failures and the rejected list](../behaviors/failures.md#the-rejected-list)) |
| Uninstalling the app | Everything: it is all in the app's private storage | |

## Schema versions

0.1.0 ships schema version 1. Version 2 adds `base_version` and `base_json` to `mutation_cache`,
for [conflict detection](../behaviors/conflicts.md#what-the-queue-records), with a migration that
keeps every queued write: a write queued under version 1 has no base and replays unchecked, as it
would have. Each later schema change is a new version with a migration that keeps the queued
writes, and a minor release of the library. Going back to an older version of the library across a
schema version is not supported, and loses the writes queued since, with the rejected and
conflicted lists. See
[Compatibility](../project/compatibility.md).
