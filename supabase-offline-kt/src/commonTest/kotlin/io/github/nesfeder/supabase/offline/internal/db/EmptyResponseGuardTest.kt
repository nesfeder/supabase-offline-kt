// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.internal.cache.CacheWriteOutcome
import io.github.nesfeder.supabase.offline.internal.cache.QueryCache
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.isFromCache
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest

/**
 * Tests for the empty-clobber guard in [QueryCache.cacheResponse]: **an unattributed empty never
 * persists.**
 *
 * The dominant cause of an empty SELECT response in practice is a cold-start auth race — a refresh
 * fires before the supabase-kt session has finished restoring, the request goes out without a
 * usable JWT, RLS filters every row, and the server returns `200 OK` with body `[]`. Written over
 * previously-cached good data, that response makes every reactive `selectAsFlow` observer re-emit
 * an empty list — a loading placeholder that never resolves, or a list screen that goes blank —
 * until the subscription is recreated. Written into a *cold* slot it is the same bug one step
 * earlier: nothing is overwritten, but the phantom empty becomes the answer every later cache-first
 * read is served. Both are refused, along with an empty over an already-empty slot (which would
 * only refresh the timestamp keeping the phantom alive).
 *
 * The guard is bypassed by `authoritativeEmpty = true` — the resolver's signal that the producing
 * request went out with an authenticated session, so an empty result is the server's real answer
 * (every matching row deleted, e.g. from another device, or the user genuinely has no rows). An
 * attributed empty writes into a cold slot too, which is how a legitimately-empty first read still
 * seeds the cache for collectors.
 *
 * Only a JSON array is ever written: a blank body is stored as `[]`, and a body of another shape or
 * media type (a proxy's block page at `200`) is refused, so a read served the cache can decode it.
 */
class EmptyResponseGuardTest {

    @Test
    fun authoritative_empty_replaces_non_empty_cache() = runTest {
        // Given — cache holds a non-empty payload (e.g. the user's plan graph)
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload(
            table = "plans",
            cacheKey = "sig-A",
            payload = """[{"id":"plan-1"}]""",
        )
        val store = buildStore(queryCacheDao)

        // When — a refresh whose request carried an authenticated session returns an empty
        // array (every row matching the query was deleted from another device)
        store.queryCache.cacheResponse(
            table = "plans",
            cacheKey = "sig-A",
            payload = "[]",
            authoritativeEmpty = true,
        )

        // Then — the authoritative empty replaces the stale rows so observers converge
        val row = queryCacheDao.getRow("plans", "sig-A")
        assertNotNull(row)
        assertEquals("[]", row.payload)
    }

    @Test
    fun authoritative_flag_has_no_effect_on_non_empty_payloads() = runTest {
        // Given — cache holds a stale non-empty payload
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("plans", "sig-A", """[{"id":"plan-1"}]""")
        val store = buildStore(queryCacheDao)

        // When — an auth-ready refresh returns different non-empty data
        store.queryCache.cacheResponse(
            table = "plans",
            cacheKey = "sig-A",
            payload = """[{"id":"plan-2"}]""",
            authoritativeEmpty = true,
        )

        // Then — the new payload replaces the cached one exactly as an ordinary write would
        val row = queryCacheDao.getRow("plans", "sig-A")
        assertNotNull(row)
        assertEquals("""[{"id":"plan-2"}]""", row.payload)
    }

    @Test
    fun empty_response_does_not_clobber_non_empty_cache() = runTest {
        // Given — cache holds a non-empty payload from a previous successful refresh
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload(
            table = "ratings",
            cacheKey = "sig-A",
            payload = """[{"id":1,"rating":80}]""",
        )
        val store = buildStore(queryCacheDao)

        // When — a refresh fires before the auth session is restored and the server
        // responds 200 OK with an empty array (RLS-filtered)
        store.queryCache.cacheResponse(table = "ratings", cacheKey = "sig-A", payload = "[]")

        // Then — the previously-cached non-empty payload is preserved
        val row = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(row)
        assertEquals("""[{"id":1,"rating":80}]""", row.payload)
    }

    @Test
    fun whitespace_only_response_does_not_clobber_non_empty_cache() = runTest {
        // Given — cache holds a non-empty payload
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", """[{"id":"project-1"}]""")
        val store = buildStore(queryCacheDao)

        // When — a refresh returns a whitespace-only body (some PostgREST proxy edge cases)
        store.queryCache.cacheResponse(table = "projects", cacheKey = "sig-A", payload = "   ")

        // Then — the cache row is unchanged
        val row = queryCacheDao.getRow("projects", "sig-A")
        assertNotNull(row)
        assertEquals("""[{"id":"project-1"}]""", row.payload)
    }

    @Test
    fun unattributed_empty_is_dropped_when_no_prior_row_exists() = runTest {
        // Given — no cached row for this signature yet (first read of this query, cold slot)
        val queryCacheDao = FakeQueryCacheDao()
        val store = buildStore(queryCacheDao)

        // When — a refresh that could not attribute its emptiness to an authenticated session
        // returns `[]` (the restore-window / signed-out shape, indistinguishable on the wire
        // from a genuinely-empty table)
        val outcome =
            store.queryCache.cacheResponse(
                table = "attachments",
                cacheKey = "sig-A",
                payload = "[]",
            )

        // Then — nothing is written. A cold slot is exactly where an RLS-shaped empty does its
        // damage: with no prior row to protect, it used to be upserted with a fresh updatedAt and
        // every later cache-first read served that phantom empty as the user's data. A genuinely
        // empty table still seeds the cache — via `authoritativeEmpty`, not via this path.
        assertEquals(CacheWriteOutcome.REFUSED_EMPTY, outcome)
        assertNull(queryCacheDao.getRow("attachments", "sig-A"))
        assertEquals(0, queryCacheDao.writes)
    }

    @Test
    fun unattributed_empty_is_dropped_when_existing_row_is_already_empty() = runTest {
        // Given — the cache row already holds an empty `[]`
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", "[]")
        val store = buildStore(queryCacheDao)

        // When — an unattributed refresh returns an empty array
        val outcome =
            store.queryCache.cacheResponse(table = "ratings", cacheKey = "sig-A", payload = "[]")

        // Then — refused as well. Nothing is lost by dropping it (the payload is identical), and
        // rewriting would only stamp a fresh updatedAt, keeping a possibly-phantom empty alive
        // past the cache duration that would otherwise expire it.
        assertEquals(CacheWriteOutcome.REFUSED_EMPTY, outcome)
        val row = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(row)
        assertEquals("[]", row.payload)
        assertEquals(0, queryCacheDao.writes)
    }

    @Test
    fun authoritative_empty_is_written_into_a_cold_slot() = runTest {
        // Given — no cached row for this signature yet
        val queryCacheDao = FakeQueryCacheDao()
        val store = buildStore(queryCacheDao)

        // When — the request carried an authenticated session and the user genuinely has no rows
        val outcome =
            store.queryCache.cacheResponse(
                table = "attachments",
                cacheKey = "sig-A",
                payload = "[]",
                authoritativeEmpty = true,
            )

        // Then — the attributed empty seeds the cold slot, so collectors observe an empty list
        // instead of waiting forever for a row that is never coming. This is the half of the
        // cold-slot rule that must NOT change: only *unattributed* empties are dropped.
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        val row = queryCacheDao.getRow("attachments", "sig-A")
        assertNotNull(row)
        assertEquals("[]", row.payload)
    }

    @Test
    fun authoritative_empty_rewrites_an_already_empty_row_and_advances_its_timestamp() = runTest {
        // Given — a cache row holding an empty `[]` stamped long ago
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", "[]", updatedAt = 0L)
        val store = buildStore(queryCacheDao, nowMillis = { 5_000L })

        // When — an auth-ready refresh confirms the table is still empty
        val outcome =
            store.queryCache.cacheResponse(
                table = "ratings",
                cacheKey = "sig-A",
                payload = "[]",
                authoritativeEmpty = true,
            )

        // Then — the row is rewritten so `updatedAt` moves forward. The attributed path must keep
        // doing this even though the unattributed one now refuses it: a cache-duration read that
        // has just re-confirmed the emptiness with the server should not immediately expire and
        // re-fetch, which is exactly what a frozen timestamp would cause.
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        val row = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(row)
        assertEquals("[]", row.payload)
        assertEquals(5_000L, row.updatedAt)
    }

    @Test
    fun non_empty_response_overwrites_existing_non_empty_cache() = runTest {
        // Given — cache holds a stale non-empty payload
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", """[{"id":1,"rating":80}]""")
        val store = buildStore(queryCacheDao)

        // When — a refresh succeeds with new server data
        store.queryCache.cacheResponse(
            table = "ratings",
            cacheKey = "sig-A",
            payload = """[{"id":1,"rating":92}]""",
        )

        // Then — the new payload replaces the cached one
        val row = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(row)
        assertEquals("""[{"id":1,"rating":92}]""", row.payload)
    }

    @Test
    fun guard_is_scoped_per_cache_key() = runTest {
        // Given — the same table has two distinct cached signatures (e.g. one per user_id)
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", """[{"id":1,"rating":80}]""")
        val store = buildStore(queryCacheDao)

        // When — an authoritative empty arrives for a *different* signature (no prior row)
        store.queryCache.cacheResponse(
            table = "ratings",
            cacheKey = "sig-B",
            payload = "[]",
            authoritativeEmpty = true,
        )

        // Then — sig-A is untouched and sig-B gets the empty payload as its initial seed: the
        // guard reasons per signature, not per table, so one query's emptiness never reaches
        // another query's rows
        val rowA = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(rowA)
        assertEquals("""[{"id":1,"rating":80}]""", rowA.payload)
        val rowB = queryCacheDao.getRow("ratings", "sig-B")
        assertNotNull(rowB)
        assertEquals("[]", rowB.payload)
    }

    @Test
    fun guard_does_not_block_explicit_clear_table() = runTest {
        // Given — cache holds a non-empty payload
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", """[{"id":1,"rating":80}]""")
        val store = buildStore(queryCacheDao)

        // When — the caller explicitly clears the table (e.g. on logout)
        store.clearTable("ratings")

        // Then — the cache row is removed; the empty-clobber guard only protects against
        // implicit empty SELECT responses, not explicit deletes
        assertNull(queryCacheDao.getRow("ratings", "sig-A"))
    }

    @Test
    fun an_authoritative_blank_body_is_stored_as_an_empty_list() = runTest {
        // Given — cache holds a non-empty payload
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", """[{"id":"project-1"}]""")
        val store = buildStore(queryCacheDao)

        // When — a signed-in refresh returns a whitespace-only body (a proxy's stand-in for `[]`)
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = "   ",
                authoritativeEmpty = true,
            )

        // Then — the empty list is what is stored, and what a read is served
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        assertEquals("[]", queryCacheDao.getRow("projects", "sig-A")?.payload)
        val read = store.queryOverlay.cachedPayloadOverlaid("projects", "sig-A")
        assertEquals("[]", read?.overlay?.payload)
    }

    @Test
    fun a_body_that_is_not_a_json_array_is_refused_as_untrusted() = runTest {
        // Given — cache holds the user's rows
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", """[{"id":"project-1"}]""")
        val store = buildStore(queryCacheDao)

        // When — a proxy's block page, and a JSON object, come back at 200
        val outcomes =
            listOf("<!doctype html><html><body>Blocked</body></html>", """{"message":"blocked"}""")
                .map { body ->
                    store.queryCache.cacheResponse(
                        table = "projects",
                        cacheKey = "sig-A",
                        payload = body,
                        authoritativeEmpty = true,
                    )
                }

        // Then — neither is written, and the rows stay
        assertEquals(
            listOf(CacheWriteOutcome.REFUSED_UNTRUSTED, CacheWriteOutcome.REFUSED_UNTRUSTED),
            outcomes,
        )
        assertEquals("""[{"id":"project-1"}]""", queryCacheDao.getRow("projects", "sig-A")?.payload)
        assertEquals(0, queryCacheDao.writes)
    }

    @Test
    fun a_200_html_page_leaves_the_cached_rows_in_place_and_reads_are_served_them() = runTest {
        // Given — the list cached, and a server (a captive portal, say) answering every request
        // with an HTML page at 200
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "projects", """[{"id":"project-1"}]""")
        val server =
            RecordingPostgrestServer(
                answer = {
                    respond(
                        content = "<!doctype html><html><body>Sign in to the network</body></html>",
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                    )
                }
            )
        val logger = RecordingLogSink()
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                queryCacheDao = queryCacheDao,
                engineLogger = logger.asEngineLogger(EngineLogLevel.WARNING),
            )

        // When — a network-first read gets the page
        val networkFirst =
            client.from("projects").select(readStrategy = ReadStrategy.NetworkFirst())

        // Then — the page is not stored; the read falls back to the cached rows, and a cache-only
        // read still has them; the warning names the table and nothing of the page
        assertEquals("""[{"id":"project-1"}]""", networkFirst.data)
        assertTrue(networkFirst.isFromCache)
        val warnings = logger.entriesAt(EngineLogLevel.WARNING).map { entry -> entry.message }
        assertEquals(
            listOf(
                "SELECT projects answered with something other than a list of rows; serving cached state if any"
            ),
            warnings,
        )
        assertEquals(
            """[{"id":"project-1"}]""",
            queryCacheDao.getRow("projects", "projects")?.payload,
        )
        val cacheOnly = client.from("projects").select(readStrategy = ReadStrategy.CacheOnly())
        assertEquals("""[{"id":"project-1"}]""", cacheOnly.data)
        assertEquals(1, server.requests.count { request -> request.method == "GET" })
    }

    @Test
    fun a_blank_body_is_answered_and_stored_as_an_empty_list() = runTest {
        // Given — nothing cached, and a server (a proxy standing in for PostgREST) answering a
        // blank body under a settled session
        val queryCacheDao = FakeQueryCacheDao()
        val server = RecordingPostgrestServer(answer = { okJson("") })
        val client =
            newTestOfflineClient(supabase = server.supabaseClient(), queryCacheDao = queryCacheDao)

        // When
        val answer = client.from("projects").select(readStrategy = ReadStrategy.NetworkOnly)

        // Then — the caller decodes an empty list, and the cache holds one
        assertEquals("[]", answer.data)
        assertEquals("[]", queryCacheDao.getRow("projects", "projects")?.payload)
    }

    @Test
    fun a_json_array_under_another_media_type_is_refused() = runTest {
        // Given — the list cached, and a server answering rows as `text/plain`
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "projects", """[{"id":"project-1"}]""")
        val server =
            RecordingPostgrestServer(
                answer = {
                    respond(
                        content = """[{"id":"project-9"}]""",
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                    )
                }
            )
        val client =
            newTestOfflineClient(supabase = server.supabaseClient(), queryCacheDao = queryCacheDao)

        // When
        val answer = client.from("projects").select(readStrategy = ReadStrategy.NetworkFirst())

        // Then — not stored, and the read is served the cached rows
        assertEquals("""[{"id":"project-1"}]""", answer.data)
        assertTrue(answer.isFromCache)
        assertEquals(
            """[{"id":"project-1"}]""",
            queryCacheDao.getRow("projects", "projects")?.payload,
        )
    }

    @Test
    fun the_fallback_from_a_page_honors_max_age_and_never_answers_network_only() = runTest {
        // Given — the list cached long ago, and a server answering a page
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "projects", """[{"id":"project-1"}]""", updatedAt = 0)
        val page = "<!doctype html><html><body>Sign in to the network</body></html>"
        val server =
            RecordingPostgrestServer(
                answer = {
                    respond(
                        content = page,
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                    )
                }
            )
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                queryCacheDao = queryCacheDao,
                nowMillis = { 10.minutes.inWholeMilliseconds },
            )

        // When — reads whose strategy may not serve rows that old, or the cache at all
        val pastMaxAge =
            client.from("projects").select(readStrategy = ReadStrategy.NetworkFirst(1.minutes))
        val networkOnly = client.from("projects").select(readStrategy = ReadStrategy.NetworkOnly)
        val withinMaxAge =
            client.from("projects").select(readStrategy = ReadStrategy.NetworkFirst(1.minutes))

        // Then — the first two are served the body as it came, as with nothing cached; rows within
        // `maxAge` are served, and the page was never stored
        assertEquals(page, pastMaxAge.data)
        assertFalse(pastMaxAge.isFromCache)
        assertEquals(page, networkOnly.data)
        assertEquals(page, withinMaxAge.data)
        assertEquals(
            """[{"id":"project-1"}]""",
            queryCacheDao.getRow("projects", "projects")?.payload,
        )
    }

    @Test
    fun a_json_array_under_the_json_media_type_with_a_charset_is_stored() = runTest {
        // Given — nothing cached, and PostgREST's own answer, `application/json; charset=utf-8`
        val queryCacheDao = FakeQueryCacheDao()
        val server =
            RecordingPostgrestServer(
                answer = {
                    respond(
                        content = """[{"id":"project-1"}]""",
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"),
                    )
                }
            )
        val client =
            newTestOfflineClient(supabase = server.supabaseClient(), queryCacheDao = queryCacheDao)

        // When
        client.from("projects").select(readStrategy = ReadStrategy.NetworkOnly)

        // Then
        assertEquals(
            """[{"id":"project-1"}]""",
            queryCacheDao.getRow("projects", "projects")?.payload,
        )
    }

    private fun buildStore(
        queryCacheDao: QueryCacheDao,
        nowMillis: () -> Long = { 0L },
    ): TestStore =
        newTestStore(
            queryCacheDao = queryCacheDao,
            nowMillis = nowMillis,
        )
}
