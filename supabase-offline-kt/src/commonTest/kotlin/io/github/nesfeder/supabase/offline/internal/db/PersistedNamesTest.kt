// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.EnqueueOutcome
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MAX_REJECTED_MUTATIONS
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredConflict
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredRejection
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredServerError
import io.github.nesfeder.supabase.offline.internal.mutationqueue.VersionColumns
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.storage.PinnedFileRecords
import io.github.nesfeder.supabase.offline.internal.storage.QUEUED_UPLOADS_DIRECTORY_NAME
import io.github.nesfeder.supabase.offline.internal.storage.StoredUpload
import io.github.nesfeder.supabase.offline.internal.storage.UPLOAD_OPERATION_NAME
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpdate
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Every name, key and stored value the mutation queue and the cache write to disk, as exact
 * literals. Once an app has written them they are frozen: a later build reads them back from
 * devices that ran this one. A rename of a constant or a property must fail here rather than pass
 * as a refactor. The table, column and index names are pinned by the exported schema
 * (`OfflineDatabaseSchemaContinuityTest`).
 */
class PersistedNamesTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()
    private val store =
        newTestStore(
            queryCacheDao = queryCacheDao,
            mutationDao = mutationDao,
            nowMillis = { NOW },
        )

    @Test
    fun the_reserved_table_and_its_cache_keys_are_these() {
        assertEquals(
            "__supabase_offline_kt__.reserved_engine_state.this_name_is_longer_than_any_postgres_identifier",
            RESERVED_TABLE_NAME,
        )
        // Postgres truncates identifiers to NAMEDATALEN - 1 = 63 bytes, so no table has this name.
        assertTrue(RESERVED_TABLE_NAME.encodeToByteArray().size > 63)
        assertEquals("rejected_mutations", REJECTED_MUTATIONS_CACHE_KEY)
        assertEquals("conflicted_mutations", CONFLICTED_MUTATIONS_CACHE_KEY)
        assertEquals("cache_owner", CACHE_OWNER_CACHE_KEY)
    }

    @Test
    fun whose_the_cache_is_is_written_under_this_name_with_these_keys() = runTest {
        // When — the cache dropped for a user, then for nobody signed in
        store.localData.dropCacheFor(QueueIdentity.User("u-1").storedValue)
        val forUser = queryCacheDao.getRow(RESERVED_TABLE_NAME, "cache_owner")?.payload
        store.localData.dropCacheFor(QueueIdentity.Anonymous.storedValue)
        val forNobody = queryCacheDao.getRow(RESERVED_TABLE_NAME, "cache_owner")?.payload

        // Then
        assertEquals("""{"version":1,"owner":"user:u-1"}""", forUser)
        assertEquals("""{"version":1,"owner":"anonymous"}""", forNobody)
    }

    @Test
    fun a_query_shape_record_is_written_under_this_name_with_these_keys() = runTest {
        // Given / When — a network response written for a filtered query, and a key two queries
        // share
        val shape =
            QueryShape(
                select = "id,title",
                conditions = listOf(FilterDescriptor.Condition("done", "eq", "false")),
                hasOtherFilters = false,
            )
        store.queryCache.cacheResponse(
            "todos",
            "todos?done=eq.false",
            "[]",
            authoritativeEmpty = true,
            shape = shape,
        )
        store.queryCache.cacheResponse(
            "todos",
            "todos?shared",
            "[]",
            authoritativeEmpty = true,
            shape = shape,
        )
        store.queryCache.cacheResponse(
            "todos",
            "todos?shared",
            "[]",
            authoritativeEmpty = true,
            shape = shape.copy(select = "*"),
        )

        // Then
        assertEquals("query_shape", QUERY_SHAPE_CACHE_KEY_PREFIX_NAME)
        assertEquals(
            """{"version":1,"shape":{"select":"id,title","conditions":[{"column":"done","operatorId":"eq","value":"false"}],"hasOtherFilters":false}}""",
            assertNotNull(
                    queryCacheDao.getRow(
                        RESERVED_TABLE_NAME,
                        "query_shape\u001Ftodos\u001Ftodos?done=eq.false",
                    )
                )
                .payload,
        )
        assertEquals(
            """{"version":1}""",
            assertNotNull(
                    queryCacheDao.getRow(
                        RESERVED_TABLE_NAME,
                        "query_shape\u001Ftodos\u001Ftodos?shared",
                    )
                )
                .payload,
        )
    }

    @Test
    fun a_pinned_file_is_recorded_under_this_name_with_these_keys() = runTest {
        // Given / When — an object pinned, by its bucket and path
        PinnedFileRecords(queryCacheDao).pin("media", "photos/a b.png")

        // Then
        assertEquals("pinned_file", PINNED_FILE_CACHE_KEY_PREFIX_NAME)
        assertEquals(
            """{"version":1,"bucketId":"media","path":"photos/a b.png"}""",
            assertNotNull(
                    queryCacheDao.getRow(
                        RESERVED_TABLE_NAME,
                        "pinned_file\u001Fmedia::photos/a b.png",
                    )
                )
                .payload,
        )
    }

    @Test
    fun a_queued_upload_is_written_under_these_names_with_these_keys() = runTest {
        // Given / When — an upload queued with every option the queue keeps, and one with none
        val full =
            store.mutationQueue.enqueueOwnedBy("user:user-a") {
                enqueueUpload(
                    StoredUpload(
                        bucket = "photos",
                        path = "user-a/1.jpg",
                        file = "0123456789abcdef0123456789abcdef",
                        size = 5,
                        upsert = true,
                        contentType = "image/jpeg",
                        userMetadata = JsonObject(mapOf("caption" to JsonPrimitive("a cat"))),
                    )
                )
            }
        val bare =
            store.mutationQueue.enqueueOwnedBy("user:user-a") {
                enqueueUpload(
                    StoredUpload(
                        bucket = "photos",
                        path = "user-a/2.jpg",
                        file = "fedcba9876543210fedcba9876543210",
                        size = 7,
                        upsert = false,
                    )
                )
            }

        // Then
        val fullRow = assertNotNull(mutationDao.byId(assertIs<EnqueueOutcome.Queued>(full).id))
        val bareRow = assertNotNull(mutationDao.byId(assertIs<EnqueueOutcome.Queued>(bare).id))
        assertEquals(
            "__supabase_offline_kt__.storage_uploads.this_name_is_longer_than_any_postgres_identifier",
            STORAGE_UPLOADS_TABLE_NAME,
        )
        // Postgres truncates identifiers to NAMEDATALEN - 1 = 63 bytes, so no table has this name.
        assertTrue(STORAGE_UPLOADS_TABLE_NAME.encodeToByteArray().size > 63)
        assertEquals(STORAGE_UPLOADS_TABLE_NAME, fullRow.table)
        assertEquals("UPLOAD", fullRow.operation)
        assertEquals("UPLOAD", UPLOAD_OPERATION_NAME)
        assertEquals("storage-uploads", QUEUED_UPLOADS_DIRECTORY_NAME)
        assertEquals(
            """{"version":1,"bucket":"photos","path":"user-a/1.jpg",""" +
                """"file":"0123456789abcdef0123456789abcdef","size":5,"upsert":true,""" +
                """"contentType":"image/jpeg","userMetadata":{"caption":"a cat"}}""",
            fullRow.bodyJson,
        )
        assertEquals(
            """{"version":1,"bucket":"photos","path":"user-a/2.jpg",""" +
                """"file":"fedcba9876543210fedcba9876543210","size":7,"upsert":false}""",
            bareRow.bodyJson,
        )
        assertEquals(null, fullRow.filterJson)
        assertEquals("user:user-a", fullRow.owner)
    }

    @Test
    fun a_refused_upload_is_kept_on_the_rejected_list_under_the_same_names() = runTest {
        // Given — a queued upload
        val queued =
            store.mutationQueue.enqueueOwnedBy("user:user-a") {
                enqueueUpload(
                    StoredUpload(
                        bucket = "photos",
                        path = "user-a/1.jpg",
                        file = "0123456789abcdef0123456789abcdef",
                        size = 5,
                        upsert = false,
                    )
                )
            }
        val row = assertIs<EnqueueOutcome.Queued>(queued).row

        // When — Storage refuses it
        store.mutationQueue.rejectMutation(
            row,
            "refused",
            StoredServerError(status = 400, code = "Unauthorized", message = "refused"),
        )

        // Then — the entry keeps the row's table, operation and body: the list gains no key
        assertEquals(
            """{"version":1,"entries":[{"id":${row.id},"table":"$STORAGE_UPLOADS_TABLE_NAME",""" +
                """"operation":"UPLOAD","bodyJson":"{\"version\":1,\"bucket\":\"photos\",""" +
                """\"path\":\"user-a/1.jpg\",\"file\":\"0123456789abcdef0123456789abcdef\",""" +
                """\"size\":5,\"upsert\":false}","createdAtMs":$NOW,"owner":"user:user-a",""" +
                """"rejectedAtMs":$NOW,"message":"refused","serverError":{"status":400,""" +
                """"code":"Unauthorized","message":"refused"}}]}""",
            assertNotNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY))
                .payload,
        )
    }

    @Test
    fun the_stored_operation_names_are_these() {
        assertEquals(
            listOf("INSERT", "UPSERT", "UPDATE", "DELETE"),
            listOf(
                MutationOperation.INSERT.storedName,
                MutationOperation.UPSERT.storedName,
                MutationOperation.UPDATE.storedName,
                MutationOperation.DELETE.storedName,
            ),
        )
    }

    @Test
    fun a_rejected_list_is_written_with_these_keys() = runTest {
        // Given / When — an entry with every field set
        store.addRejection(
            StoredRejection(
                id = 7,
                table = "todos",
                operation = MutationOperation.UPDATE.storedName,
                bodyJson = "{}",
                filterJson = "f",
                createdAtMs = 1,
                defaultToNull = false,
                onConflict = "id",
                ignoreDuplicates = true,
                owner = "user:user-a",
                baseVersion = "v1",
                baseJson = """{"title":"a","updated_at":"v1"}""",
                rejectedAtMs = 2,
                message = "refused",
                serverError =
                    StoredServerError(
                        status = 409,
                        code = "23505",
                        message = "duplicate",
                        details = "x",
                        hint = "y",
                    ),
                bodyOmittedChars = 5,
            )
        )

        // Then
        assertEquals(
            """{"version":1,"entries":[{"id":7,"table":"todos","operation":"UPDATE","bodyJson":"{}",""" +
                """"filterJson":"f","createdAtMs":1,"defaultToNull":false,"onConflict":"id",""" +
                """"ignoreDuplicates":true,"owner":"user:user-a","baseVersion":"v1",""" +
                """"baseJson":"{\"title\":\"a\",\"updated_at\":\"v1\"}","rejectedAtMs":2,""" +
                """"message":"refused","serverError":{"status":409,"code":"23505","message":"duplicate",""" +
                """"details":"x","hint":"y"},"bodyOmittedChars":5}]}""",
            assertNotNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY))
                .payload,
        )
    }

    @Test
    fun a_conflicted_list_is_written_with_these_keys() = runTest {
        // Given — an entry with every field set
        val entry =
            StoredConflict(
                id = 7,
                table = "todos",
                operation = MutationOperation.UPDATE.storedName,
                bodyJson = """{"title":"b"}""",
                filterJson = "f",
                createdAtMs = 1,
                defaultToNull = false,
                onConflict = "id",
                ignoreDuplicates = true,
                owner = "user:user-a",
                baseVersion = "v1",
                baseJson = """{"title":"a","updated_at":"v1"}""",
                serverRowJson = """{"id":1,"title":"c","updated_at":"v2"}""",
                serverVersion = "v2",
                changedOnServer = listOf("title"),
                conflictingColumns = listOf("title"),
                message = "moved",
                conflictedAtMs = 2,
                bodyOmittedChars = 5,
            )

        // When
        store.mutationQueue.holdingQueueLock {
            val (row, list) = store.mutationQueue.conflictedList.conflictedRowWith(add = entry)
            queryCacheDao.upsert(row)
            store.mutationQueue.conflictedList.conflictWritten(list)
        }

        // Then
        assertEquals(
            """{"version":1,"entries":[{"id":7,"table":"todos","operation":"UPDATE",""" +
                """"bodyJson":"{\"title\":\"b\"}","filterJson":"f","createdAtMs":1,""" +
                """"defaultToNull":false,"onConflict":"id","ignoreDuplicates":true,""" +
                """"owner":"user:user-a","baseVersion":"v1",""" +
                """"baseJson":"{\"title\":\"a\",\"updated_at\":\"v1\"}",""" +
                """"serverRowJson":"{\"id\":1,\"title\":\"c\",\"updated_at\":\"v2\"}",""" +
                """"serverVersion":"v2","changedOnServer":["title"],"conflictingColumns":["title"],""" +
                """"message":"moved","conflictedAtMs":2,"bodyOmittedChars":5}]}""",
            assertNotNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, CONFLICTED_MUTATIONS_CACHE_KEY))
                .payload,
        )
    }

    @Test
    fun a_guarded_writes_base_is_stored_in_its_row() = runTest {
        // Given — a cached row of a table with a version column
        val versioned =
            newTestStore(
                queryCacheDao = queryCacheDao,
                mutationDao = mutationDao,
                versionColumns = VersionColumns { "updated_at" },
            )
        queryCacheDao.upsert(
            CachedQueryRow(
                table = "todos",
                cacheKey = "todos?select=*",
                payload = """[{"id":1,"title":"a","done":false,"updated_at":"v1"}]""",
                updatedAt = NOW,
            )
        )

        // When
        versioned.mutationQueue.enqueueUpdate(
            table = "todos",
            body = Json.parseToJsonElement("""{"title":"b"}"""),
            filter =
                FilterDescriptor(conditions = listOf(FilterDescriptor.Condition("id", "eq", "1"))),
        )

        // Then — the version as text, and the row's written columns with the version column
        val stored = mutationDao.inserted.single()
        assertEquals("v1", stored.baseVersion)
        assertEquals("""{"title":"a","updated_at":"v1"}""", stored.baseJson)
    }

    @Test
    fun the_count_of_dropped_rejections_is_written_under_this_key() = runTest {
        // Given / When — one more rejection than the list keeps
        repeat(MAX_REJECTED_MUTATIONS + 1) { index ->
            store.addRejection(
                StoredRejection(
                    id = index + 1L,
                    table = "t",
                    operation = "INSERT",
                    bodyJson = "[]",
                    createdAtMs = 0,
                    rejectedAtMs = 0,
                    message = "m",
                )
            )
        }

        // Then
        val payload =
            assertNotNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY))
                .payload
        assertTrue(payload.endsWith("""],"droppedCount":1}"""), payload.takeLast(40))
    }

    @Test
    fun a_mutations_owner_is_stored_as_these_values() = runTest {
        // Given / When — one write by a user, one by nobody signed in
        store.mutationQueue.enqueueOwnedBy(QueueIdentity.User("user-a").storedValue) {
            enqueueDelete(table = "todos", filter = null)
        }
        store.mutationQueue.enqueueOwnedBy(QueueIdentity.Anonymous.storedValue) {
            enqueueDelete(table = "todos", filter = null)
        }

        // Then
        assertEquals(listOf("user:user-a", "anonymous"), mutationDao.inserted.map { it.owner })
    }

    @Test
    fun a_replay_and_a_payload_that_holds_it_store_one_sequence() = runTest {
        // Given — two replayed mutations, then a network response for a cached query of their table
        repeat(2) {
            store.mutationQueue.enqueueUpdate(
                table = "todos",
                body = Json.parseToJsonElement("""{"title":"x"}"""),
                filter = null,
            )
        }
        store.mutationQueue.recordReplayed(id = 1, table = "todos")
        store.mutationQueue.recordReplayed(id = 2, table = "todos")

        // When
        store.queryCache.cacheResponse(
            "todos",
            "todos?id=eq.1",
            """[{"id":1}]""",
            requestStartedAtTick = store.freshness.tickNow(),
        )

        // Then — the replays are numbered from 1 in order, and the payload holds both
        assertEquals(listOf(1L, 2L), mutationDao.inserted.map { it.syncSequence })
        assertEquals(2L, queryCacheDao.getRow("todos", "todos?id=eq.1")?.syncedThrough)
    }

    @Test
    fun a_started_send_is_recorded_in_its_row_until_it_replays() = runTest {
        // Given — a keyed INSERT whose send is starting
        store.mutationQueue.enqueueInsert(
            table = "todos",
            body = Json.parseToJsonElement("""[{"id":"t1"}]""") as JsonArray,
        )

        // When — its send starts, and later it replays
        store.mutationQueue.markSendUnconfirmed(1)
        val started = mutationDao.inserted.single().sendUnconfirmed
        store.mutationQueue.recordReplayed(1, "todos")

        // Then — the row's `send_unconfirmed` column holds it, and no reserved row does
        assertTrue(started)
        assertFalse(mutationDao.inserted.single().sendUnconfirmed)
        assertEquals(emptyList(), queryCacheDao.allForTable(RESERVED_TABLE_NAME))
    }

    @Test
    fun an_unreadable_rejected_list_is_kept_aside_under_this_key() = runTest {
        // Given
        queryCacheDao.seedPayload(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY, "not a list")

        // When
        store.mutationQueue.rejectedList.rejections()

        // Then
        assertEquals(
            "not a list",
            queryCacheDao
                .getRow(RESERVED_TABLE_NAME, "rejected_mutations.unreadable.$NOW")
                ?.payload,
        )
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
