// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation

/// Rows and records to and from JSON text, with dates as PostgREST sends and reads them.
///
/// Keys are left as the type names them (no snake-case conversion), as supabase-swift leaves them.
enum RowCoding {

    /// An encoder that writes a `Date` as ``PostgresTimestamp`` text.
    static func makeEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
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
