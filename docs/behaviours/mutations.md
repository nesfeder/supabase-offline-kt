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
| **Online** | Sent at once, never behind the queue: `Applied`. The cached queries are not patched.<br><small>`OnlineOnlyWriteTest.an_online_only_write_leaves_the_cached_queries_as_they_were`, `HeadSendTest.an_online_only_write_does_not_wait_behind_the_queue`</small> | Recorded in the queue, then sent at once when it heads its user's queue: `Applied`, and patched into every cached query it touches and every live query it now matches, with the rows the server returned when it returned any. Behind pending writes: `Queued`, and a pass the engine starts sends it. A server's refusal is thrown and the write leaves the queue; a 503 or 520 leaves it queued.<br><small>`OnlineWriteJoinsQueueTest.an_online_write_with_nothing_pending_is_applied_at_once_and_leaves_no_queue_row`, `ListMembershipTest.an_online_insert_into_list_b_lands_in_list_b_only`, `OfflineStoreEmptyCacheReactivityTest.live_cache_less_subscriber_sees_a_materialized_online_upsert`, `ReturnedRowsOverlayTest.an_update_that_returns_its_row_leaves_the_servers_columns_in_the_cache`, `OnlineWriteJoinsQueueTest.a_write_that_joins_the_queue_is_sent_by_a_pass_the_engine_starts_itself`, `HeadSendTest.a_refusal_at_the_head_is_thrown_and_the_write_leaves_the_queue`, `HeadSendTest.a_503_at_the_head_leaves_the_write_queued`</small> |
| **Offline, warm** | Throws the connectivity failure; nothing is queued.<br><small>`OfflineMutationQueueDecisionTest.insert_connectivity_failure_under_OnlineOnly_rethrows`, `MutationOutcomeTest.insert_online_only_connectivity_failure_throws_and_does_not_enqueue`</small> | `Queued`, and shown at once in each cached query that admits it. Not queued, the failure thrown instead: an UPDATE or DELETE with no filter or one the queue cannot record exactly, and a dry run.<br><small>`MutationOutcomeTest.insert_connectivity_failure_returns_queued_and_enqueues`, `ListMembershipTest.a_queued_insert_into_list_b_shows_in_list_b_only`, `OfflineMutationQueueDecisionTest.a_filter_the_queue_cannot_capture_refuses_to_queue_and_rethrows_the_failure`, `OfflineMutationQueueDecisionTest.an_unfiltered_update_is_refused_for_queueing_and_rethrows`, `DryRunWriteTest.an_offline_dry_run_is_refused_and_never_queued`</small> |
| **Offline, cold** | As offline, warm.<br><small>`OfflineMutationQueueDecisionTest.insert_connectivity_failure_under_OnlineOnly_rethrows`</small> | `Queued`; a reader of a query with nothing cached sees the queued rows it admits.<br><small>`OfflineStoreEmptyCacheReactivityTest.queued_offline_insert_is_visible_via_substrate_without_writing_a_room_row`</small> |
| **Session restoring** | Sent at once, as supabase-kt sends any request meanwhile: on the anon key.<br><small>`WritesWhileRestoringTest.an_online_only_write_is_sent_at_once_with_the_token_sent_meanwhile`</small> | `Queued` for the stored user, online or not, and sent with that user's token once the session settles.<br><small>`WritesWhileRestoringTest.an_online_write_is_queued_until_the_session_settles_then_sent_as_the_user`, `WritesWhileRestoringTest.a_write_made_offline_while_restoring_is_queued`, `QueueIdentityTest.a_restoring_session_is_its_stored_user`</small> |

A `QueueIfOffline` write to a table outside the client's default schema throws
`UnsupportedOperationException`, online or not; `OnlineOnly` sends it.
<small>`NonDefaultSchemaTest.a_queued_write_to_another_schema_is_refused_while_online_too`,
`NonDefaultSchemaTest.an_online_only_write_to_another_schema_is_sent_to_it`</small>

## `MutationOutcome`

## What gets queued

## Filter shapes that can be queued

## Upsert options

## Delta columns

## Idempotency

## Pending counts
