package io.github.nesfeder.supabase.internal

import io.github.nesfeder.supabase.internal.db.MutationOperation
import io.github.nesfeder.supabase.internal.db.MutationRow
import io.github.nesfeder.supabase.internal.db.ObjectRow
import io.github.nesfeder.supabase.internal.db.StorageRow

internal fun fakeObjectRow(
    table: String = "assets",
    signature: String = "assets?deleted=eq.false",
    payload: String = "[]",
    updatedAt: Long = 0L,
): ObjectRow = ObjectRow(
    table = table,
    signature = signature,
    payload = payload,
    updatedAt = updatedAt,
)

internal fun fakeStorageRow(
    key: String = "bucket::path.txt",
    bucketId: String = "bucket",
    path: String = "path.txt",
    localPath: String = "/tmp/path.txt",
    sizeBytes: Long? = 123L,
    eTag: String? = "etag",
    lastFetchedEpochMs: Long = 0L,
): StorageRow = StorageRow(
    key = key,
    bucketId = bucketId,
    path = path,
    localPath = localPath,
    sizeBytes = sizeBytes,
    eTag = eTag,
    lastFetchedEpochMs = lastFetchedEpochMs,
)

internal fun fakeMutationRow(
    id: Long = 0L,
    table: String = "assets",
    operation: String = MutationOperation.INSERT,
    bodyJson: String = "[]",
    filterJson: String? = null,
    createdAtMs: Long = 0L,
    syncedAtMs: Long? = null,
    defaultToNull: Boolean? = true,
    onConflict: String? = null,
    ignoreDuplicates: Boolean? = null,
): MutationRow = MutationRow(
    id = id,
    table = table,
    operation = operation,
    bodyJson = bodyJson,
    filterJson = filterJson,
    createdAtMs = createdAtMs,
    syncedAtMs = syncedAtMs,
    defaultToNull = defaultToNull,
    onConflict = onConflict,
    ignoreDuplicates = ignoreDuplicates,
)
