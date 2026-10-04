// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import Testing
@testable import SupabaseOffline

/// The raw JSON value: explicit nulls, exact numbers, and the trap it avoids.
struct OfflineJSONTests {

    @Test func aNilInALiteralIsSentAsNull() throws {
        // Given
        let change: OfflineJSON = ["notes": nil, "done": true, "tags": ["a", "b"], "rank": 2, "score": 0.5]

        // When
        let text = try encodedSorted(change)

        // Then
        #expect(text == #"{"done":true,"notes":null,"rank":2,"score":0.5,"tags":["a","b"]}"#)
    }

    @Test func aStructWithANilPropertyLeavesTheKeyOut() throws {
        // Given the trap OfflineJSON exists for
        struct Change: Encodable {
            let notes: String?
        }

        // When
        let text = try RowCoding.jsonText(for: Change(notes: nil))

        // Then
        #expect(text == "{}")
    }

    @Test func decodesEveryKindOfValue() throws {
        // When
        let value = try RowCoding.decode(
            OfflineJSON.self,
            from: #"{"id":9007199254740993,"title":"Milk","done":false,"price":1.25,"notes":null,"tags":[1,"x"]}"#
        )

        // Then
        #expect(
            value
                == .object([
                    "id": .int(9_007_199_254_740_993),
                    "title": .string("Milk"),
                    "done": .bool(false),
                    "price": .double(1.25),
                    "notes": .null,
                    "tags": .array([.int(1), .string("x")]),
                ])
        )
    }

    @Test func aNumberBeyondIntDecodesAsADouble() throws {
        #expect(try RowCoding.decode(OfflineJSON.self, from: "1e300") == .double(1e300))
    }

    @Test func aRowSurvivesARoundTrip() throws {
        // Given
        let row: OfflineJSON = ["id": 9_223_372_036_854_775_807, "nested": ["list": [nil, false, 1.5]]]

        // When
        let decoded = try RowCoding.decode(OfflineJSON.self, from: try RowCoding.jsonText(for: row))

        // Then
        #expect(decoded == row)
    }

    @Test func aNumberJsonCannotCarryFailsEncoding() {
        // When
        let error = #expect(throws: OfflineError.self) {
            try RowCoding.jsonText(for: OfflineJSON.double(.nan))
        }

        // Then
        #expect(error?.code == .encoding)
        #expect(error?.underlyingError is EncodingError)
    }
}

/// `value` as JSON text with its keys sorted, so a comparison does not depend on dictionary order.
private func encodedSorted(_ value: OfflineJSON) throws -> String {
    let encoder = RowCoding.makeEncoder()
    encoder.outputFormatting = .sortedKeys
    return String(decoding: try encoder.encode(value), as: UTF8.self)
}
