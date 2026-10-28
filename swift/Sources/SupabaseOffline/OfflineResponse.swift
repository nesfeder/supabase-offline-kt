// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
import SupabaseOfflineCore

/// The answer to a one-shot read, an applied write or a function call: the decoded value, the body
/// it came from, and where it came from.
public struct OfflineResponse<Value: Sendable>: Sendable {

    /// The body decoded as the type asked for; `()` when none was.
    public let value: Value

    /// The body as the server sent it, or the cached rows with the user's queued writes applied:
    /// JSON for a JSON request, the text itself for ``OfflinePostgrestRequestBuilder/csv()``.
    public let data: Data

    /// The total a ``Count`` asked for; `nil` when none was asked for or sent.
    public let count: Int?

    /// Whether the rows are the device's copy rather than the server's answer to this request.
    public let isFromCache: Bool

    /// When the rows were fetched from the server, by the device's clock; `nil` for an answer
    /// straight from the server.
    public let fetchedAt: Date?

    /// Whether a queued write added, changed or removed one of the rows.
    public let hasPendingMutations: Bool

    /// Which rows a queued write added, changed or removed, as ``QuerySnapshot/pendingRowKeys`` says
    /// it for a live read: the primary key of each. A row such a write removed keeps its key here;
    /// a row without its whole key adds none. Empty for an answer straight from the server.
    public let pendingRowKeys: Set<[String: OfflineJSON]>

    package init(
        value: Value,
        data: Data,
        count: Int?,
        isFromCache: Bool,
        fetchedAt: Date?,
        hasPendingMutations: Bool,
        pendingRowKeys: Set<[String: OfflineJSON]> = []
    ) {
        self.value = value
        self.data = data
        self.count = count
        self.isFromCache = isFromCache
        self.fetchedAt = fetchedAt
        self.hasPendingMutations = hasPendingMutations
        self.pendingRowKeys = pendingRowKeys
    }
}

extension OfflineResponse {

    /// The core's `result`, its body decoded by `decode`; runs on the core's thread.
    init(core result: BridgeQueryResult, decode: (String) throws -> Value) throws {
        self.init(
            value: try decode(result.data),
            data: Data(result.data.utf8),
            count: result.count.map { Int($0.int64Value) },
            isFromCache: result.isFromCache,
            fetchedAt: result.fetchedAtEpochMillis.map { Date(epochMilliseconds: $0.int64Value) },
            hasPendingMutations: result.hasPendingMutations,
            pendingRowKeys: try RowCoding.decode(Set<[String: OfflineJSON]>.self, from: result.pendingRowKeysJson)
        )
    }
}

extension Date {

    /// The date `milliseconds` after 1970 began, as the core counts time.
    init(epochMilliseconds milliseconds: Int64) {
        self.init(timeIntervalSince1970: Double(milliseconds) / 1_000)
    }
}
