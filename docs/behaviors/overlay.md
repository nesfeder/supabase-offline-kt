# Optimistic overlay

What a read returns is the cached rows with the user's writes that have not reached them yet laid
on top, oldest first. So a write shows in every list at once, online or offline, and a list never
loses it between the moment it is sent and the moment the list is fetched again.
`hasPendingMutations` on a result or a snapshot says whether a queued write changed the rows, and
each `PendingMutation`'s `rowKeys` say which rows it touches, as the sample's cloud marks use them:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TasksModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Tasks/TasksModel.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:pending-marks"
    ```

=== "iOS"

    ```swift
    --8<-- "Tasks/TasksModel.swift:pending-marks"
    ```

## What it applies

- **The user's queued writes**, in the order they were made: every write in the mutation queue that
  belongs to the current user, or to no user yet. Another user's writes are never laid over.
- **Writes a replay pass sent** that a cached list has not seen yet. Such a write stays in the
  overlay until each cached list of its table has been fetched again after it, then retires. A
  table keeps at most its newest 1,000 such writes: past that, the oldest go whether or not every
  list has seen them.

A write sent at once and accepted is patched into the cached lists instead: the rows the server
returned, or the rows sent when it returned none, are laid into every cached list of the table that
admits them, matched by key and merged with the columns the write sent, and a live read with
nothing cached gets a list of the written rows it admits. A write whose effect the cache cannot
work out (an UPDATE or DELETE filtered by something it cannot evaluate, an upsert without keys, a
write to another schema) marks the lists it may reach stale, and their live readers fetch again.
An `OnlineOnly` write is not patched in: the list changes when it is fetched again.

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
  <small>`OverlayLimitsTest.a_queued_row_is_appended_past_the_order_and_limit_of_every_page_it_matches`</small>
- **A queued row carries only the columns it wrote.** In a list that embeds another table
  (`author:profiles(name)`) or renames a column (`heading:title`), it shows without the embed and
  under the name it was written with, which can fail a decode of the whole list: keep queued rows
  out of such lists, or decode those columns as nullable. A row read with other columns, or with
  other options for a table it embeds (a limit, an order, a filter), never joins a list, which is
  fetched again instead.
  <small>`OverlayLimitsTest.a_queued_row_carries_only_the_columns_it_wrote`,
  `BorrowedRowsShapeTest.a_row_does_not_join_a_list_that_renames_its_columns`,
  `BorrowedRowsShapeTest.a_row_read_with_one_embedded_row_does_not_join_a_list_that_reads_them_all`,
  `WritesThatSelectTest.an_insert_that_selects_its_id_keeps_the_columns_it_wrote`</small>
- **What the engine cannot evaluate touches nothing.** Besides any condition but `eq`, `neq`, `in`
  and `is` (`lt`, `gt` and the other ordered comparisons, `like`, `ilike`, full-text search, the
  array and range operators), that is any comparison whose answer depends on the column's type:
  digits equal but for leading zeros, numbers equal only as a float, boolean words, quoted
  literals, `inf`, MAC addresses. Such a pending UPDATE or DELETE shows once it has synced and the
  list is fetched again; an online write the cache cannot patch marks the lists it may reach stale.
  <small>`OverlayLimitsTest.a_pending_update_whose_filter_the_engine_cannot_evaluate_changes_nothing_shown`,
  `TypeDependentComparisonTest.a_pending_update_or_delete_never_touches_a_row_it_cannot_tell`,
  `AdmissionSemanticsTest.every_family_the_engine_cannot_evaluate_admits_the_row`,
  `StaleQueriesTest.an_online_update_whose_filter_the_cache_cannot_evaluate_marks_the_lists_it_may_reach`</small>
- **A known limit: a JSON `null` is read as SQL `NULL`.** PostgREST sends a SQL `NULL` and a
  `jsonb` column holding the JSON value `null` alike, so the engine cannot tell them apart and reads
  both as `NULL`. The one case this gets wrong is that `jsonb` value: `is(column, null)` matches it
  where Postgres does not, so a pending write filtered that way changes such a row on screen, and a
  locally written row with it joins a list filtered that way, until the list is fetched again.
  Reading it as unknown instead would keep every `is(column, null)` write off screen until it
  synced.
  <small>`RowMatchingTest.a_json_null_is_read_as_sql_null_even_where_the_column_is_jsonb`,
  `PatchCachedQueriesTest.update_with_is_null_condition_patches_unread_row_and_skips_read_row`</small>
- **Keys are read as UUIDs.** A key written in capitals matches the same key sent back in lower
  case, so the row is shown once.
  <small>`UuidKeyCaseTest.a_row_written_in_capitals_and_sent_back_in_lower_case_is_shown_once`,
  `UuidKeyCaseTest.an_update_filtered_by_a_capitalized_key_changes_the_row_sent_in_lower_case`</small>
- **An online update brings in only rows a cached list holds.** A row that no cached list holds
  does not join a list it starts matching after an online update, even when the update asked for
  the row back; the list shows it once it is fetched again.
  <small>`OverlayLimitsTest.an_online_update_of_a_row_no_cached_list_holds_brings_it_into_none`</small>
- **An upsert keys on `id` unless it names its conflict columns.** On a table without an `id`
  column, an upsert without `onConflict` adds a row next to the one it replaces on the server. Name
  the conflict columns.
  <small>`OverlayLimitsTest.an_upsert_to_a_table_without_an_id_column_that_names_no_conflict_columns_adds_a_row`,
  `OverlayLimitsTest.an_upsert_that_names_its_conflict_columns_merges_by_them`</small>
- **An ignore-duplicates upsert shows as though it landed.** Offline, the engine cannot tell whether
  the server already holds the row, so it shows in the lists it matches even when the server will
  keep it where it is.
  <small>`OverlayLimitsTest.an_ignore_duplicates_upsert_shows_a_row_the_server_may_keep_elsewhere`</small>
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
  is fetched again.
