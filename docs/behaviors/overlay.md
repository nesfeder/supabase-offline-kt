# Optimistic overlay

What a read returns is the cached rows with the user's writes that have not reached them yet laid
on top, oldest first. So a write shows in every list at once, online or offline, and a list never
loses it between the moment it is sent and the moment the list is fetched again.
`hasPendingMutations` on a result or a snapshot says whether a queued write changed the rows, and
`pendingRowKeys` says which: the primary key of each row a queued write added, changed or removed.
Both come from the pass that laid the writes over the rows, so they always describe the rows they
come with. Each `PendingMutation`'s `rowKeys` say which rows a write touches in any list, but the
queue's flow emits on its own, so a mark read from it can show an emission early or late. The
Android sample's cloud marks read `pendingRowKeys`; the iOS sample's still follow the queue:

From the samples: [LibraryMappings.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LibraryMappings.kt) · [TasksModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Tasks/TasksModel.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/LibraryMappings.kt:pending-marks"
    ```

=== "iOS"

    ```swift
    --8<-- "Tasks/TasksModel.swift:pending-marks"
    ```

## What it applies

- **The user's queued writes**, in the order they were made: every write in the mutation queue that
  belongs to the current user, or to no user yet. Another user's writes are never laid over.
- **Writes a replay pass sent** that a cached list has not seen yet; a guarded replay's returned
  row is also written into the lists ([Tips](#tips)). Such a write stays in the overlay until each
  cached list of its table has been fetched again after it, then retires. A table keeps at most its
  newest 1,000 such writes: past that, the oldest go whether or not every list has seen them.

A write sent at once and accepted is patched into the cached lists instead: the rows the server
returned, or the rows sent when it returned none, are laid into every cached list of the table that
admits them, matched by key and merged with the columns the write sent, and a live read with
nothing cached gets a list of the written rows it admits. A write whose effect the cache cannot
work out (an UPDATE or DELETE filtered by something it cannot evaluate, an upsert without keys, a
write to another schema) marks the lists it may reach stale, and their live readers fetch again.
An `OnlineOnly` write is not patched in: the list changes when it is fetched again.

## Which rows are named

| Row | In `pendingRowKeys` |
| --- | --- |
| Added or changed by a queued write | Its key, with the values the row shows |
| Removed by a queued write: deleted, or changed so the query no longer admits it | Its key as the row last showed it, though the rows no longer hold it |
| Shown without its whole key: a row from the server read with a select that leaves a key column out, or a key column holding `null` | None. `hasPendingMutations` still counts it, so the set can be empty while the flag is `true`. A queued insert or upsert carries every column it wrote, so one that wrote its key is named even in such a list |
| Changed by a write a sync pass already sent | None, even while the list still shows the write until it is fetched again |

A key is an object of the table's key columns, `id` unless you set another with `primaryKey`, so
`{"id": 42}` names the row whose `id` is 42. The set is empty whenever `hasPendingMutations` is
`false`.

## How rows are matched

| Write | Effect on a cached list |
| --- | --- |
| INSERT | Its rows join every list whose filters may admit them, after the list's own rows |
| UPSERT | Merged into the row with the same `onConflict` columns, or the same key; otherwise joins as an insert |
| UPDATE | Patches the rows its filter provably matches, and moves a row into or out of a list as its membership changes |
| DELETE | Removes the rows its filter provably matches |

The library evaluates `eq`, `neq`, `in` and `is`, with Postgres's rules for nulls. Anything else is
unknown, and unknown admits a new row into a list, so your own row is never hidden, but never lets
a queued UPDATE or DELETE touch a row. A row is recognized by the table's primary key, `id` unless
you set another with `primaryKey` ([Configuration](../reference/configuration.md#primarykeys)).

## Limits

- **Order, limit and pages are not applied.** A queued row is appended after a list's rows, past
  its limit, and shows on every page whose filters admit it; an edited sort column does not move
  it. The server's order returns with the next fetch, so sort and page in the app for lists that
  show your own writes.
- **A queued row carries only the columns it wrote.** In a list that embeds another table
  (`author:profiles(name)`) or renames a column (`heading:title`), it shows without the embed and
  under the name it was written with, which can fail a decode of the whole list: keep queued rows
  out of such lists, or decode those columns as nullable. A row read with other columns, or with
  other options for a table it embeds (a limit, an order, a filter), never joins a list, which is
  fetched again instead.
- **What the engine cannot evaluate touches nothing.** Besides any condition but `eq`, `neq`, `in`
  and `is` (`lt`, `gt` and the other ordered comparisons, `like`, `ilike`, full-text search, the
  array and range operators), that is any comparison whose answer depends on the column's type:
  digits equal but for leading zeros, numbers equal only as a float, boolean words, quoted
  literals, `inf`, MAC addresses. Such a pending UPDATE or DELETE shows once it has synced and the
  list is fetched again; an online write the cache cannot patch marks the lists it may reach stale.
- **A known limit: a JSON `null` is read as SQL `NULL`.** PostgREST sends a SQL `NULL` and a
  `jsonb` column holding the JSON value `null` alike, so the engine cannot tell them apart and reads
  both as `NULL`. The one case this gets wrong is that `jsonb` value: `is(column, null)` matches it
  where Postgres does not, so a pending write filtered that way changes such a row on screen, and a
  locally written row with it joins a list filtered that way, until the list is fetched again.
  Reading it as unknown instead would keep every `is(column, null)` write off screen until it
  synced.
- **Keys are read as UUIDs.** A key written in capitals matches the same key sent back in lower
  case, so the row is shown once.
- **An online update brings in only rows a cached list holds.** A row that no cached list holds
  does not join a list it starts matching after an online update, even when the update asked for
  the row back; the list shows it once it is fetched again.
- **An upsert keys on `id` unless it names its conflict columns.** On a table without an `id`
  column, an upsert without `onConflict` adds a row next to the one it replaces on the server. Name
  the conflict columns.
- **An ignore-duplicates upsert shows as though it landed.** Offline, the engine cannot tell whether
  the server already holds the row, so it shows in the lists it matches even when the server will
  keep it where it is.
- **Lists cached long ago and never fetched again.** Once a table has 1,000 replayed writes newer
  than a cached list, that list stops showing the oldest of them, and a row a replayed DELETE
  removed reappears in it, until the list is fetched again. A list a live reader shows is fetched
  again when its read starts, so this affects lists that sit unread through 1,000 writes.

## Tips

- **Make ids, and every column a list decodes, on the device.** A queued row lacks the columns the
  server fills in (a default `created_at`, say) until the list is fetched again, and a list that
  decodes such a column into a non-nullable property fails. Write `id` and `created_at` yourself,
  as the [sample app](../samples/android.md) does.
- **Ask an online write for its rows back** with `select()`: when it is sent at once, the server's
  values (defaults, triggers) are patched into the cache with it. A write that waited in the queue
  does not use them, so its replay brings nothing back: the list shows the server's values once it
  is fetched again. The exception is a guarded replay, an update of one cached row of a table with
  a version column: it asks for the key, the version and the columns it sets, and the row it gets
  back is written into every cached list that holds it, so the list shows the row once, at the
  server's new version ([What a replay sends](conflicts.md#what-a-replay-sends)).
