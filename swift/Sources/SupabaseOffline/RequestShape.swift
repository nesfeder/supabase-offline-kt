// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// What a read, a write or a function call adds to its request: filters, modifiers, a count and the
/// response's shape, kept in call order and written into the core's description when it is sent.
struct RequestShape: Sendable {

    /// The filters, in call order, before ``TopLevelFilters`` arranges them.
    var filters: [FilterNode] = []

    /// `order`, `limit` and `range`, in call order.
    var modifiers: [Modifier] = []

    /// How to count the matching rows, if at all.
    var count: Count?

    /// Whether the response is one object rather than a list.
    var isSingle = false

    /// The body the request asks for.
    var format: ResponseFormat = .json

    /// Whether null fields are left out of the response.
    var stripsNulls = false

    /// Why a filter written as text cannot be sent, in call order; the first fails the request.
    var refusals: [String] = []

    /// One of `order`, `limit` and `range`, on the main table or a referenced one.
    enum Modifier: Sendable, Hashable {

        /// Sorts by `column`.
        case order(column: String, isAscending: Bool, nullsFirst: Bool, referencedTable: String?)

        /// At most `count` rows.
        case limit(count: Int, referencedTable: String?)

        /// Rows `from` through `to`, both inclusive.
        case range(from: Int, to: Int, referencedTable: String?)

        /// The modifier as a description's object.
        var descriptionValue: OfflineJSON {
            var object: [String: OfflineJSON]
            let referencedTable: String?
            switch self {
            case .order(let column, let isAscending, let nullsFirst, let table):
                object = ["kind": "order", "column": .string(column), "ascending": .bool(isAscending)]
                if nullsFirst { object["nullsFirst"] = true }
                referencedTable = table
            case .limit(let count, let table):
                object = ["kind": "limit", "count": .int(count)]
                referencedTable = table
            case .range(let from, let to, let table):
                object = ["kind": "range", "from": .int(from), "to": .int(to)]
                referencedTable = table
            }
            if let referencedTable { object["referencedTable"] = .string(referencedTable) }
            return .object(object)
        }
    }

    /// The body a request asks for.
    enum ResponseFormat: String, Sendable, Hashable {

        /// JSON rows.
        case json

        /// Comma-separated values.
        case csv

        /// GeoJSON features.
        case geojson
    }

    /// The description fields this shape adds, the filters arranged so the core sends them as
    /// written.
    ///
    /// - Throws: ``OfflineError/Code/invalidRequest`` for a filter that cannot be sent as written.
    func descriptionFields() throws -> [String: OfflineJSON] {
        if let refusal = refusals.first {
            throw OfflineError(code: .invalidRequest, message: refusal)
        }
        let arranged = TopLevelFilters.arranged(filters)
        if let refusal = TopLevelFilters.refusal(of: arranged) {
            throw OfflineError(code: .invalidRequest, message: refusal)
        }
        var fields: [String: OfflineJSON] = [:]
        if !arranged.isEmpty { fields["filters"] = .array(arranged.map(\.descriptionValue)) }
        if !modifiers.isEmpty { fields["modifiers"] = .array(modifiers.map(\.descriptionValue)) }
        if let count { fields["count"] = .string(count.rawValue) }
        if isSingle { fields["single"] = true }
        if format != .json { fields["format"] = .string(format.rawValue) }
        if stripsNulls { fields["stripNulls"] = true }
        return fields
    }

    /// `fields` as a version-1 description's JSON text.
    static func descriptionText(_ fields: [String: OfflineJSON]) throws -> String {
        try RowCoding.jsonText(for: OfflineJSON.object(fields.merging(["v": 1]) { given, _ in given }))
    }
}
