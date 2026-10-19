// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.CachedFileDao
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import kotlinx.serialization.json.Json

/**
 * Whose the query cache and the Storage file cache are, and whether the current identity may read
 * or write them; asked on every cache read and write. Its only lock is the owner record's, taken
 * last and held over no other.
 *
 * @param isCacheOwnedPerIdentity whether the caches belong to one identity at a time: with the Auth
 *   plugin. Without it there is one identity, which owns them for good.
 * @param identityProvider the current identity's stored value, or `null` when it cannot be told.
 * @param onCacheNotServed told when the caches were not served to, or written for, the current
 *   identity, so a settled session drops them for it.
 */
internal class CacheOwnership(
    private val queryCacheDao: QueryCacheDao,
    private val cachedFileDao: CachedFileDao,
    json: Json,
    private val isCacheOwnedPerIdentity: Boolean,
    private val identityProvider: suspend () -> String?,
    private val onCacheNotServed: () -> Unit,
) {

    /** The persisted owner; see [CacheOwnerRecord]. */
    val record: CacheOwnerRecord = CacheOwnerRecord(queryCacheDao, json)

    /**
     * Whether the cache may be read for the current identity: it is that identity's, or no
     * identity's yet and the identity is a user, who adopts it. Without the Auth plugin there is
     * one identity, and it always may.
     */
    suspend fun cacheServesCurrentIdentity(): Boolean =
        cacheIsIdentitys(identityProvider, forWrite = false)

    /** [cacheServesCurrentIdentity] for [identity], already read; `null` is no identity. */
    suspend fun cacheServes(identity: String?): Boolean =
        cacheIsIdentitys({ identity }, forWrite = false)

    /**
     * Whether the cache may be written for the current identity, as [cacheServesCurrentIdentity]
     * says; a cache no identity owns is first recorded as that identity's, so what is written into
     * it is never adopted by another.
     */
    suspend fun cacheTakesWritesOfCurrentIdentity(): Boolean =
        cacheIsIdentitys(identityProvider, forWrite = true)

    /**
     * [cacheServesCurrentIdentity] for the identity [identityOf] reads, only when it is needed, or
     * with [forWrite] [cacheTakesWritesOfCurrentIdentity].
     */
    private suspend fun cacheIsIdentitys(
        identityOf: suspend () -> String?,
        forWrite: Boolean,
    ): Boolean {
        if (!isCacheOwnedPerIdentity) {
            if (forWrite) identityOf()?.let { claimant -> record.owner(claimant = claimant) }
            return true
        }
        val identity = identityOf()
        val owner = identity?.let { cacheOwnerClaimingFor(it, claimsForUser = forWrite) }
        val mine =
            identity != null &&
                (owner == identity ||
                    (owner == null && identity != QueueIdentity.Anonymous.storedValue))
        if (!mine) onCacheNotServed()
        return mine
    }

    /**
     * The cache's recorded owner, recording [identity] first when none is and it may claim the
     * cache: a user when [claimsForUser]; nobody signed in only while the cache holds nothing,
     * since rows an earlier build left may be a user's.
     */
    suspend fun cacheOwnerClaimingFor(identity: String, claimsForUser: Boolean): String? =
        record.owner(
            claimant = identity,
            mayClaim = {
                if (identity != QueueIdentity.Anonymous.storedValue) claimsForUser
                else queryCacheDao.totalPayloadBytes() == 0L && cachedFileDao.all().isEmpty()
            },
        )
}
