// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// Any JSON value, for rows and changes no `Codable` type describes.
///
/// It keeps what a synthesized `Encodable` loses: `["notes": nil]` sends `"notes": null`, where a
/// struct with a `nil` property leaves the key out. Write it as a literal:
/// `["title": "Milk", "done": false, "notes": nil]`.
public enum OfflineJSON: Sendable, Hashable {

    /// `null`.
    case null

    /// `true` or `false`.
    case bool(Bool)

    /// A whole number; one beyond `Int`'s range decodes as ``double(_:)``.
    case int(Int)

    /// A number with a fraction or an exponent. It must be finite to encode.
    case double(Double)

    /// A string; a date travels as one, in ISO 8601.
    case string(String)

    /// An array.
    case array([OfflineJSON])

    /// An object.
    case object([String: OfflineJSON])
}

extension OfflineJSON: Codable {

    /// Reads whichever JSON value comes next.
    ///
    /// - Throws: The decoder's `DecodingError` when what comes next is not JSON.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Int.self) {
            self = .int(value)
        } else if let value = try? container.decode(Double.self) {
            self = .double(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([OfflineJSON].self) {
            self = .array(value)
        } else {
            self = .object(try container.decode([String: OfflineJSON].self))
        }
    }

    /// Writes the value as the JSON it stands for.
    ///
    /// - Throws: The encoder's `EncodingError` for a ``double(_:)`` that is not finite.
    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let value): try container.encode(value)
        case .int(let value): try container.encode(value)
        case .double(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }
}

extension OfflineJSON: ExpressibleByNilLiteral {

    /// `nil` as ``null``, so `["notes": nil]` sends a JSON `null`.
    public init(nilLiteral: ()) {
        self = .null
    }
}

extension OfflineJSON: ExpressibleByBooleanLiteral {

    /// A literal `true` or `false`.
    public init(booleanLiteral value: Bool) {
        self = .bool(value)
    }
}

extension OfflineJSON: ExpressibleByIntegerLiteral {

    /// A literal whole number.
    public init(integerLiteral value: Int) {
        self = .int(value)
    }
}

extension OfflineJSON: ExpressibleByFloatLiteral {

    /// A literal number with a fraction.
    public init(floatLiteral value: Double) {
        self = .double(value)
    }
}

extension OfflineJSON: ExpressibleByStringLiteral {

    /// A literal string.
    public init(stringLiteral value: String) {
        self = .string(value)
    }
}

extension OfflineJSON: ExpressibleByArrayLiteral {

    /// A literal array.
    public init(arrayLiteral elements: OfflineJSON...) {
        self = .array(elements)
    }
}

extension OfflineJSON: ExpressibleByDictionaryLiteral {

    /// A literal object; a key given twice keeps its last value.
    public init(dictionaryLiteral elements: (String, OfflineJSON)...) {
        self = .object(Dictionary(elements, uniquingKeysWith: { _, last in last }))
    }
}
