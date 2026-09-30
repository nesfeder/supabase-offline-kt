# Optimistic overlay

How writes that have not reached the server yet appear in cached query results.

> **Not written yet.** This page is a placeholder in the planned site. The headings below are its
> outline; the content will be written for 0.1.0, once the API it describes has settled.

## What it applies

## Online writes

## How rows are matched

## Limits

Facts about the engine as it is. Each names the test that pins it.

- **Order, limit and pages are not applied.** A queued row is appended after a list's rows, past
  its limit, and shows on every page whose filters admit it. An edited sort column does not move
  it. The server's order returns with the next fetch.
  <small>`OverlayLimitsTest.a_queued_row_is_appended_past_the_order_and_limit_of_every_page_it_matches`</small>
- **A queued row carries only the columns it wrote.** In a list that embeds another table
  (`author:profiles(name)`) or renames a column (`heading:title`), it shows without the embed and
  under the name it was written with, which can fail a decode of the whole list. A row read with
  other columns never joins a list; an online write that asked for rows back (`select()`) brings
  the server's columns.
  <small>`OverlayLimitsTest.a_queued_row_carries_only_the_columns_it_wrote`,
  `BorrowedRowsShapeTest.a_row_does_not_join_a_list_that_renames_its_columns`,
  `WritesThatSelectTest.an_insert_that_selects_its_id_keeps_the_columns_it_wrote`</small>
- **What the engine cannot evaluate touches nothing.** A pending UPDATE or DELETE changes only the
  rows its filter provably matches, while a new row joins every list whose filter does not provably
  exclude it, so the user's own row is never hidden. The engine evaluates `eq`, `neq`, `in`, `is` and
  the other plain comparisons it can decide from the JSON alone; `lt`, `gt` and the other ordered
  comparisons, `like`, `ilike`, full-text search and the array and range operators are left to the
  server, and so is any comparison whose answer depends on the column's type: digits equal but for
  leading zeros, numbers equal only as a float, boolean words, quoted literals, `inf`, MAC
  addresses. Such a write shows once it has synced and the list is fetched again. An online write
  the cache cannot patch marks the lists it may reach stale instead.
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
  `OfflineStoreApplyLocalMutationTest.update_with_is_null_condition_patches_unread_row_and_skips_read_row`</small>
- **Keys are read as UUIDs.** A key written in capitals matches the same key sent back in lower
  case, so the row is shown once.
  <small>`UuidKeyCaseTest.a_row_written_in_capitals_and_sent_back_in_lower_case_is_shown_once`,
  `UuidKeyCaseTest.an_update_filtered_by_a_capitalised_key_changes_the_row_sent_in_lower_case`</small>
- **An upsert keys on `id` unless it names its conflict columns.** On a table without an `id`
  column, an upsert without `onConflict` adds a row next to the one it replaces on the server. Name
  the conflict columns.
  <small>`OverlayLimitsTest.an_upsert_to_a_table_without_an_id_column_that_names_no_conflict_columns_adds_a_row`,
  `OverlayLimitsTest.an_upsert_that_names_its_conflict_columns_merges_by_them`</small>
- **An ignore-duplicates upsert shows as though it landed.** Offline, the engine cannot tell whether
  the server already holds the row, so it shows in the lists it matches even when the server will
  keep it where it is.
  <small>`OverlayLimitsTest.an_ignore_duplicates_upsert_shows_a_row_the_server_may_keep_elsewhere`</small>

## Tips
