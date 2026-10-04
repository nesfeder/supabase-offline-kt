// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A live list against the writes and refreshes that change what it shows. The cached payload, the
 * table's queued mutations and the refresh change together at the writer; every emission must be
 * one consistent view of them, never a row twice, a row missing, or `Idle` before the rows. Run
 * over the in-memory tables and over each platform's real database; [LiveReadSourceOrderTest] runs
 * the same steps with one source held back.
 */
internal object LiveReadConsistency {

    /** The table every scenario reads and writes. */
    const val TABLE: String = "todos"

    /** The title of the row a scenario's write adds. */
    const val ADDED: String = "Added"

    /**
     * [iterations] times over a fresh client: a live list of two rows, then one insert the server
     * applies at once. No emission shows the new row twice, and none after it first shows lacks it.
     *
     * @param keyed whether the row carries its key, or the server makes it.
     * @param returnsRows whether the insert asks for its rows back (`select()`).
     * @return a description of each iteration that went wrong.
     */
    suspend fun anAppliedInsertIsShownOnce(
        openClient: OpenOfflineClient,
        configFor: (iteration: Int) -> Offline.Config,
        keyed: Boolean,
        returnsRows: Boolean,
        iterations: Int,
    ): List<String> = onRealThreads {
        val failures = mutableListOf<String>()
        repeat(iterations) { iteration ->
            val server = seededServer("a" to "Milk", "b" to "Eggs")
            val client = openClient(server.supabaseClient(), configFor(iteration))
            val emissions =
                appliedInsertEmissions(client, keyed, returnsRows, rowKey = "r$iteration")
            client.close()
            if (!showsAddedOnceOnceShown(emissions)) failures += "iteration $iteration: $emissions"
        }
        failures
    }

    /**
     * The emissions of a live list of two rows, from before one insert the server applies to the
     * list settling on it and then staying quiet for [quiet]. The list is open and refreshed before
     * the insert is made.
     *
     * @param rowKey the key the row carries when [keyed].
     */
    suspend fun appliedInsertEmissions(
        client: Offline,
        keyed: Boolean,
        returnsRows: Boolean,
        rowKey: String,
        beforeInsert: () -> Unit = {},
        quiet: Duration = QUIET,
    ): List<Seen> {
        // Given — a live list of the server's two rows
        val list = RecordedLiveList(client, TABLE)
        list.await { seen -> seen.titles == listOf("Milk", "Eggs") && !seen.isFromCache }
        beforeInsert()

        // When — one insert the server applies
        val row = buildJsonObject {
            if (keyed) put("id", rowKey)
            put("title", ADDED)
        }
        val outcome =
            if (returnsRows) client.from(TABLE).insert(JsonArray(listOf(row))) { select() }
            else client.from(TABLE).insert(JsonArray(listOf(row)))
        assertIs<MutationOutcome.Applied>(outcome)
        list.await { seen -> seen.added == 1 && !seen.hasPendingMutations }
        return list.stop(after = quiet)
    }

    /** Whether no emission shows the added row twice, and none after it first shows lacks it. */
    fun showsAddedOnceOnceShown(emissions: List<Seen>): Boolean {
        val firstShown = emissions.indexOfFirst { seen -> seen.added > 0 }
        return emissions.none { seen -> seen.added > 1 } &&
            emissions.drop(firstShown + 1).none { seen -> seen.added == 0 }
    }

    /**
     * [iterations] times over a fresh client: a row inserted offline is replayed by an explicit
     * sync, then a list of the table opens online and refreshes. Every emission it makes shows the
     * replayed row: before its refresh lands, from the cached payload with the replayed write laid
     * over it, and after.
     *
     * @return a description of each iteration that went wrong.
     */
    suspend fun aReplayedInsertShowsInAListOpenedAfterTheSync(
        openClient: OpenOfflineClient,
        configFor: (iteration: Int) -> Offline.Config,
        iterations: Int,
    ): List<String> = onRealThreads {
        val failures = mutableListOf<String>()
        repeat(iterations) { iteration ->
            val server = seededServer("a" to "Milk")
            val client = openClient(server.supabaseClient(), configFor(iteration))
            val emissions = replayedInsertEmissions(client, rowKey = "r$iteration")
            client.close()
            if (emissions.any { seen -> seen.titles != null && seen.added != 1 })
                failures += "iteration $iteration: $emissions"
        }
        failures
    }

    /**
     * The emissions of a list of one row opened right after a row inserted offline was replayed by
     * an explicit sync, until it settles on the server's two rows and stays quiet for [quiet].
     * [client] must not sync by itself.
     *
     * @param beforeListOpens runs after the sync, before the list opens.
     */
    suspend fun replayedInsertEmissions(
        client: Offline,
        rowKey: String,
        beforeListOpens: () -> Unit = {},
        quiet: Duration = QUIET,
    ): List<Seen> {
        // Given — the list cached online, then a row inserted offline and replayed
        RecordedLiveList(client, TABLE).run {
            await { seen -> seen.titles == listOf("Milk") && !seen.isFromCache }
            stop()
        }
        client.disableNetwork()
        val row = buildJsonObject {
            put("id", rowKey)
            put("title", ADDED)
        }
        assertIs<MutationOutcome.Queued>(client.from(TABLE).insert(JsonArray(listOf(row))))
        client.enableNetwork()
        assertEquals(1, client.syncPendingMutations().syncedCount)
        beforeListOpens()

        // When — a list of the table opens and refreshes
        val list = RecordedLiveList(client, TABLE)
        list.await { seen -> seen.added == 1 && !seen.isFromCache && seen.titles?.size == 2 }
        return list.stop(after = quiet)
    }

    /**
     * [iterations] times over a fresh client: a list whose refresh answers after a short wait. Its
     * refresh never says [RefreshState.Idle] in an emission still showing the cache it replaces:
     * nothing, cold, or the rows cached before, warm.
     *
     * @param warm whether the list's query is cached, with fewer rows than the server now holds.
     * @return a description of each iteration that went wrong.
     */
    suspend fun aRefreshIsIdleOnlyWithTheRowsItFetched(
        openClient: OpenOfflineClient,
        configFor: (iteration: Int) -> Offline.Config,
        warm: Boolean,
        iterations: Int,
    ): List<String> = onRealThreads {
        val failures = mutableListOf<String>()
        repeat(iterations) { iteration ->
            val server = seededServer("a" to "Milk")
            val client = openClient(server.supabaseClient(), configFor(iteration))
            val emissions = refreshEmissions(client, server, warm)
            client.close()
            if (!isIdleOnlyWithFetchedRows(emissions)) failures += "iteration $iteration: $emissions"
        }
        failures
    }

    /**
     * The emissions of a list that opens while the server holds a row more than [client] cached:
     * when [warm], one row; otherwise nothing. Its refresh answers after a short wait; the list
     * runs until it shows the server's rows and stays quiet for [quiet].
     *
     * @param beforeListOpens runs after the cache is warmed, before the list opens.
     */
    suspend fun refreshEmissions(
        client: Offline,
        server: FakeRelationalPostgrestServer,
        warm: Boolean,
        beforeListOpens: () -> Unit = {},
        quiet: Duration = QUIET,
    ): List<Seen> {
        // Given — the query cached with one row, when warm; the server holding two
        if (warm) {
            RecordedLiveList(client, TABLE).run {
                await { seen -> seen.titles == listOf("Milk") && !seen.isFromCache }
                stop()
            }
        }
        server.seed(TABLE, rowOf("b", "Eggs"))
        server.answerDelayMillis = REFRESH_ANSWER_DELAY_MILLIS
        beforeListOpens()

        // When — a list opens and refreshes
        val list = RecordedLiveList(client, TABLE)
        list.await { seen -> seen.titles == listOf("Milk", "Eggs") && !seen.isFromCache }
        return list.stop(after = quiet)
    }

    /** Whether every emission whose refresh is [RefreshState.Idle] shows what it fetched. */
    fun isIdleOnlyWithFetchedRows(emissions: List<Seen>): Boolean =
        emissions.none { seen -> seen.refresh == RefreshState.Idle && seen.isFromCache }

    /** Fails the calling test with [failures], or passes when there are none. */
    fun assertNone(failures: List<String>, iterations: Int) {
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of $iterations iterations went wrong; the first: ${failures.take(2)}",
        )
    }

    /** A server whose [TABLE] holds [rows], each an id and a title. */
    fun seededServer(vararg rows: Pair<String, String>): FakeRelationalPostgrestServer =
        FakeRelationalPostgrestServer().apply {
            seed(TABLE, *rows.map { (id, title) -> rowOf(id, title) }.toTypedArray())
        }

    private fun rowOf(id: String, title: String): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
    }

    /** Runs [block] on real threads, where the client's work runs, bounded well past its need. */
    suspend fun <T> onRealThreads(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.Default) { withTimeout(10.minutes) { block() } }

    /** How long the server takes to answer a refresh, so a cached emission comes first. */
    private const val REFRESH_ANSWER_DELAY_MILLIS = 50L

    /**
     * How long a list is still watched once it settled: an emission built from a source heard late
     * arrives after the one that settled it.
     */
    private val QUIET = 150.milliseconds
}

/**
 * One emission of a live list, as the scenarios compare them.
 *
 * @property titles the rows' titles, in order; `null` while nothing is known.
 */
internal data class Seen(
    val titles: List<String>?,
    val isFromCache: Boolean,
    val refresh: RefreshState,
    val hasPendingMutations: Boolean,
) {
    /** How many rows the write under test added show. */
    val added: Int
        get() = titles.orEmpty().count { title -> title == LiveReadConsistency.ADDED }

    override fun toString(): String =
        "${titles ?: "null"}${if (isFromCache) "/cache" else ""}" +
            "/${refresh::class.simpleName}${if (hasPendingMutations) "/pending" else ""}"
}

/**
 * A live list of [table] collected on real threads from the moment it is made, every emission
 * recorded, until [stop].
 */
internal class RecordedLiveList(client: Offline, table: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recorded = MutableStateFlow<List<Seen>>(emptyList())
    private val job =
        scope.launch {
            client.from(table).selectAsFlow().collect { snapshot ->
                recorded.update { seen -> seen + seenOf(snapshot) }
            }
        }

    /** How many emissions are recorded so far. */
    val count: Int
        get() = recorded.value.size

    /** Waits, bounded, until an emission matches [settled], counting from emission [from]. */
    suspend fun await(from: Int = 0, settled: (Seen) -> Boolean) {
        withTimeout(SETTLE_TIMEOUT) { recorded.first { seen -> seen.drop(from).any(settled) } }
    }

    /** Stops collecting, [after] a further wait; every emission recorded, in order. */
    suspend fun stop(after: Duration = Duration.ZERO): List<Seen> {
        if (after > Duration.ZERO) delay(after)
        job.cancelAndJoin()
        scope.cancel()
        return recorded.value
    }

    private fun seenOf(snapshot: QuerySnapshot): Seen =
        Seen(
            titles =
                snapshot.result?.data?.let { data ->
                    (Json.parseToJsonElement(data) as JsonArray).map { row ->
                        ((row as JsonObject)["title"] as? JsonPrimitive)?.contentOrNull ?: "?"
                    }
                },
            isFromCache = snapshot.isFromCache,
            refresh = snapshot.refresh,
            hasPendingMutations = snapshot.hasPendingMutations,
        )

    private companion object {
        /** How long one wait for a settled list may take on a loaded machine. */
        val SETTLE_TIMEOUT = 20.seconds
    }
}
