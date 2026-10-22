// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.closingAfter
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pins the cache key of every query in `cache-key-fixture.json`: the key a network read's response
 * is stored under, read back from the query cache. Apps hold warm caches under these keys. A query
 * naming a call the pinned supabase-kt minor lacks ([callsMissingFromPinnedSupabaseKt]) is pinned
 * from the minor that has it on, and one the library refuses ([REFUSED_CASES]) is never stored.
 */
class CacheKeyFixtureTest {

    @Test
    fun every_fixture_query_is_stored_under_its_recorded_key() = runTest {
        // Given
        val cases =
            CacheKeyFixture.cases().filterNot { case ->
                case.request.namesAnyCall(callsMissingFromPinnedSupabaseKt)
            }

        // When
        val refused = mutableListOf<String>()
        val mismatches = cases.mapNotNull { case ->
            val stored =
                try {
                    storedCacheKeyOf(case)
                } catch (_: IllegalArgumentException) {
                    refused += case.name
                    return@mapNotNull null
                }
            if (stored == case.cacheKey) null
            else "${case.name}: expected <${case.cacheKey}> but was <$stored>"
        }

        // Then — and the queries the library refuses before sending are the ones it should
        assertEquals(emptyList(), mismatches, "cache keys changed")
        assertEquals(REFUSED_CASES, refused, "the refused queries changed")
    }

    @Test
    fun the_fixture_names_forty_or_more_distinct_queries() {
        // Given
        val cases = CacheKeyFixture.cases()

        // When
        val names = cases.map { case -> case.name }

        // Then
        assertTrue(cases.size >= 40, "only ${cases.size} cases")
        assertEquals(names.size, names.toSet().size, "a case name repeats")
    }
}

/**
 * Reads [case] through a client online, then returns the one cache key its response was stored
 * under.
 */
internal suspend fun storedCacheKeyOf(case: CacheKeyFixtureCase): String {
    val queryCacheDao = FakeQueryCacheDao()
    val server = RecordingPostgrestServer(answer = { okJson("""[{"id":1}]""") })
    newTestOfflineClient(
            supabase = server.supabaseClient(),
            queryCacheDao = queryCacheDao,
        )
        .closingAfter { client ->
            val builder =
                if (case.schema == null) client.from(case.table)
                else client.from(case.schema, case.table)
            builder.select(
                columns = CacheKeyFixture.columnsOf(case),
                readStrategy = ReadStrategy.NetworkOnly,
                request = CacheKeyFixture.requestOf(case.request),
            )
        }
    // A table outside the default schema is cached as `schema.table`
    val schemas =
        listOfNotNull(case.schema) +
            case.request
                .map { call -> call.jsonArray }
                .filter { call -> call[0].jsonPrimitive.content == "schema" }
                .map { call -> call[1].jsonPrimitive.content }
    val rows =
        (listOf(case.table) + schemas.map { schema -> "$schema.${case.table}" })
            .distinct()
            .flatMap(queryCacheDao::rowsOf)
    check(rows.size == 1) { "${case.name} stored ${rows.size} rows" }
    return rows.single().cacheKey
}

/**
 * The fixture's queries the library refuses before sending: two with two values for one top-level
 * parameter, which supabase-kt would send only the first of (two filters on one column, and a raw
 * parameter given two values), and an `in` list with a member holding a quote or a backslash and
 * none of `,.:()`, which supabase-kt escapes but does not quote, so the server would read the
 * escapes as the member. Their keys stay pinned for the day the refusal is lifted.
 */
private val REFUSED_CASES: List<String> =
    listOf(
        "two_filters_on_one_column",
        "raw_param_two_values",
        "in_members_with_quote_backslash_and_unicode",
    )

/** Whether this call list, or a call nested in it (an `or` group's), is named one of [names]. */
private fun JsonElement.namesAnyCall(names: Set<String>): Boolean =
    this is JsonArray &&
        ((firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content in names ||
            any { element -> element.namesAnyCall(names) })
