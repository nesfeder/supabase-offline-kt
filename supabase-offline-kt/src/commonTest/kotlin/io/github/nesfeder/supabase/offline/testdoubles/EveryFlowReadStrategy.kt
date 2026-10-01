// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.FlowReadStrategy

/** Every [FlowReadStrategy], for tests that run one case per strategy. */
internal val everyFlowReadStrategy: List<FlowReadStrategy> =
    listOf(
        FlowReadStrategy.CacheFirstThenNetwork,
        FlowReadStrategy.NetworkFirstThenCache,
        FlowReadStrategy.CacheOnly,
    )
