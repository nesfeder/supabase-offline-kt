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
| **Offline, warm** | Throws the connectivity failure; nothing is queued.<br><small>`OfflineMutationQueueDecisionTest.insert_connectivity_failure_under_OnlineOnly_rethrows`, `MutationOutcomeTest.insert_online_only_connectivity_failure_throws_and_does_not_enqueue`</small> | `Queued`, and shown at once in each cached query that admits it; an UPDATE also brings a row cached by another query with the same columns into a list it starts matching. Not queued, a `MutationNotQueueableException` thrown instead, its cause the connectivity failure: an UPDATE or DELETE with no filter or one the queue cannot record exactly, and a dry run.<br><small>`MutationOutcomeTest.insert_connectivity_failure_returns_queued_and_enqueues`, `ListMembershipTest.a_queued_insert_into_list_b_shows_in_list_b_only`, `QueuedUpdateJoinsListsTest.a_queued_update_moves_a_server_row_into_the_list_it_starts_matching`, `OfflineMutationQueueDecisionTest.a_filter_the_queue_cannot_capture_is_not_queued_and_reports_why`, `OfflineMutationQueueDecisionTest.an_unfiltered_update_is_not_queued_and_reports_why`, `DryRunWriteTest.an_offline_dry_run_is_refused_and_never_queued`</small> |
| **Offline, cold** | As offline, warm.<br><small>`OfflineMutationQueueDecisionTest.insert_connectivity_failure_under_OnlineOnly_rethrows`</small> | `Queued`; a reader of a query with nothing cached sees the queued rows it admits.<br><small>`OfflineStoreEmptyCacheReactivityTest.queued_offline_insert_is_visible_via_substrate_without_writing_a_room_row`</small> |
| **Network access off** (`disableNetwork()`) | Throws `NetworkDisabledException`; nothing is sent.<br><small>`NetworkSwitchTest.while_disabled_calls_that_must_reach_the_server_throw_and_send_nothing`</small> | `Queued`, and nothing is sent until access is back on.<br><small>`NetworkSwitchTest.while_disabled_reads_come_from_the_cache_writes_queue_and_nothing_is_sent`</small> |
| **Session restoring** | Sent at once, as supabase-kt sends any request meanwhile: on the anon key.<br><small>`WritesWhileRestoringTest.an_online_only_write_is_sent_at_once_with_the_token_sent_meanwhile`</small> | `Queued` for the stored user, online or not, and sent with that user's token once the session settles.<br><small>`WritesWhileRestoringTest.an_online_write_is_queued_until_the_session_settles_then_sent_as_the_user`, `WritesWhileRestoringTest.a_write_made_offline_while_restoring_is_queued`, `QueueIdentityTest.a_restoring_session_is_its_stored_user`</small> |

The queue replays to the client's default schema only. A `QueueIfOffline` write to a table in
another schema is therefore sent at once and never queued, and does not wait for writes queued to
the default schema. Offline it throws a `MutationNotQueueableException` that names the schema. Online, the cached
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

What is waiting, what the server refused, and when the library sends by itself. Each row names the
test that pins it.

| Fact | Test |
|---|---|
| `pendingMutations`, `pendingMutationCount` and `pendingMutationCountFlow` show the current user's queued writes, and those with no user recorded that they may take over; never another user's, who cannot discard one by its id either. | `QueueVisibilityAcrossUsersTest.a_users_queued_writes_are_invisible_to_another_user_who_cannot_discard_them` |
| `discardPendingMutation` waits for a send of the write in flight, then removes it, unsent: the write is lost. | `DiscardWhileSendingTest.a_write_being_sent_is_discarded_once_its_send_ends` |
| Discarding the write at the head of the queue lets the writes behind it go, with no call from the app. | `DiscardWhileSendingTest.discarding_the_write_at_the_head_lets_the_writes_behind_it_go` |
| `rejectedMutations` lists the current user's refused writes only; another user can neither see, retry nor dismiss one by guessing its id. | `QueueVisibilityAcrossUsersTest.a_users_rejection_is_invisible_to_another_user`, `QueueVisibilityAcrossUsersTest.another_user_cannot_retry_or_dismiss_a_rejection_by_guessing_its_id` |
| `retryRejectedMutation` queues the write again, and it is sent. | `QueueVisibilityAcrossUsersTest.the_user_who_made_a_refused_write_can_retry_it_and_it_is_sent_again` |
| A refusal whose body was too large to keep says so (`isRetryable` is `false`), and retrying it throws. | `QueueVisibilityAcrossUsersTest.a_rejection_whose_body_was_too_large_to_keep_says_so_and_cannot_be_retried` |
| A write sent at once that the server refuses throws the server's error, and is not listed: the list is for refusals found on replay. | `HeadSendTest.a_refusal_at_the_head_is_thrown_and_the_write_leaves_the_queue` |
| `syncPendingMutations` reports what it sent, what the server refused (with its `ServerError`) and what is still queued. | `SyncReportTest.a_sync_reports_what_it_sent_what_was_refused_and_what_remains` |
| Offline it returns at once, with the writes still queued, and `syncStatus.lastSyncError` says the network. | `SyncReportTest.a_sync_while_offline_returns_with_the_writes_still_queued_and_says_why` |
| `lastSyncError` carries the status and code, never the server's text, a filter value or a token. | `SyncReportTest.the_failure_a_sync_reports_carries_no_filter_value_or_server_text` |
| An insert whose earlier send had in fact arrived is counted in `alreadyOnServerCount`, not inserted twice. | `SyncReportTest.an_insert_whose_earlier_send_had_arrived_is_counted_as_already_on_the_server` |
| A sync that meets the library sending the same writes waits for it, then returns with what is still queued after both. | `SyncReportTest.a_sync_that_meets_the_library_sending_the_same_writes_waits_and_reports_what_remains` |
| With `autoSync` on, the network coming back, network access turned back on, and the session settling anew send queued writes by themselves. | `AutoSyncTest.the_network_coming_back_sends_queued_writes_by_itself`, `AutoSyncTest.turning_network_access_back_on_sends_queued_writes_by_itself`, `AutoSyncTest.the_session_settling_with_writes_queued_sends_them_unless_auto_sync_is_off` |
| With `autoSync = false` those three send nothing, but an online write queued behind an earlier one is still sent without the app asking. | `AutoSyncTest.with_auto_sync_off_the_network_coming_back_sends_nothing`, `AutoSyncTest.with_auto_sync_off_turning_network_access_back_on_sends_nothing`, `AutoSyncTest.with_auto_sync_off_an_online_write_behind_a_queued_one_is_sent_without_the_app_asking` |
| After a pass stopped on a server's `Retry-After`, the library's own next pass waits at least that long, at most an hour. | `RetryAfterTest.the_next_pass_waits_at_least_as_long_as_the_server_asked`, `RetryAfterTest.a_server_cannot_hold_the_library_back_for_more_than_an_hour` |
| `syncStatus` follows the queue, the rejected list and the passes, and says when the queue was last emptied. | `SyncStatusTest.the_status_follows_the_queue_and_says_when_it_was_emptied`, `SyncStatusTest.the_status_counts_the_current_users_refused_writes`, `SyncStatusTest.the_status_says_a_pass_is_running_while_one_is` |
| `syncStatus.network` is `DISABLED` as soon as `disableNetwork()` returns; a new client starts with access on. | `NetworkSwitchTest.the_status_says_disabled_as_soon_as_the_switch_returns_and_not_after_it_is_back_on`, `NetworkSwitchTest.a_new_client_starts_with_network_access_on` |
| While access is off, a Storage download is answered from the file cache, and an upload, a delete or a move throws `NetworkDisabledException`, leaving the cache as it was. | `StorageWhileNetworkDisabledTest.a_download_while_access_is_off_is_answered_from_the_file_cache`, `StorageWhileNetworkDisabledTest.uploads_and_deletes_while_access_is_off_throw_and_leave_the_cache_alone` |
