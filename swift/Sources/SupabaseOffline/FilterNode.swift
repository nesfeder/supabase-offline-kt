// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// One condition of a request, in the form the core's description reads.
indirect enum FilterNode: Sendable, Hashable {

    /// `column operator value`, or `column not.operator value`.
    case condition(column: String, operator: FilterOperator, value: ConditionValue, isNegated: Bool)

    /// An `or` or `and` over `children`, negated or on a referenced table.
    case group(kind: GroupKind, children: [FilterNode], isNegated: Bool, referencedTable: String?)

    /// `like(all)`, `like(any)`, `ilike(all)` or `ilike(any)` over `patterns`.
    case patternList(kind: PatternListKind, column: String, patterns: [String])

    /// A full-text search with a configuration, such as `wfts(english).cat`.
    case textSearch(column: String, query: String, type: OfflineTextSearchType?, config: String)

    /// A condition's value, in the form its operator takes.
    enum ConditionValue: Sendable, Hashable {

        /// One value.
        case single(String)

        /// A list, for `in`.
        case list([String])

        /// A lower and an upper bound, sent `(lower,upper)`.
        case range(String, String)
    }

    /// The two logical groups.
    enum GroupKind: String, Sendable, Hashable {

        /// Any child holds.
        case or

        /// Every child holds.
        case and
    }

    /// The four pattern-list matches, by their description names.
    enum PatternListKind: String, Sendable, Hashable {

        /// Every pattern matches, case-sensitively.
        case likeAll

        /// One pattern matches, case-sensitively.
        case likeAny

        /// Every pattern matches, ignoring case.
        case ilikeAll

        /// One pattern matches, ignoring case.
        case ilikeAny
    }

    /// The column a condition, pattern list or text search filters; `nil` for a group.
    var column: String? {
        switch self {
        case .condition(let column, _, _, _), .patternList(_, let column, _), .textSearch(let column, _, _, _):
            return column
        case .group:
            return nil
        }
    }

    /// The query parameter a group is sent as, such as `or`, `not.or` or `authors.and`.
    var groupParameter: String? {
        guard case .group(let kind, _, let isNegated, let referencedTable) = self else { return nil }
        return (isNegated ? "not." : "") + (referencedTable.map { "\($0)." } ?? "") + kind.rawValue
    }

    /// Whether this is a text search, which replaces the filters before it on its column.
    var isTextSearch: Bool {
        if case .textSearch = self { return true }
        return false
    }

    /// The node as a description's filter object.
    var descriptionValue: OfflineJSON {
        switch self {
        case .condition(let column, let filterOperator, let value, let isNegated):
            var object: [String: OfflineJSON] = [
                "kind": "filter", "column": .string(column), "operator": .string(filterOperator.rawValue),
            ]
            switch value {
            case .single(let text): object["value"] = .string(text)
            case .list(let texts): object["values"] = .array(texts.map(OfflineJSON.string))
            case .range(let lower, let upper): object["range"] = [.string(lower), .string(upper)]
            }
            if isNegated { object["negated"] = true }
            return .object(object)
        case .group(let kind, let children, let isNegated, let referencedTable):
            var object: [String: OfflineJSON] = [
                "kind": .string(kind.rawValue), "filters": .array(children.map(\.descriptionValue)),
            ]
            if isNegated { object["negated"] = true }
            if let referencedTable { object["referencedTable"] = .string(referencedTable) }
            return .object(object)
        case .patternList(let kind, let column, let patterns):
            return [
                "kind": .string(kind.rawValue), "column": .string(column),
                "patterns": .array(patterns.map(OfflineJSON.string)),
            ]
        case .textSearch(let column, let query, let type, let config):
            var object: [String: OfflineJSON] = [
                "kind": "textSearch", "column": .string(column), "query": .string(query), "config": .string(config),
            ]
            if let type { object["type"] = .string(type.rawValue) }
            return .object(object)
        }
    }
}

// MARK: - What the core sends as written

/// Puts a request's top-level filters in a form the core sends exactly as written.
///
/// The core sends one filter per column and one group per parameter at the top level, as the
/// library underneath does, and refuses a list that breaks either rule. PostgREST reads several
/// conditions joined by `&` and the same conditions inside one `and` group alike, so conditions that
/// share a top-level column, and `or` groups that share a table, move into one `and` group.
enum TopLevelFilters {

    /// `nodes` with the conflicting ones moved into `and` groups, each where its first member stood;
    /// the rest keep their order.
    static func arranged(_ nodes: [FilterNode]) -> [FilterNode] {
        let columnCounts = counts(of: nodes.compactMap(\.column))
        let groupCounts = counts(of: nodes.compactMap(\.groupParameter))
        var arranged: [FilterNode] = []
        var bucketPositions: [String: Int] = [:]
        var buckets: [String: [FilterNode]] = [:]
        var bucketTables: [String: String?] = [:]
        for node in nodes {
            guard let (key, table, member) = foldedMember(node, columnCounts: columnCounts, groupCounts: groupCounts)
            else {
                arranged.append(node)
                continue
            }
            if bucketPositions[key] == nil {
                bucketPositions[key] = arranged.count
                bucketTables[key] = table
                arranged.append(node)
            }
            buckets[key, default: []].append(member)
        }
        for (key, position) in bucketPositions {
            let members = buckets[key, default: []]
            let textSearchesFirst = members.filter(\.isTextSearch) + members.filter { !$0.isTextSearch }
            arranged[position] = .group(
                kind: .and,
                children: textSearchesFirst,
                isNegated: false,
                referencedTable: bucketTables[key] ?? nil
            )
        }
        return arranged
    }

    /// The `and` group `node` moves into, by a key per table, with the table and the node as it
    /// stands inside the group; `nil` when it stays where it is.
    private static func foldedMember(
        _ node: FilterNode,
        columnCounts: [String: Int],
        groupCounts: [String: Int]
    ) -> (key: String, table: String?, member: FilterNode)? {
        if let column = node.column, columnCounts[column, default: 0] > 1 {
            return ("", nil, node)
        }
        guard case .group(.or, let children, false, let referencedTable) = node,
            let parameter = node.groupParameter, groupCounts[parameter, default: 0] > 1
        else { return nil }
        let inner = FilterNode.group(kind: .or, children: children, isNegated: false, referencedTable: nil)
        return (referencedTable.map { "\($0)." } ?? "", referencedTable, inner)
    }

    /// How many times each value occurs.
    private static func counts(of values: [String]) -> [String: Int] {
        values.reduce(into: [:]) { counts, value in counts[value, default: 0] += 1 }
    }

    /// Why the core would refuse `nodes` as a list at this level, or `nil` when it sends them all:
    /// an empty group anywhere, a text search after a filter on its column, and at the top level a
    /// second filter on one column or a second group on one parameter.
    static func refusal(of nodes: [FilterNode], isTopLevel: Bool = true) -> String? {
        var filteredColumns: Set<String> = []
        var groupParameters: Set<String> = []
        for node in nodes {
            if case .group(_, let children, _, _) = node {
                if children.isEmpty { return "A filter group is empty; leave it out" }
                if let refusal = refusal(of: children, isTopLevel: false) { return refusal }
                if isTopLevel, let parameter = node.groupParameter, !groupParameters.insert(parameter).inserted {
                    return "Two \"\(parameter)\" groups at the top level; combine them in one group"
                }
            }
            guard let column = node.column else { continue }
            if node.isTextSearch && filteredColumns.contains(column) {
                return "A text search on \"\(column)\" follows another filter on that column, which it would replace"
            }
            if !filteredColumns.insert(column).inserted && isTopLevel {
                return "Two filters on \"\(column)\" at the top level"
            }
        }
        return nil
    }
}
