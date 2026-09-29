package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.closingAfter
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the cache key of every query in `cache-key-fixture.json`: the key a network read's response
 * is stored under, read back from the query cache. Apps hold warm caches under these keys.
 */
class CacheKeyFixtureTest {

    @Test
    fun every_fixture_query_is_stored_under_its_recorded_key() = runTest {
        // Given
        val cases = CacheKeyFixture.cases()

        // When
        val mismatches = cases.mapNotNull { case ->
            val stored = storedCacheKeyOf(case)
            if (stored == case.cacheKey) null else "${case.name}: expected <${case.cacheKey}> but was <$stored>"
        }

        // Then
        assertEquals(emptyList(), mismatches, "cache keys changed")
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

/** Reads [case] through a client online, then returns the one cache key its response was stored under. */
internal suspend fun storedCacheKeyOf(case: CacheKeyFixtureCase): String {
    val objectDao = FakeObjectDao()
    val server = RecordingPostgrestServer(answer = { okJson("""[{"id":1}]""") })
    newTestOfflineClient(supabase = server.supabaseClient(), store = newTestOfflineStore(objectDao = objectDao)).closingAfter { client ->
        val builder = if (case.schema == null) client.from(case.table) else client.from(case.schema, case.table)
        builder.select(
            columns = CacheKeyFixture.columnsOf(case),
            readStrategy = ReadStrategy.NetworkOnly,
            request = CacheKeyFixture.requestOf(case.request),
        )
    }
    val rows = objectDao.rowsOf(case.table)
    check(rows.size == 1) { "${case.name} stored ${rows.size} rows" }
    return rows.single().signature
}
