// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Version columns and the app's conflict resolver across the bridge: a row moved on the server
 * between enqueue and replay, the resolver's one-time answer, and the conflicted list.
 */
class BridgeConflictTest {

    /** Reads the row at [BASE], misses the guarded PATCH, and finds the row moved by key. */
    private val server = RecordingServer { request ->
        when {
            request.method == "PATCH" && "eq.$BASE" in request.parameter("updated_at") ->
                ScriptedAnswer.json(200, "[]")
            request.method == "PATCH" ->
                ScriptedAnswer.json(200, """[{"id":1,"title":"Mine","updated_at":"$RESENT"}]""")
            request.parameter("limit") == "1" ->
                ScriptedAnswer.json(200, """[{"id":1,"title":"Theirs","updated_at":"$MOVED"}]""")
            request.method == "GET" ->
                ScriptedAnswer.json(200, """[{"id":1,"title":"Milk","updated_at":"$BASE"}]""")
            else -> ScriptedAnswer.json(201, "[]")
        }
    }

    @Test
    fun the_resolver_is_shown_the_conflict_and_its_answer_resends_the_write() = bridgeTest {
        // Given — a version column and a resolver that keeps the local change
        val resolver = RecordingConflictResolver { _, answer -> answer.keepLocal() }
        val client = openConflictClient(resolver)

        // When — a title queued offline replays after the row moved on the server
        val report = client.queueTitleChangeAndSync()

        // Then — the resolver saw the row as text, and the write was resent at the new version
        val conflict = resolver.conflicts.single()
        assertEquals(BridgeMutationOperation.UPDATE, conflict.operation)
        assertEquals("todos", conflict.table)
        assertEquals("""{"id":"1"}""", conflict.rowKeyJson)
        assertEquals(BASE, Json.parseToJsonElement(conflict.baseJson).field("updated_at"))
        assertEquals("""{"title":"Mine"}""", conflict.localJson)
        val serverRow = assertNotNull(conflict.serverJson)
        assertEquals("Theirs", Json.parseToJsonElement(serverRow).field("title"))
        assertEquals(listOf("title"), conflict.changedOnServer)
        assertEquals(listOf("title"), conflict.conflictingColumns)
        assertEquals(1, report.syncedCount)
        assertEquals(1, report.conflictsResolvedCount)
        assertEquals(0, report.conflictsKeptServerCount)
        assertTrue("eq.$MOVED" in server.patches().last().parameter("updated_at"))
        client.closeAndWait()
    }

    @Test
    fun only_the_first_answer_counts() = bridgeTest {
        // Given — a resolver that holds the write, then answers again
        val resolver = RecordingConflictResolver { _, answer ->
            answer.hold()
            answer.keepLocal()
            answer.keepServer()
        }
        val client = openConflictClient(resolver)

        // When
        val report = client.queueTitleChangeAndSync()

        // Then — the write is parked, never resent
        assertEquals(0, report.syncedCount)
        assertEquals(1, report.conflicted.size)
        assertEquals(1, server.patches().size)
        assertEquals(1, client.currentSyncStatus().conflictedMutationCount)
        client.closeAndWait()
    }

    @Test
    fun a_failure_holds_the_write_and_logs_the_table_only() = bridgeTest {
        assertHeldAndLoggedWithoutDetail { _, answer -> answer.fail("secret detail") }
    }

    @Test
    fun columns_that_are_not_an_object_hold_the_write_and_log_the_table_only() = bridgeTest {
        assertHeldAndLoggedWithoutDetail { _, answer -> answer.apply("[1]") }
    }

    @Test
    fun applied_columns_are_sent_as_the_body() = bridgeTest {
        // Given
        val resolver = RecordingConflictResolver { _, answer ->
            answer.apply("""{"title":"Merged"}""")
        }
        val client = openConflictClient(resolver)

        // When
        val report = client.queueTitleChangeAndSync()

        // Then
        assertEquals(1, report.conflictsResolvedCount)
        assertEquals("""{"title":"Merged"}""", server.patches().last().body)
        client.closeAndWait()
    }

    @Test
    fun the_conflicted_list_is_listed_observed_resolved_and_dismissed() = bridgeTest {
        // Given — two writes parked by a resolver that holds every conflict
        val resolver = RecordingConflictResolver { _, answer -> answer.hold() }
        val client = openConflictClient(resolver)
        val lists = SubscriptionProbe<List<BridgeConflictedMutation>>()
        client.observeConflictedMutations(lists.onValue, lists.onCompletion)
        client.queueTitleChangeAndSync()
        client.queueTitleChangeAndSync()

        // When
        val listed = CompletionProbe<List<BridgeConflictedMutation>>()
        client.conflictedMutations(listed.completion)
        val entries = assertNotNull(listed.awaitValue())

        // Then — each entry carries the write, its base and the server's row
        assertEquals(2, entries.size)
        val first = entries.first()
        assertEquals("todos", first.table)
        assertEquals("""[{"id":"1"}]""", first.rowKeysJson)
        assertEquals("""{"title":"Mine"}""", first.bodyJson)
        assertEquals(MOVED, first.serverVersion)
        assertEquals(listOf("title"), first.conflictingColumns)
        assertEquals(entries.map { it.id }, lists.awaitValue { it.size == 2 }.map { it.id })

        // When — the first is kept and the second dismissed; Hold and Apply without columns refused
        val held = awaitErrorOnly {
            client.resolveConflictedMutation(first.id, BridgeConflictDecision.HOLD, null, it)
        }
        val bare = awaitErrorOnly {
            client.resolveConflictedMutation(first.id, BridgeConflictDecision.APPLY, null, it)
        }
        assertNull(
            awaitErrorOnly {
                client.resolveConflictedMutation(
                    first.id,
                    BridgeConflictDecision.KEEP_LOCAL,
                    null,
                    it,
                )
            }
        )
        assertNull(awaitErrorOnly { client.dismissConflictedMutation(entries.last().id, it) })

        // Then — the kept write is queued again against the recorded version, the list is empty
        assertEquals(BridgeErrorKind.INVALID_ARGUMENT, held?.kind)
        assertEquals(BridgeErrorKind.INVALID_REQUEST, bare?.kind)
        lists.awaitValue { it.isEmpty() }
        val pending = CompletionProbe<List<BridgePendingMutation>>()
        client.pendingMutations(null, pending.completion)
        assertEquals(listOf(MOVED), pending.awaitValue()?.map { it.baseVersion })
        client.closeAndWait()
    }

    /** Syncs a conflict [answerWith] settles, and checks it parked the write with a bare warning. */
    private suspend fun assertHeldAndLoggedWithoutDetail(
        answerWith: (BridgeConflict, BridgeConflictAnswer) -> Unit
    ) {
        // Given
        val sink = RecordingLogSink()
        val client = openConflictClient(RecordingConflictResolver(answerWith)) { logSink = sink }

        // When
        val report = client.queueTitleChangeAndSync()

        // Then
        assertEquals(1, report.conflicted.size)
        val warning = sink.messages.single { "conflict resolver" in it }
        assertTrue(warning.startsWith("WARNING"), warning)
        assertTrue("todos" in warning, warning)
        assertTrue("secret" !in warning && "[1]" !in warning, warning)
        client.closeAndWait()
    }

    /** A client with `todos` guarded by `updated_at`, automatic replay off, and [resolver]. */
    private fun openConflictClient(
        resolver: BridgeConflictResolver,
        configure: BridgeClientOptions.() -> Unit = {},
    ): BridgeClient =
        openTestClient(server) {
            autoSync = false
            versionColumns = mapOf("todos" to "updated_at")
            conflictResolver = resolver
            configure()
        }

    /** Reads the row, queues a title change offline, and syncs it. */
    private suspend fun BridgeClient.queueTitleChangeAndSync(): BridgeSyncReport {
        selectAndWait(readOf())
        awaitErrorOnly { disableNetwork(it) }
        val (outcome, error) = mutateAndWait(UPDATE_TODO_1, """{"title":"Mine"}""")
        assertNull(error)
        assertEquals(BASE, outcome?.queued?.baseVersion)
        awaitErrorOnly { enableNetwork(it) }
        val synced = CompletionProbe<BridgeSyncReport>()
        syncPendingMutations(null, synced.completion)
        return assertNotNull(synced.awaitValue())
    }

    private fun RecordingServer.patches(): List<RecordedRequest> =
        requests.filter { it.method == "PATCH" }

    private fun RecordedRequest.parameter(name: String): String =
        queryParameters.firstOrNull { it.first == name }?.second.orEmpty()

    private fun JsonElement.field(name: String): String? =
        jsonObject[name]?.toString()?.trim('"')

    private companion object {
        const val BASE = "2026-10-18T13:00:27.486123+00:00"
        const val MOVED = "2026-10-18T13:04:00.000001+00:00"
        const val RESENT = "2026-10-18T13:05:00.000002+00:00"
        const val UPDATE_TODO_1 =
            """{"v":1,"operation":"update","table":"todos","filters":[""" +
                """{"kind":"filter","column":"id","operator":"eq","value":"1"}]}"""
    }
}
