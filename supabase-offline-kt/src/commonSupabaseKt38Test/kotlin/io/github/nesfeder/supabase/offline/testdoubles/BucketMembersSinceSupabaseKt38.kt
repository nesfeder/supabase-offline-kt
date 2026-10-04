// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.PurgeCacheOptions

/**
 * The [BucketApi] members supabase-kt adds from 3.8, which [FakeBucketApi] fails as it fails
 * others.
 */
internal abstract class BucketMembersSinceSupabaseKt38 : BucketApi {

    override suspend fun purgeCache(path: String, options: PurgeCacheOptions.() -> Unit): Unit =
        error("FakeBucketApi.purgeCache must not be called in this test")
}
