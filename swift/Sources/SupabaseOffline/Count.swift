// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// How PostgREST counts the rows a request matches, for ``OfflineResponse/count``; counted
/// requests go to the server under every strategy but a cache-only one, and are never cached.
public enum Count: String, Sendable, Hashable, CaseIterable {

    /// An exact `count(*)`: correct, and slow on a large table.
    case exact

    /// The planner's estimate: fast, and approximate.
    case planned

    /// Exact below the server's `max-rows`, the planner's estimate above it.
    case estimated
}
