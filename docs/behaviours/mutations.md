# Writes and the offline queue

What happens to an insert, upsert, update or delete when the device is online, when it is offline, and when the server rejects it.

> **Not written yet.** This page is a placeholder in the planned site. The headings below are its
> outline; the content will be written for 0.1.0, once the API it describes has settled.

## Mutation strategies

Facts about the engine as it is, one row per condition; the conditions are those of the
[one-shot read matrix](read-strategies.md#behaviour-matrix), where warm and cold say whether the
queries a write touches are cached. Each cell names the test that pins it.

| Condition | `OnlineOnly` | `QueueIfOffline` (default) |
|---|---|---|
| **Online** | Sent at once, never behind the queue: `Applied`. The cached queries are not patched.<br><small>`OnlineOnlyWriteTest.an_online_only_write_leaves_the_cached_queries_as_they_were`, `HeadSendTest.an_online_only_write_does_not_wait_behind_the_queue`</small> | Recorded in the queue, then sent at once when it heads its user's queue: `Applied`, and patched into every cached query it touches and every live query it now matches. Rows the server returned are used: an INSERT or UPSERT keeps the columns it wrote beside them; an UPDATE that asked for rows back changes those rows alone, and none when it got none. A write whose filter the cache cannot evaluate marks the lists it may reach stale, and their live readers fetch again. Behind pending writes: `Queued`, and a pass the engine starts sends it. A server's refusal is thrown and the write leaves the queue. These leave it `Queued` instead: a 503 or 520; PostgREST's schema cache not knowing the table or a column yet (`PGRST205`, `PGRST204`), until the tenth such refusal, which rejects it; and a session token the Auth server will not refresh, since the write was never sent.<br><small>`OnlineWriteJoinsQueueTest.an_online_write_with_nothing_pending_is_applied_at_once_and_leaves_no_queue_row`, `ListMembershipTest.an_online_insert_into_list_b_lands_in_list_b_only`, `OfflineStoreEmptyCacheReactivityTest.live_cache_less_subscriber_sees_a_materialized_online_upsert`, `ReturnedRowsOverlayTest.an_update_that_returns_its_row_leaves_the_servers_columns_in_the_cache`, `WritesThatSelectTest.an_insert_that_selects_its_id_keeps_the_columns_it_wrote`, `WritesThatSelectTest.an_update_that_selects_and_gets_no_row_back_changes_nothing`, `StaleQueriesTest.an_online_update_whose_filter_the_cache_cannot_evaluate_marks_the_lists_it_may_reach`, `StaleQueriesTest.a_live_reader_of_a_list_the_write_may_reach_fetches_again_once_and_no_other`, `OnlineWriteJoinsQueueTest.a_write_that_joins_the_queue_is_sent_by_a_pass_the_engine_starts_itself`, `HeadSendTest.a_refusal_at_the_head_is_thrown_and_the_write_leaves_the_queue`, `HeadSendTest.a_503_at_the_head_leaves_the_write_queued`, `SchemaCacheMissTest.a_write_meeting_a_column_the_schema_cache_does_not_know_is_queued`, `SchemaCacheMissTest.a_table_still_missing_after_ten_spaced_misses_is_rejected`, `ExpiredSessionWriteTest.online_a_write_whose_refresh_is_refused_is_queued_as_never_sent`</small> |
| **Offline, warm** | Throws the connectivity failure; nothing is queued.<br><small>`OfflineMutationQueueDecisionTest.insert_connectivity_failure_under_OnlineOnly_rethrows`, `MutationOutcomeTest.insert_online_only_connectivity_failure_throws_and_does_not_enqueue`</small> | `Queued`, and shown at once in each cached query that admits it; an UPDATE also brings a row cached by another query with the same columns into a list it starts matching. Not queued, the failure thrown instead: an UPDATE or DELETE with no filter or one the queue cannot record exactly, and a dry run.<br><small>`MutationOutcomeTest.insert_connectivity_failure_returns_queued_and_enqueues`, `ListMembershipTest.a_queued_insert_into_list_b_shows_in_list_b_only`, `QueuedUpdateJoinsListsTest.a_queued_update_moves_a_server_row_into_the_list_it_starts_matching`, `OfflineMutationQueueDecisionTest.a_filter_the_queue_cannot_capture_refuses_to_queue_and_rethrows_the_failure`, `OfflineMutationQueueDecisionTest.an_unfiltered_update_is_refused_for_queueing_and_rethrows`, `DryRunWriteTest.an_offline_dry_run_is_refused_and_never_queued`</small> |
| **Offline, cold** | As offline, warm.<br><small>`OfflineMutationQueueDecisionTest.insert_connectivity_failure_under_OnlineOnly_rethrows`</small> | `Queued`; a reader of a query with nothing cached sees the queued rows it admits.<br><small>`OfflineStoreEmptyCacheReactivityTest.queued_offline_insert_is_visible_via_substrate_without_writing_a_room_row`</small> |
| **Session restoring** | Sent at once, as supabase-kt sends any request meanwhile: on the anon key.<br><small>`WritesWhileRestoringTest.an_online_only_write_is_sent_at_once_with_the_token_sent_meanwhile`</small> | `Queued` for the stored user, online or not, and sent with that user's token once the session settles.<br><small>`WritesWhileRestoringTest.an_online_write_is_queued_until_the_session_settles_then_sent_as_the_user`, `WritesWhileRestoringTest.a_write_made_offline_while_restoring_is_queued`, `QueueIdentityTest.a_restoring_session_is_its_stored_user`</small> |

The queue replays to the client's default schema only. A `QueueIfOffline` write to a table in
another schema is therefore sent at once and never queued, and does not wait for writes queued to
the default schema. Offline it throws an `IOException` that names the schema. Online, the cached
queries of that schema's table are marked stale. `OnlineOnly` sends it as it sends any write. Name
the schema with `from(schema, table)` or the request's `schema`, not a raw `Content-Profile` header,
which the engine does not read.
<small>`WritesToAnotherSchemaTest.online_it_is_sent_to_that_schema`,
`WritesToAnotherSchemaTest.offline_it_fails_naming_the_schema_and_nothing_is_queued`,
`WritesToAnotherSchemaTest.it_does_not_wait_for_writes_queued_to_the_default_schema`,
`WritesToAnotherSchemaTest.the_cached_queries_of_that_schema_are_fetched_again`,
`NonDefaultSchemaTest.an_online_only_write_to_another_schema_is_sent_to_it`</small>

A write runs its `request` lambda up to three times: once built to read its schema, filter and
`select()`, once to tell a dry run, and once to send it (an `OnlineOnly` write, once). It must
configure the same request every time: no side effects, and no clock or counter read inside it.
<small>`RequestLambdaRunsTest.an_update_sent_at_once_runs_the_request_three_times`,
`RequestLambdaRunsTest.a_write_queued_offline_runs_the_request_three_times`,
`RequestLambdaRunsTest.an_online_only_write_runs_the_request_once`</small>

## `MutationOutcome`

## What gets queued

## Filter shapes that can be queued

## Upsert options

## Values several devices change

A queued write replays exactly what you sent; a value that several devices change concurrently (a
counter, a running total) is computed on the server, and clients write only the facts that feed it.

## Idempotency

## Pending counts
