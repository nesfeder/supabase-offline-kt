// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// One change a filter or transform method makes to a request; only those methods make them.
public struct OfflineQueryChange: Sendable {

    /// Applies the change.
    let apply: @Sendable (inout RequestShape) -> Void

    /// Adds `node` after the filters already there.
    static func adding(_ node: FilterNode) -> OfflineQueryChange {
        OfflineQueryChange { $0.filters.append(node) }
    }

    /// Adds the node `make` builds, or, when the text it reads cannot be sent, records why, so the
    /// request fails with ``OfflineError/Code/invalidRequest`` when it is executed.
    static func adding(_ make: () throws(FilterTextError) -> FilterNode) -> OfflineQueryChange {
        do {
            return adding(try make())
        } catch {
            let message = error.message
            return OfflineQueryChange { $0.refusals.append(message) }
        }
    }
}

/// The filters a read, a write or a function call narrows its rows with: supabase-swift's filter
/// methods, under their names, each returning a new builder.
///
/// Values are written as PostgREST reads them (``OfflineFilterValue``). Two conditions on one
/// column, or two ``or(_:referencedTable:)`` groups, are sent together in one `and` group, which
/// PostgREST reads the same way. A filter written as text that cannot be sent as written fails the
/// request with ``OfflineError/Code/invalidRequest`` when it is executed, and nothing is sent.
public protocol OfflinePostgrestFilterBuilder: Sendable {

    /// This builder with `change` made to its request after the changes before it.
    func applying(_ change: OfflineQueryChange) -> Self
}

/// A builder that also shapes its response: order, limits and ranges, one object, or another format.
public protocol OfflinePostgrestRequestBuilder: OfflinePostgrestFilterBuilder {}

// MARK: - Comparisons

extension OfflinePostgrestFilterBuilder {

    /// Rows where `column` equals `value`; for `NULL`, use ``is(_:value:)``.
    public func eq(_ column: String, value: some OfflineFilterValue) -> Self {
        condition(column, .eq, value)
    }

    /// Rows where `column` does not equal `value`.
    public func neq(_ column: String, value: some OfflineFilterValue) -> Self {
        condition(column, .neq, value)
    }

    /// Rows where `column` is greater than `value`.
    public func gt(_ column: String, value: some OfflineFilterValue) -> Self {
        condition(column, .gt, value)
    }

    /// Rows where `column` is greater than or equal to `value`.
    public func gte(_ column: String, value: some OfflineFilterValue) -> Self {
        condition(column, .gte, value)
    }

    /// Rows where `column` is less than `value`.
    public func lt(_ column: String, value: some OfflineFilterValue) -> Self {
        condition(column, .lt, value)
    }

    /// Rows where `column` is less than or equal to `value`.
    public func lte(_ column: String, value: some OfflineFilterValue) -> Self {
        condition(column, .lte, value)
    }

    /// Rows where `column` `IS` `value`: `nil` for `NULL`, or `true` or `false`.
    public func `is`(_ column: String, value: Bool?) -> Self {
        applying(.adding(.condition(column: column, operator: .is, value: .single(value.filterText), isNegated: false)))
    }

    /// Rows where `column` is one of `values`.
    ///
    /// A value holding `"` or `\` and none of `,.:()` fails the request with
    /// ``OfflineError/Code/invalidRequest``: filter on such a value with ``eq(_:value:)`` instead.
    public func `in`(_ column: String, values: [any OfflineFilterValue]) -> Self {
        applying(.adding(.condition(column: column, operator: .in, value: .list(values.map(\.filterText)), isNegated: false)))
    }

    /// Rows where `column` is none of `values`; a value holding `"` or `\` fails as in
    /// ``in(_:values:)``.
    public func notIn(_ column: String, values: [any OfflineFilterValue]) -> Self {
        applying(.adding(.condition(column: column, operator: .in, value: .list(values.map(\.filterText)), isNegated: true)))
    }

    /// Rows where each column in `query` equals its value, in the columns' order.
    public func match(_ query: [String: any OfflineFilterValue]) -> Self {
        query.sorted { $0.key < $1.key }.reduce(self) { builder, entry in
            builder.applying(.adding(.condition(column: entry.key, operator: .eq, value: .single(entry.value.filterText), isNegated: false)))
        }
    }

    /// Rows where `column` `operator` `value` does not hold, sent `column=not.operator.value`.
    ///
    /// For `.in`, `value` is an array or the text `(a,b)`; for a range operator, a two-element array
    /// or `(lower,upper)`.
    public func not(_ column: String, operator filterOperator: FilterOperator, value: some OfflineFilterValue) -> Self {
        applying(.adding { () throws(FilterTextError) in try FilterValueNodes.condition(column, filterOperator, value, isNegated: true) })
    }

    /// The condition `column` `filterOperator` `value`, not negated.
    private func condition(_ column: String, _ filterOperator: FilterOperator, _ value: some OfflineFilterValue) -> Self {
        applying(.adding { () throws(FilterTextError) in try FilterValueNodes.condition(column, filterOperator, value, isNegated: false) })
    }
}

// MARK: - Patterns and text search

extension OfflinePostgrestFilterBuilder {

    /// Rows where `column` matches the `LIKE` `pattern` (`%` any text, `_` one character), case-sensitively.
    public func like(_ column: String, pattern: some OfflineFilterValue) -> Self {
        condition(column, .like, pattern)
    }

    /// Rows where `column` matches the `LIKE` `pattern`, ignoring case.
    public func ilike(_ column: String, pattern: some OfflineFilterValue) -> Self {
        condition(column, .ilike, pattern)
    }

    /// Rows where `column` matches the POSIX regular expression `pattern`, case-sensitively.
    public func match(_ column: String, pattern: some OfflineFilterValue) -> Self {
        condition(column, .match, pattern)
    }

    /// Rows where `column` matches the POSIX regular expression `pattern`, ignoring case.
    public func imatch(_ column: String, pattern: some OfflineFilterValue) -> Self {
        condition(column, .imatch, pattern)
    }

    /// Rows where `column` matches every one of the `LIKE` `patterns`, case-sensitively.
    ///
    /// An empty list fails the request with ``OfflineError/Code/invalidRequest``, and so does a
    /// pattern holding none of `,.:()` that is empty, `NULL` in any case, edged with white space or
    /// holding a brace: match such a pattern with ``like(_:pattern:)`` or ``ilike(_:pattern:)``.
    public func likeAllOf(_ column: String, patterns: [some OfflineFilterValue]) -> Self {
        patternList(.likeAll, column, patterns)
    }

    /// Rows where `column` matches at least one of the `LIKE` `patterns`, case-sensitively.
    ///
    /// The patterns are checked as in ``likeAllOf(_:patterns:)``.
    public func likeAnyOf(_ column: String, patterns: [some OfflineFilterValue]) -> Self {
        patternList(.likeAny, column, patterns)
    }

    /// Rows where `column` matches every one of the `LIKE` `patterns`, ignoring case.
    ///
    /// The patterns are checked as in ``likeAllOf(_:patterns:)``.
    public func iLikeAllOf(_ column: String, patterns: [some OfflineFilterValue]) -> Self {
        patternList(.ilikeAll, column, patterns)
    }

    /// Rows where `column` matches at least one of the `LIKE` `patterns`, ignoring case.
    ///
    /// The patterns are checked as in ``likeAllOf(_:patterns:)``.
    public func iLikeAnyOf(_ column: String, patterns: [some OfflineFilterValue]) -> Self {
        patternList(.ilikeAny, column, patterns)
    }

    /// Rows where the `text` or `tsvector` `column` matches the full-text `query`, read as `type`
    /// says (`nil`: `to_tsquery` syntax), with the text search configuration `config`.
    ///
    /// A search with a `config` replaces any filter before it on its column, so it is sent first.
    public func textSearch(
        _ column: String,
        query: some OfflineFilterValue,
        config: String? = nil,
        type: OfflineTextSearchType? = nil
    ) -> Self {
        guard let config else {
            let filterOperator = type?.filterOperator ?? .fts
            return applying(.adding(.condition(column: column, operator: filterOperator, value: .single(query.filterText), isNegated: false)))
        }
        return applying(.adding(.textSearch(column: column, query: query.filterText, type: type, config: config)))
    }

    /// Rows where `column` matches the `to_tsquery` `query`, with the configuration `config`.
    public func fts(_ column: String, query: some OfflineFilterValue, config: String? = nil) -> Self {
        textSearch(column, query: query, config: config, type: nil)
    }

    /// The pattern list `kind` on `column`.
    private func patternList(
        _ kind: FilterNode.PatternListKind,
        _ column: String,
        _ patterns: [some OfflineFilterValue]
    ) -> Self {
        applying(.adding(.patternList(kind: kind, column: column, patterns: patterns.map(\.filterText))))
    }
}

// MARK: - Arrays and ranges

extension OfflinePostgrestFilterBuilder {

    /// Rows where the array, range or `jsonb` `column` contains `value` (`@>`): an array, an
    /// ``OfflineJSON`` object, or a range written out (`[1,5)`).
    public func contains(_ column: String, value: some OfflineFilterValue) -> Self {
        condition(column, .cs, value)
    }

    /// Rows where `column` is contained by `value` (`<@`).
    public func containedBy(_ column: String, value: some OfflineFilterValue) -> Self {
        condition(column, .cd, value)
    }

    /// Rows where the array or range `column` shares an element with `value` (`&&`).
    public func overlaps(_ column: String, value: some OfflineFilterValue) -> Self {
        condition(column, .ov, value)
    }

    /// Rows where the range `column` is strictly left of `range` (`<<`).
    ///
    /// `range` is `(lower,upper)` or a two-element array: this build sends exclusive bounds only,
    /// and any other form fails the request with ``OfflineError/Code/invalidRequest``.
    public func rangeLt(_ column: String, range: some OfflineFilterValue) -> Self {
        condition(column, .sl, range)
    }

    /// Rows where the range `column` is strictly right of `range` (`>>`); `range` as in
    /// ``rangeLt(_:range:)``.
    public func rangeGt(_ column: String, range: some OfflineFilterValue) -> Self {
        condition(column, .sr, range)
    }

    /// Rows where the range `column` does not extend to the left of `range` (`&>`); `range` as in
    /// ``rangeLt(_:range:)``.
    public func rangeGte(_ column: String, range: some OfflineFilterValue) -> Self {
        condition(column, .nxl, range)
    }

    /// Rows where the range `column` does not extend to the right of `range` (`&<`); `range` as in
    /// ``rangeLt(_:range:)``.
    public func rangeLte(_ column: String, range: some OfflineFilterValue) -> Self {
        condition(column, .nxr, range)
    }

    /// Rows where the range `column` is adjacent to `range` (`-|-`); `range` as in
    /// ``rangeLt(_:range:)``.
    public func rangeAdjacent(_ column: String, range: some OfflineFilterValue) -> Self {
        condition(column, .adj, range)
    }
}

// MARK: - Written as PostgREST text

extension OfflinePostgrestFilterBuilder {

    /// Rows matching any of `filters`, PostgREST's text for an `or` group, such as
    /// `done.eq.true,priority.gt.3` or `title.eq."milk, eggs",and(a.eq.1,b.eq.2)`; on the
    /// embedded table `referencedTable` when given.
    ///
    /// The text is read here and sent as the same group; text this build cannot send fails the
    /// request with ``OfflineError/Code/invalidRequest``.
    public func or(_ filters: String, referencedTable: String? = nil) -> Self {
        applying(.adding { () throws(FilterTextError) in
            .group(
                kind: .or,
                children: try PostgrestFilterText.nodes(inLogicList: filters),
                isNegated: false,
                referencedTable: referencedTable
            )
        })
    }

    /// Rows where `column` `operator` `value` holds, both written as PostgREST text: `operator` is
    /// a name such as `eq`, optionally negated (`not.in`) or with its argument (`fts(english)`,
    /// `like(all)`), and `value` its value (`(a,b)` for `in`).
    public func filter(_ column: String, operator filterOperator: String, value: String) -> Self {
        applying(.adding { () throws(FilterTextError) in
            try PostgrestFilterText.node(column: column, operatorText: filterOperator, valueText: value)
        })
    }
}

// MARK: - Transforms

extension OfflinePostgrestRequestBuilder {

    /// Sorts the rows by `column`, or the embedded rows of `referencedTable`; later calls sort ties.
    public func order(
        _ column: String,
        ascending: Bool = true,
        nullsFirst: Bool = false,
        referencedTable: String? = nil
    ) -> Self {
        applying(OfflineQueryChange {
            $0.modifiers.append(.order(column: column, isAscending: ascending, nullsFirst: nullsFirst, referencedTable: referencedTable))
        })
    }

    /// At most `count` rows, or embedded rows of `referencedTable`.
    public func limit(_ count: Int, referencedTable: String? = nil) -> Self {
        applying(OfflineQueryChange { $0.modifiers.append(.limit(count: count, referencedTable: referencedTable)) })
    }

    /// Rows `from` through `to`, both inclusive and counted from zero.
    public func range(from: Int, to: Int, referencedTable: String? = nil) -> Self {
        applying(OfflineQueryChange { $0.modifiers.append(.range(from: from, to: to, referencedTable: referencedTable)) })
    }

    /// The one matching row as an object rather than a list; the server refuses the request when
    /// there is not exactly one. Never cached: it goes to the server under every strategy but a
    /// cache-only one.
    public func single() -> Self {
        applying(OfflineQueryChange { $0.isSingle = true })
    }

    /// The rows as comma-separated values, read from ``OfflineResponse/data``; never cached.
    public func csv() -> Self {
        applying(OfflineQueryChange { $0.format = .csv })
    }

    /// The rows as GeoJSON features, for a table with a geometry column; never cached.
    public func geojson() -> Self {
        applying(OfflineQueryChange { $0.format = .geojson })
    }

    /// The rows without their `null` fields; never cached.
    public func stripNulls() -> Self {
        applying(OfflineQueryChange { $0.stripsNulls = true })
    }
}

/// The nodes the typed filter methods make of their values.
enum FilterValueNodes {

    /// The condition `column` `filterOperator` `value`, the value read in the form the operator
    /// takes: an array or `(a,b)` for a list, a two-element array or `(lower,upper)` for a range.
    static func condition(
        _ column: String,
        _ filterOperator: FilterOperator,
        _ value: some OfflineFilterValue,
        isNegated: Bool
    ) throws(FilterTextError) -> FilterNode {
        do {
            return try conditionNode(column, filterOperator, value, isNegated: isNegated)
        } catch {
            throw error.on(column: column)
        }
    }

    /// ``condition(_:_:_:isNegated:)`` without the column in its error.
    private static func conditionNode(
        _ column: String,
        _ filterOperator: FilterOperator,
        _ value: some OfflineFilterValue,
        isNegated: Bool
    ) throws(FilterTextError) -> FilterNode {
        let elements = (value as? any FilterValueList)?.elementTexts
        let conditionValue: FilterNode.ConditionValue
        switch filterOperator.valueForm {
        case .list:
            if let elements {
                conditionValue = .list(elements)
            } else {
                conditionValue = .list(try PostgrestFilterText.listElements(of: value.filterText, opening: "(", closing: ")"))
            }
        case .range:
            if let elements, elements.count == 2 {
                conditionValue = .range(elements[0], elements[1])
            } else {
                conditionValue = try PostgrestFilterText.range(of: value.filterText)
            }
        case .literal, .single:
            conditionValue = .single(value.filterText)
        }
        return .condition(column: column, operator: filterOperator, value: conditionValue, isNegated: isNegated)
    }
}
