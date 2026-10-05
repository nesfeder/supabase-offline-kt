// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// A filter written as PostgREST text that cannot be sent as written; its message says why.
struct FilterTextError: Error, Sendable, Equatable {

    /// What is wrong and where: what was expected, a column the caller named, a character
    /// position; never the caller's text.
    let message: String

    /// This error with `column` named first, for a value read for that column.
    func on(column: String) -> FilterTextError {
        FilterTextError(message: "On \"\(column)\": \(message)")
    }
}

/// Reads filters written as PostgREST text into the nodes a description carries: one condition's
/// operator and value (`not.in` and `(a,b)`), or a logic list such as `done.eq.true,priority.gt.3`.
///
/// Inside a logic list a value may be double-quoted (`title.eq."milk, eggs"`), with `\"` and `\\`
/// escapes, as PostgREST reads it; a value standing alone is taken as written.
enum PostgrestFilterText {

    /// The node for one condition: `operatorText` is an operator (`eq`, `fts`), optionally negated
    /// (`not.eq`) or with its argument (`fts(english)`, `like(all)`), and `valueText` what follows it.
    static func node(column: String, operatorText: String, valueText: String) throws(FilterTextError) -> FilterNode {
        var reader = FilterTextReader(operatorText)
        let parsed = try reader.readOperator()
        guard reader.isAtEnd else { throw FilterTextError(message: "The operator text is not a filter operator") }
        do {
            return try node(column: column, operator: parsed, valueText: valueText, isInLogicList: false)
        } catch {
            throw error.on(column: column)
        }
    }

    /// The conditions and nested groups of a logic list: the text inside `or=( … )`.
    static func nodes(inLogicList text: String) throws(FilterTextError) -> [FilterNode] {
        var reader = FilterTextReader(text)
        let nodes = try reader.readLogicList(closedBy: nil)
        guard reader.isAtEnd else { throw reader.unexpected("the end of the list") }
        return nodes
    }

    /// An operator as written: its name, whether `not.` negated it, and its argument in parentheses.
    struct WrittenOperator {

        /// The name, such as `eq` or `like`.
        let name: String

        /// Whether `not.` came first.
        let isNegated: Bool

        /// What stood in parentheses after the name, such as `english` or `all`.
        let argument: String?
    }

    /// The node `written` makes of `valueText`; inside a logic list a quoted value is unquoted.
    static func node(
        column: String,
        operator written: WrittenOperator,
        valueText: String,
        isInLogicList: Bool
    ) throws(FilterTextError) -> FilterNode {
        let textSearchTypes: [String: OfflineTextSearchType?] = [
            "fts": nil, "plfts": .plain, "phfts": .phrase, "wfts": .websearch,
        ]
        if let argument = written.argument {
            if let type = textSearchTypes[written.name] {
                guard !written.isNegated else {
                    throw FilterTextError(message: "A negated text search cannot name a configuration")
                }
                return .textSearch(column: column, query: valueText, type: type, config: argument)
            }
            return try patternList(column: column, operator: written, patternsText: valueText)
        }
        guard let filterOperator = FilterOperator(rawValue: written.name) else {
            throw FilterTextError(message: "The operator is not a filter operator this build sends")
        }
        let value: FilterNode.ConditionValue
        switch filterOperator.valueForm {
        case .list: value = .list(try listElements(of: valueText, opening: "(", closing: ")"))
        case .range: value = try range(of: valueText)
        case .literal: value = .single(valueText)
        case .single: value = .single(valueText)
        }
        return .condition(column: column, operator: filterOperator, value: value, isNegated: written.isNegated)
    }

    /// `like(all)`, `like(any)`, `ilike(all)` or `ilike(any)` over a `{a,b}` list.
    private static func patternList(
        column: String,
        operator written: WrittenOperator,
        patternsText: String
    ) throws(FilterTextError) -> FilterNode {
        let kinds: [String: FilterNode.PatternListKind] = [
            "like(all)": .likeAll, "like(any)": .likeAny, "ilike(all)": .ilikeAll, "ilike(any)": .ilikeAny,
        ]
        guard let kind = kinds["\(written.name)(\(written.argument ?? ""))"] else {
            throw FilterTextError(message: "The operator and its argument are not a filter operator this build sends")
        }
        guard !written.isNegated else {
            throw FilterTextError(message: "A pattern list cannot be negated")
        }
        return .patternList(kind: kind, column: column, patterns: try listElements(of: patternsText, opening: "{", closing: "}"))
    }

    /// The bounds of `(lower,upper)`, the one range form the core sends.
    static func range(of text: String) throws(FilterTextError) -> FilterNode.ConditionValue {
        let refusal = FilterTextError(
            message: "A range is written (lower,upper), with exclusive bounds, the one form this build sends"
        )
        guard let bounds = try? listElements(of: text, opening: "(", closing: ")"), bounds.count == 2 else {
            throw refusal
        }
        return .range(bounds[0], bounds[1])
    }

    /// The elements of `text`, a list between `opening` and `closing` separated by commas, each
    /// plain or double-quoted. In a `{…}` array literal a `\` in a plain element escapes the next
    /// character, as Postgres reads it; in a `(…)` list it is kept, as PostgREST reads it.
    static func listElements(of text: String, opening: Character, closing: Character) throws(FilterTextError) -> [String] {
        var reader = FilterTextReader(text, subject: "the value")
        guard reader.read(opening) else {
            throw reader.unexpected("a list in \(opening)\(closing)")
        }
        var elements: [String] = []
        if !reader.read(closing) {
            repeat {
                elements.append(
                    opening == "{"
                        ? try reader.readArrayElement(closing: closing) : try reader.readValue(stoppingAt: [",", closing])
                )
            } while reader.read(",")
            guard reader.read(closing) else { throw reader.unexpected("\"\(closing)\"") }
        }
        guard reader.isAtEnd else { throw reader.unexpected("the end of the list") }
        return elements
    }
}

/// Reads PostgREST filter text left to right; every `read` consumes only what it recognizes.
struct FilterTextReader {

    /// The text's characters.
    private let characters: [Character]

    /// The index of the next character.
    private var position = 0

    /// What the text is, in an error: `the filter text`, or `the value` of one condition.
    private let subject: String

    init(_ text: String, subject: String = "the filter text") {
        characters = Array(text)
        self.subject = subject
    }

    /// Whether every character has been read.
    var isAtEnd: Bool { position == characters.count }

    /// Consumes `character` when it is next.
    mutating func read(_ character: Character) -> Bool {
        guard position < characters.count, characters[position] == character else { return false }
        position += 1
        return true
    }

    /// Consumes `text` when it comes next.
    mutating func read(_ text: String) -> Bool {
        let expected = Array(text)
        guard characters.count - position >= expected.count,
            Array(characters[position..<position + expected.count]) == expected
        else { return false }
        position += expected.count
        return true
    }

    /// An error naming what was expected and how far into the text, never quoting the text.
    func unexpected(_ expected: String) -> FilterTextError {
        let place = isAtEnd ? "at the end of \(subject)" : "at character \(position + 1) of \(subject)"
        return FilterTextError(message: "Expected \(expected) \(place)")
    }

    /// `[not.]name[(argument)]`, stopping before the `.` that leads to the value.
    mutating func readOperator() throws(FilterTextError) -> PostgrestFilterText.WrittenOperator {
        let isNegated = read("not.")
        var name = ""
        while position < characters.count, characters[position].isLetter {
            name.append(characters[position])
            position += 1
        }
        guard !name.isEmpty else { throw unexpected("an operator") }
        var argument: String?
        if read("(") {
            var text = ""
            while position < characters.count, characters[position] != ")" {
                text.append(characters[position])
                position += 1
            }
            guard read(")") else { throw unexpected("\")\"") }
            argument = text
        }
        return PostgrestFilterText.WrittenOperator(name: name, isNegated: isNegated, argument: argument)
    }

    /// The items of a logic list up to `closing` (not consumed), or to the end when `nil`.
    mutating func readLogicList(closedBy closing: Character?) throws(FilterTextError) -> [FilterNode] {
        var nodes: [FilterNode] = []
        repeat {
            nodes.append(try readLogicItem())
        } while read(",")
        if let closing, !(position < characters.count && characters[position] == closing) {
            throw unexpected("\"\(closing)\"")
        }
        return nodes
    }

    /// One item: a nested `and( … )` / `or( … )`, optionally `not.`, or `column.operator.value`.
    private mutating func readLogicItem() throws(FilterTextError) -> FilterNode {
        let start = position
        let isNegated = read("not.")
        for kind in [FilterNode.GroupKind.and, .or] where read("\(kind.rawValue)(") {
            let children = try readLogicList(closedBy: ")")
            _ = read(")")
            return .group(kind: kind, children: children, isNegated: isNegated, referencedTable: nil)
        }
        position = start
        let column = try readColumn()
        guard read(".") else { throw unexpected("\".\" after a column") }
        let written = try readOperator()
        guard read(".") else { throw unexpected("\".\" after an operator") }
        let valueText = try readLogicValue(for: written)
        return try PostgrestFilterText.node(column: column, operator: written, valueText: valueText, isInLogicList: true)
    }

    /// A column name: double-quoted, or up to the next `.`.
    private mutating func readColumn() throws(FilterTextError) -> String {
        if position < characters.count, characters[position] == "\"" {
            return try readQuoted()
        }
        var name = ""
        while position < characters.count, !".,()".contains(characters[position]) {
            name.append(characters[position])
            position += 1
        }
        guard !name.isEmpty else { throw unexpected("a column") }
        return name
    }

    /// A value inside a logic list: a bracketed list or range kept whole for the operator to read,
    /// a quoted value unquoted, or plain text up to the next `,` or `)`.
    private mutating func readLogicValue(for written: PostgrestFilterText.WrittenOperator) throws(FilterTextError) -> String {
        guard position < characters.count else { return "" }
        let closings: [Character: Character] = ["(": ")", "[": "]", "{": "}"]
        if let closing = closings[characters[position]], Self.takesBracketedValue(written) {
            return readBracketed(closing: closing)
        }
        return try readValue(stoppingAt: [",", ")"])
    }

    /// Whether `written` takes a list, a range or a literal array, which holds commas of its own.
    private static func takesBracketedValue(_ written: PostgrestFilterText.WrittenOperator) -> Bool {
        if written.argument != nil { return written.name == "like" || written.name == "ilike" }
        guard let filterOperator = FilterOperator(rawValue: written.name) else { return false }
        return filterOperator.valueForm != .single
    }

    /// Text from an opening bracket through `closing`, quoted parts included whole.
    private mutating func readBracketed(closing: Character) -> String {
        var text = ""
        var isInQuotes = false
        while position < characters.count {
            let character = characters[position]
            text.append(character)
            position += 1
            if character == "\\", isInQuotes, position < characters.count {
                text.append(characters[position])
                position += 1
            } else if character == "\"" {
                isInQuotes.toggle()
            } else if character == closing, !isInQuotes {
                break
            }
        }
        return text
    }

    /// A double-quoted value unquoted, or plain text up to one of `stops` (not consumed).
    mutating func readValue(stoppingAt stops: Set<Character>) throws(FilterTextError) -> String {
        if position < characters.count, characters[position] == "\"" {
            return try readQuoted()
        }
        var text = ""
        while position < characters.count, !stops.contains(characters[position]) {
            text.append(characters[position])
            position += 1
        }
        return text
    }

    /// One element of a Postgres array literal: double-quoted, or plain up to a `,` or `closing`
    /// (not consumed), a `\` taking the character after it as written.
    mutating func readArrayElement(closing: Character) throws(FilterTextError) -> String {
        if position < characters.count, characters[position] == "\"" {
            return try readQuoted()
        }
        var text = ""
        while position < characters.count, characters[position] != ",", characters[position] != closing {
            if characters[position] == "\\" {
                position += 1
                guard position < characters.count else { throw FilterTextError(message: "A list ends in an unfinished escape") }
            }
            text.append(characters[position])
            position += 1
        }
        return text
    }

    /// A double-quoted string, with `\"` and `\\` unescaped.
    private mutating func readQuoted() throws(FilterTextError) -> String {
        _ = read("\"")
        var text = ""
        while position < characters.count {
            let character = characters[position]
            position += 1
            if character == "\"" { return text }
            if character == "\\", position < characters.count {
                text.append(characters[position])
                position += 1
            } else {
                text.append(character)
            }
        }
        throw FilterTextError(message: "A quoted value is not closed")
    }
}
