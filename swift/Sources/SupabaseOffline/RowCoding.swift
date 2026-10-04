// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation

/// Rows and records to and from JSON text, with dates as PostgREST sends and reads them.
///
/// Keys are left as the type names them (no snake-case conversion), as supabase-swift leaves them.
enum RowCoding {

    /// An encoder that writes a `Date` as ``PostgresTimestamp`` text and a `UUID` in lower case.
    static func makeEncoder() -> JSONEncoder {
        let encoder = LowercaseUUIDEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(PostgresTimestamp.text(for: date))
        }
        return encoder
    }

    /// A decoder that reads a `Date` from any ``PostgresTimestamp`` form.
    static func makeDecoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            guard let date = PostgresTimestamp.date(from: try container.decode(String.self)) else {
                throw DecodingError.dataCorruptedError(
                    in: container,
                    debugDescription: "Expected an ISO 8601 date, such as 2026-10-07T09:30:00Z"
                )
            }
            return date
        }
        return decoder
    }

    /// `value` as JSON text, for a body or a record the core receives.
    static func jsonText(for value: some Encodable) throws -> String {
        do {
            return String(decoding: try makeEncoder().encode(value), as: UTF8.self)
        } catch {
            throw OfflineError(
                code: .encoding,
                message: "A \(type(of: value)) could not be encoded as JSON",
                underlyingError: error
            )
        }
    }

    /// The JSON `text` the core answered with, decoded as `type`.
    static func decode<Value: Decodable>(_ type: Value.Type, from text: String) throws -> Value {
        do {
            return try makeDecoder().decode(type, from: Data(text.utf8))
        } catch {
            throw OfflineError(
                code: .decoding,
                message: "The response could not be decoded as \(type)",
                underlyingError: error
            )
        }
    }
}

// MARK: - UUIDs in lower case

/// A `JSONEncoder` that writes every `UUID` it meets, at any depth, as lower-case text, as the
/// server writes ids. It cannot reach a `UUID` handed to an encoder directly rather than through a
/// container: by a type's own `encode(to:)`, or by a property wrapper that passes its encoder on.
final class LowercaseUUIDEncoder: JSONEncoder, @unchecked Sendable {

    /// `value` as JSON, its `UUID`s in lower case.
    override func encode<T: Encodable>(_ value: T) throws -> Data {
        try super.encode(LowercaseUUIDRoot(value: value))
    }
}

/// The value an encoder writes, with every `UUID` in it in lower case and every value the JSON
/// encoder writes in a form of its own (dates, data, URLs, decimals) handed to it as it is.
private func lowercasingUUIDs(in value: some Encodable) -> any Encodable {
    switch value {
    case let uuid as UUID: return uuid.uuidString.lowercased()
    case let date as Date: return date
    case let data as Data: return data
    case let url as URL: return url
    case let decimal as Decimal: return decimal
    default: return LowercaseUUIDValue(value: value)
    }
}

/// The value at the top of an encoding, which may itself be a `UUID`.
private struct LowercaseUUIDRoot<Value: Encodable>: Encodable {

    /// The value.
    let value: Value

    func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(lowercasingUUIDs(in: value))
    }
}

/// A value whose own encoding goes through ``LowercaseUUIDEncoding``.
private struct LowercaseUUIDValue<Value: Encodable>: Encodable {

    /// The value.
    let value: Value

    func encode(to encoder: any Encoder) throws {
        try value.encode(to: LowercaseUUIDEncoding(base: encoder))
    }
}

/// An encoder that passes everything to `base`, with every value a container receives lowercased
/// by ``lowercasingUUIDs(in:)`` first.
private struct LowercaseUUIDEncoding: Encoder {

    /// The encoder that writes.
    let base: any Encoder

    var codingPath: [any CodingKey] { base.codingPath }

    var userInfo: [CodingUserInfoKey: Any] { base.userInfo }

    func container<Key: CodingKey>(keyedBy type: Key.Type) -> KeyedEncodingContainer<Key> {
        KeyedEncodingContainer(LowercaseUUIDKeyedContainer(base: base.container(keyedBy: type)))
    }

    func unkeyedContainer() -> any UnkeyedEncodingContainer {
        LowercaseUUIDUnkeyedContainer(base: base.unkeyedContainer())
    }

    func singleValueContainer() -> any SingleValueEncodingContainer {
        LowercaseUUIDSingleValueContainer(base: base.singleValueContainer())
    }
}

/// A keyed container that lowercases the `UUID`s in what it is given.
private struct LowercaseUUIDKeyedContainer<Key: CodingKey>: KeyedEncodingContainerProtocol {

    /// The container that writes.
    var base: KeyedEncodingContainer<Key>

    var codingPath: [any CodingKey] { base.codingPath }

    mutating func encodeNil(forKey key: Key) throws { try base.encodeNil(forKey: key) }
    mutating func encode(_ value: Bool, forKey key: Key) throws { try base.encode(value, forKey: key) }
    mutating func encode(_ value: String, forKey key: Key) throws { try base.encode(value, forKey: key) }
    mutating func encode(_ value: Double, forKey key: Key) throws { try base.encode(value, forKey: key) }
    mutating func encode(_ value: Float, forKey key: Key) throws { try base.encode(value, forKey: key) }
    mutating func encode(_ value: Int, forKey key: Key) throws { try base.encode(value, forKey: key) }
    mutating func encode(_ value: Int8, forKey key: Key) throws { try base.encode(value, forKey: key) }
    mutating func encode(_ value: Int16, forKey key: Key) throws { try base.encode(value, forKey: key) }
    mutating func encode(_ value: Int32, forKey key: Key) throws { try base.encode(value, forKey: key) }
    mutating func encode(_ value: Int64, forKey key: Key) throws { try base.encode(value, forKey: key) }
    mutating func encode(_ value: UInt, forKey key: Key) throws { try base.encode(value, forKey: key) }
    mutating func encode(_ value: UInt8, forKey key: Key) throws { try base.encode(value, forKey: key) }
    mutating func encode(_ value: UInt16, forKey key: Key) throws { try base.encode(value, forKey: key) }
    mutating func encode(_ value: UInt32, forKey key: Key) throws { try base.encode(value, forKey: key) }
    mutating func encode(_ value: UInt64, forKey key: Key) throws { try base.encode(value, forKey: key) }

    mutating func encode<T: Encodable>(_ value: T, forKey key: Key) throws {
        try base.encode(lowercasingUUIDs(in: value), forKey: key)
    }

    mutating func nestedContainer<NestedKey: CodingKey>(
        keyedBy keyType: NestedKey.Type,
        forKey key: Key
    ) -> KeyedEncodingContainer<NestedKey> {
        KeyedEncodingContainer(LowercaseUUIDKeyedContainer<NestedKey>(base: base.nestedContainer(keyedBy: keyType, forKey: key)))
    }

    mutating func nestedUnkeyedContainer(forKey key: Key) -> any UnkeyedEncodingContainer {
        LowercaseUUIDUnkeyedContainer(base: base.nestedUnkeyedContainer(forKey: key))
    }

    mutating func superEncoder() -> any Encoder {
        LowercaseUUIDEncoding(base: base.superEncoder())
    }

    mutating func superEncoder(forKey key: Key) -> any Encoder {
        LowercaseUUIDEncoding(base: base.superEncoder(forKey: key))
    }
}

/// An unkeyed container that lowercases the `UUID`s in what it is given.
private struct LowercaseUUIDUnkeyedContainer: UnkeyedEncodingContainer {

    /// The container that writes.
    var base: any UnkeyedEncodingContainer

    var codingPath: [any CodingKey] { base.codingPath }

    var count: Int { base.count }

    mutating func encodeNil() throws { try base.encodeNil() }
    mutating func encode(_ value: Bool) throws { try base.encode(value) }
    mutating func encode(_ value: String) throws { try base.encode(value) }
    mutating func encode(_ value: Double) throws { try base.encode(value) }
    mutating func encode(_ value: Float) throws { try base.encode(value) }
    mutating func encode(_ value: Int) throws { try base.encode(value) }
    mutating func encode(_ value: Int8) throws { try base.encode(value) }
    mutating func encode(_ value: Int16) throws { try base.encode(value) }
    mutating func encode(_ value: Int32) throws { try base.encode(value) }
    mutating func encode(_ value: Int64) throws { try base.encode(value) }
    mutating func encode(_ value: UInt) throws { try base.encode(value) }
    mutating func encode(_ value: UInt8) throws { try base.encode(value) }
    mutating func encode(_ value: UInt16) throws { try base.encode(value) }
    mutating func encode(_ value: UInt32) throws { try base.encode(value) }
    mutating func encode(_ value: UInt64) throws { try base.encode(value) }

    mutating func encode<T: Encodable>(_ value: T) throws {
        try base.encode(lowercasingUUIDs(in: value))
    }

    mutating func nestedContainer<NestedKey: CodingKey>(keyedBy keyType: NestedKey.Type) -> KeyedEncodingContainer<NestedKey> {
        KeyedEncodingContainer(LowercaseUUIDKeyedContainer<NestedKey>(base: base.nestedContainer(keyedBy: keyType)))
    }

    mutating func nestedUnkeyedContainer() -> any UnkeyedEncodingContainer {
        LowercaseUUIDUnkeyedContainer(base: base.nestedUnkeyedContainer())
    }

    mutating func superEncoder() -> any Encoder {
        LowercaseUUIDEncoding(base: base.superEncoder())
    }
}

/// A single-value container that lowercases the `UUID` in what it is given.
private struct LowercaseUUIDSingleValueContainer: SingleValueEncodingContainer {

    /// The container that writes.
    var base: any SingleValueEncodingContainer

    var codingPath: [any CodingKey] { base.codingPath }

    mutating func encodeNil() throws { try base.encodeNil() }
    mutating func encode(_ value: Bool) throws { try base.encode(value) }
    mutating func encode(_ value: String) throws { try base.encode(value) }
    mutating func encode(_ value: Double) throws { try base.encode(value) }
    mutating func encode(_ value: Float) throws { try base.encode(value) }
    mutating func encode(_ value: Int) throws { try base.encode(value) }
    mutating func encode(_ value: Int8) throws { try base.encode(value) }
    mutating func encode(_ value: Int16) throws { try base.encode(value) }
    mutating func encode(_ value: Int32) throws { try base.encode(value) }
    mutating func encode(_ value: Int64) throws { try base.encode(value) }
    mutating func encode(_ value: UInt) throws { try base.encode(value) }
    mutating func encode(_ value: UInt8) throws { try base.encode(value) }
    mutating func encode(_ value: UInt16) throws { try base.encode(value) }
    mutating func encode(_ value: UInt32) throws { try base.encode(value) }
    mutating func encode(_ value: UInt64) throws { try base.encode(value) }

    mutating func encode<T: Encodable>(_ value: T) throws {
        try base.encode(lowercasingUUIDs(in: value))
    }
}
