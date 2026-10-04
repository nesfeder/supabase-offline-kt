// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// Where a live read takes its first rows from; every later local write, sync and server record
/// then updates it from the cache.
public enum SnapshotStrategy: Sendable, Hashable, CaseIterable {

    /// Shows the cached rows at once and refreshes them from the server in parallel.
    ///
    /// A failing refresh leaves the rows in place, is reported in ``QuerySnapshot/refresh``, and is
    /// retried while the sequence is iterated: at most a minute apart, and at once when the network
    /// comes back.
    case cacheFirstThenNetwork

    /// Asks the server first, then shows the cached rows as they change.
    ///
    /// Offline with the rows cached, it shows them at once and keeps asking; with nothing cached,
    /// or on a server error, the sequence throws.
    case networkFirstThenCache

    /// Shows the cached rows and never sends a request; a query the cache never holds throws
    /// ``OfflineError/Code/cacheMiss``.
    case cacheOnly

    /// The strategy as a live read description's `strategy` object.
    var descriptionValue: OfflineJSON {
        switch self {
        case .cacheFirstThenNetwork: return ["kind": "cacheFirstThenNetwork"]
        case .networkFirstThenCache: return ["kind": "networkFirstThenCache"]
        case .cacheOnly: return ["kind": "cacheOnly"]
        }
    }
}
