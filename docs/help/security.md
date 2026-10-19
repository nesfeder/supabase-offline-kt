# Security and privacy

The library keeps one user's rows, unsent writes and downloaded files on the device, in the app's
private storage, unencrypted beyond what the platform does. This page says what is there, how to
keep it out of backups and crash reports, and where the real security boundary is: row-level
security on your project.

## What is stored

| Data | Where | Holds |
| --- | --- | --- |
| Cached lists | The database | The rows PostgREST returned to the user's list reads |
| The mutation queue | The database | Each unsent write's body and filter, and the id of the user who made it |
| The rejected list | The database | Each refused write's body, and the server's message, details and hint |
| The conflicted list | The database | Each write held after a conflict: its body, the columns it sets as the device saw them, and the server's columns as the replay read them |
| The cache owner | The database | Which user the cache belongs to |
| Downloaded files | The files directory | The bytes of each Storage object downloaded through the offline bucket |

The database is SQLite, and nothing in it or in the files is encrypted by the library. Both are in
the app's private storage, which other apps cannot read, and the device's own storage encryption
applies to them as to the rest of the app's data. On iOS they are in `Library/Application Support`,
which the Files app does not show, never in `Documents`. Where each lives is on
[On-device data](../reference/on-device-data.md).

## Backups

Android's Auto Backup includes the database and the file cache unless you leave them out, and a
restore onto a new device brings them back under whoever signs in next. Exclude them in your
backup rules, or put the data under `noBackupFilesDir`; see
[Project setup](../getting-started/setup.md#backups). On iOS the plugin excludes its files from
iCloud and device backups itself.

## A shared device

With supabase-kt's Auth plugin, the cache and the files belong to one user at a time: they are
dropped when the session settles as someone else, and nothing cached for one user is served to
the next. Queued writes stay with the user who made them, and are sent only with that user's
token. Call `clearLocalData()` when a user signs out for good, to delete their unsent writes too;
see [Signing out and switching users](../behaviors/sign-out.md). Without Auth there is one
identity for the life of the install.

## Logs

The library logs through supabase-kt's logging, to the processor your client was built with,
tagged `Supabase-Offline`. It writes nothing to the console itself.

- **`INFO`, `WARNING` and `ERROR`** messages name tables, buckets, operations, queued writes' ids,
  counts, HTTP statuses and exception types. They carry no rows, request bodies, filter values,
  tokens, object paths or server messages. A failure in the library's own background work is
  logged by its type, never its message. A version a guarded write carries is a filter value
  like any other, and never appears in them; neither does a resolver's conflict, which a failing
  resolver reports by the table and the failure's type alone. These are safe to keep as
  crash-report breadcrumbs.
- **`DEBUG`** messages carry request bodies, the server's responses and failures' text: rows,
  filter values, object paths. Keep `DEBUG` out of release builds.

supabase-kt's own plugins log at the same `defaultLogLevel`, and at `DEBUG` they log requests
with their URLs; its Realtime plugin logs each channel's join payload at `DEBUG`, the user's token
included, so a client that names tables in `realtime { }` has one more reason to keep `DEBUG` out
of release builds. Set the level for release builds where you build the client; see
[Configuration](../reference/configuration.md#loglevel).

## Exceptions

supabase-kt's exceptions put the request in their messages:

- `HttpRequestException`, the connectivity failure, says "HTTP request to" the full URL;
- `RestException`, and so `PostgrestRestException`, gives the URL with its host masked but its path
  and query string intact, and the request's headers with the key and token masked.

A URL carries the filter values (`?id=eq.…`, `?email=eq.…`) and, for Storage, the object path. So
do not send an exception's `message`, its `toString()` or its stack trace's text to analytics or
crash reporting unredacted. The same goes for `RefreshState.Stalled.cause`, which is
supabase-kt's exception. Report the kind and the status instead:

From the samples: [LibraryFailures.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LibraryFailures.kt) · [SampleFailure.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/SampleFailure.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/LibraryFailures.kt:reportable-failure"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/SampleFailure.swift:reportable-failure"
    ```

What the library builds itself is safe to report: `SyncStatus.lastSyncError` is a
`SyncFailedException` that keeps the HTTP status and the PostgREST or Postgres code and nothing of
the original message. A `RejectedMutation` is not: its `body` is the row you wrote, and its
`serverError.details` can hold the row's values (Postgres puts a duplicate key's value there).

## Keys

Ship the project's **publishable** key (`sb_publishable_…`), or the legacy `anon` key, in the app:
it is public by design, and anyone can take it out of an app. Never ship the secret key
(`sb_secret_…`) or the legacy `service_role` key: either one skips row-level security.

## Row-level security

Row-level security on your project is the security boundary, not the library. Every request the
library makes is the user's own, with the user's token, and the server decides what it may read
and write:

- a queued write is sent with its owner's token or not at all, so a write is never applied under
  another user's session;
- the cache holds only what row-level security let the user read;
- a write a policy refuses is thrown when it is sent at once, or lands on the rejected list when
  it is replayed.

Write your policies for every table the app reads or writes, and for every Storage bucket.

## Reporting a vulnerability

Report it privately, through GitHub's private vulnerability reporting, never in an issue, a
discussion or a pull request. The process, what is in scope and which versions get fixes are in
the repository's
[security policy](https://github.com/nesfeder/supabase-offline-kt/blob/main/SECURITY.md).
