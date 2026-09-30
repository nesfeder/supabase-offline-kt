# Cache size and eviction

How the query cache is kept within its size limit.

> **Not written yet.** This page is a placeholder in the planned site. The headings below are its
> outline; the content will be written for 0.1.0, once the API it describes has settled.

## What is measured

## Defaults

## When eviction runs

## What is evicted

## Unlimited and minimum sizes

## Database file size

## Storage file size

## Eviction and live queries

Facts about the engine as it is. Eviction takes the payloads fetched longest ago first; a payload
marked stale keeps its place by the time it was fetched. A payload a live reader shows is never
evicted while it is shown: the oldest payload nobody observes goes instead, and the reader never
drops to "no data". Up to 500 live queries are protected this way; past that, or for a reader that
starts while an eviction pass is deleting, a `CacheFirstThenNetwork` reader fetches its list again
and a `CacheOnly` one shows no data.
<small>`LiveEvictionTest.eviction_keeps_the_oldest_payload_while_a_reader_shows_it`,
`OfflineStoreEvictionTest.eviction_removes_oldest_rows_until_within_limit_keeping_newest`</small>
