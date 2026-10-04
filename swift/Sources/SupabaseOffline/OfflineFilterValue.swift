// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation

/// A value a filter compares a column with, as the text PostgREST reads.
///
/// Strings, numbers, booleans, `UUID`, `Date`, `Decimal`, optionals, arrays and ``OfflineJSON``
/// conform; so does an enum with a raw value that conforms, once it declares the conformance. A
/// `UUID` is written in lower case and a `Date` in UTC to the microsecond, so equal values always
/// make the same request and share a cached result.
public protocol OfflineFilterValue: Sendable {

    /// The value as PostgREST reads it, such as `true`, `42` or `2026-10-07T09:30:00Z`.
    var filterText: String { get }
}

extension OfflineFilterValue where Self: RawRepresentable, RawValue: OfflineFilterValue {

    /// The raw value's text.
    public var filterText: String { rawValue.filterText }
}

extension String: OfflineFilterValue {

    /// The string itself.
    public var filterText: String { self }
}

extension Bool: OfflineFilterValue {

    /// `true` or `false`.
    public var filterText: String { self ? "true" : "false" }
}

extension Int: OfflineFilterValue {

    /// The number in decimal.
    public var filterText: String { String(self) }
}

extension Int8: OfflineFilterValue {

    /// The number in decimal.
    public var filterText: String { String(self) }
}

extension Int16: OfflineFilterValue {

    /// The number in decimal.
    public var filterText: String { String(self) }
}

extension Int32: OfflineFilterValue {

    /// The number in decimal.
    public var filterText: String { String(self) }
}

extension Int64: OfflineFilterValue {

    /// The number in decimal.
    public var filterText: String { String(self) }
}

extension UInt: OfflineFilterValue {

    /// The number in decimal.
    public var filterText: String { String(self) }
}

extension UInt8: OfflineFilterValue {

    /// The number in decimal.
    public var filterText: String { String(self) }
}

extension UInt16: OfflineFilterValue {

    /// The number in decimal.
    public var filterText: String { String(self) }
}

extension UInt32: OfflineFilterValue {

    /// The number in decimal.
    public var filterText: String { String(self) }
}

extension UInt64: OfflineFilterValue {

    /// The number in decimal.
    public var filterText: String { String(self) }
}

extension Double: OfflineFilterValue {

    /// The shortest decimal that reads back as this number, such as `9.5`, or `1.0E-7` outside
    /// 0.001 to 10 million; `NaN`, `Infinity` or `-Infinity` for the special values.
    public var filterText: String { FilterNumberText.text(forFloatingPoint: description) }
}

extension Float: OfflineFilterValue {

    /// The shortest decimal that reads back as this number, written as a `Double` is.
    public var filterText: String { FilterNumberText.text(forFloatingPoint: description) }
}

extension Decimal: OfflineFilterValue {

    /// The number in plain decimal, every digit kept.
    public var filterText: String { description }
}

extension UUID: OfflineFilterValue {

    /// The UUID in lower case, as Postgres writes one.
    public var filterText: String { uuidString.lowercased() }
}

extension Date: OfflineFilterValue {

    /// The date in UTC, ISO 8601, to the microsecond: `2026-10-07T09:30:00Z`, `…:00.250Z` or
    /// `…:00.250125Z`.
    public var filterText: String { PostgresTimestamp.text(for: self) }
}

extension Optional: OfflineFilterValue where Wrapped: OfflineFilterValue {

    /// The wrapped value's text, or `null`.
    public var filterText: String { map(\.filterText) ?? "null" }
}

extension Array: OfflineFilterValue where Element: OfflineFilterValue {

    /// A Postgres array literal, such as `{work,"at home"}`: elements quoted where Postgres needs
    /// it, `NULL` for a `nil` one, nested arrays as nested literals.
    public var filterText: String { PostgresArrayLiteral.text(of: map { $0 }) }
}

extension OfflineJSON: OfflineFilterValue {

    /// A string as itself, a number or boolean as written, `null`, an array as a Postgres array
    /// literal and an object as JSON text with its keys sorted (for a `jsonb` containment).
    public var filterText: String {
        switch self {
        case .null: return "null"
        case .bool(let value): return value.filterText
        case .int(let value): return value.filterText
        case .double(let value): return value.filterText
        case .string(let value): return value
        case .array(let elements): return PostgresArrayLiteral.text(of: elements)
        case .object: return sortedJSONText
        }
    }
}

extension OfflineJSON {

    /// The value as JSON text with every object's keys sorted, so equal objects filter alike.
    var sortedJSONText: String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        // Every case but a non-finite double encodes; that one falls back to `null`.
        guard let data = try? encoder.encode(self) else { return "null" }
        return String(decoding: data, as: UTF8.self)
    }
}

// MARK: - Lists of values

/// A value that stands for several: an array, whose elements a list filter (`in`) takes one by one.
protocol FilterValueList {

    /// Each element's text, in order.
    var elementTexts: [String] { get }
}

extension Array: FilterValueList where Element: OfflineFilterValue {

    /// Each element's ``OfflineFilterValue/filterText``.
    var elementTexts: [String] { map(\.filterText) }
}

/// An element of a Postgres array literal that writes itself rather than being quoted: a nested
/// array, or a missing value.
protocol PostgresArrayElement {

    /// The element as it stands inside the literal's braces.
    var arrayElementText: String { get }
}

extension Array: PostgresArrayElement where Element: OfflineFilterValue {

    /// The nested literal.
    var arrayElementText: String { filterText }
}

extension Optional: PostgresArrayElement where Wrapped: OfflineFilterValue {

    /// `NULL`, or the wrapped value as any other element.
    var arrayElementText: String {
        guard let value = self else { return "NULL" }
        return PostgresArrayLiteral.elementText(for: value)
    }
}

extension OfflineJSON: PostgresArrayElement {

    /// `NULL` for `null`, a nested literal for an array, any other value quoted as needed.
    var arrayElementText: String {
        switch self {
        case .null: return "NULL"
        case .array: return filterText
        default: return PostgresArrayLiteral.quotedIfNeeded(filterText)
        }
    }
}

/// Postgres array literals: `{a,"b c",NULL}`.
enum PostgresArrayLiteral {

    /// The literal holding `elements`.
    static func text(of elements: [any OfflineFilterValue]) -> String {
        "{" + elements.map(elementText(for:)).joined(separator: ",") + "}"
    }

    /// One element inside the braces.
    static func elementText(for element: any OfflineFilterValue) -> String {
        if let element = element as? any PostgresArrayElement { return element.arrayElementText }
        return quotedIfNeeded(element.filterText)
    }

    /// `text` in double quotes, with `"` and `\` escaped, when Postgres would otherwise read it
    /// differently: empty, `NULL`, or holding a brace, comma, quote, backslash or white space.
    static func quotedIfNeeded(_ text: String) -> String {
        let needsQuotes =
            text.isEmpty || text.caseInsensitiveCompare("NULL") == .orderedSame
            || text.contains(where: { "{},\"\\".contains($0) || $0.isWhitespace })
        guard needsQuotes else { return text }
        let escaped = text.replacing("\\", with: "\\\\").replacing("\"", with: "\\\"")
        return "\"\(escaped)\""
    }
}

// MARK: - Numbers

/// Floating-point numbers as the Android library writes them, so a filter value reads the same on
/// both platforms: plain decimal from 0.001 up to 10 million, scientific notation (`1.0E-7`,
/// `1.0E16`) outside it, never a `+` that a URL would turn into a space.
enum FilterNumberText {

    /// The text for a number whose shortest Swift `description` is `description`.
    static func text(forFloatingPoint description: String) -> String {
        switch description {
        case "nan", "-nan": return "NaN"
        case "inf", "+inf": return "Infinity"
        case "-inf": return "-Infinity"
        default: break
        }
        let isNegative = description.hasPrefix("-")
        let unsigned = isNegative ? description.dropFirst() : Substring(description)
        let parts = unsigned.split(separator: "e", maxSplits: 1)
        let mantissa = parts[0]
        let exponent = parts.count > 1 ? Int(parts[1]) ?? 0 : 0
        let integerPart = mantissa.split(separator: ".", omittingEmptySubsequences: false)[0]
        let allDigits = mantissa.filter(\.isNumber)
        // The value is 0.<digits> × 10^pointPosition once leading zeros are dropped.
        let leadingZeros = allDigits.prefix(while: { $0 == "0" }).count
        var digits = String(allDigits.dropFirst(leadingZeros))
        while digits.hasSuffix("0") { digits.removeLast() }
        let sign = isNegative ? "-" : ""
        guard !digits.isEmpty else { return sign + "0.0" }
        let pointPosition = integerPart.count - leadingZeros + exponent
        let scientificExponent = pointPosition - 1
        guard (-3..<7).contains(scientificExponent) else {
            let fraction = digits.count > 1 ? String(digits.dropFirst()) : "0"
            return "\(sign)\(digits.prefix(1)).\(fraction)E\(scientificExponent)"
        }
        return sign + plainDecimal(digits: digits, pointPosition: pointPosition)
    }

    /// `0.<digits> × 10^pointPosition` in plain decimal, with at least one digit after the point.
    private static func plainDecimal(digits: String, pointPosition: Int) -> String {
        if pointPosition <= 0 {
            return "0." + String(repeating: "0", count: -pointPosition) + digits
        }
        if digits.count <= pointPosition {
            return digits + String(repeating: "0", count: pointPosition - digits.count) + ".0"
        }
        let point = digits.index(digits.startIndex, offsetBy: pointPosition)
        return "\(digits[..<point]).\(digits[point...])"
    }
}
