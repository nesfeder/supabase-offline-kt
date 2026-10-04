// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.storage.BucketApi

/** The [BucketApi] members [FakeBucketApi] fails that supabase-kt adds from 3.8: none on 3.6. */
internal abstract class BucketMembersSinceSupabaseKt38 : BucketApi
