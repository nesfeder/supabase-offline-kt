// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// Every `UUID` in a body or record is written in lower case, as the server and the core write
/// ids, at any depth; reading accepts either case.
struct RowCodingUUIDTests {

    /// An id whose text has letters, so its case shows.
    private static let id = UUID(uuidString: "9C822319-F88A-42D8-83E9-942796340802")!

    /// `id`'s text as PostgREST writes it.
    private static let lowercaseID = "9c822319-f88a-42d8-83e9-942796340802"

    /// Another id, for lists.
    private static let otherID = UUID(uuidString: "F3C522EE-5583-495D-82BD-27B3E7B0C81A")!

    @Test func aUUIDAtTheTopIsWrittenInLowerCase() throws {
        #expect(try encoded(Self.id) == "\"\(Self.lowercaseID)\"")
    }

    @Test func aUUIDPropertyIsWrittenInLowerCase() throws {
        // Given
        struct Row: Encodable {
            let id: UUID
            let title: String
        }

        // When
        let text = try encoded(Row(id: Self.id, title: "Milk"))

        // Then
        #expect(text == #"{"id":"\#(Self.lowercaseID)","title":"Milk"}"#)
    }

    @Test func anOptionalUUIDIsWrittenInLowerCaseOrLeftOut() throws {
        // Given
        struct Row: Encodable {
            let parentID: UUID?
        }

        // When
        let present = try encoded(Row(parentID: Self.id))
        let absent = try encoded(Row(parentID: nil))
        let listOfOptionals = try encoded([Self.id, nil] as [UUID?])

        // Then
        #expect(present == #"{"parentID":"\#(Self.lowercaseID)"}"#)
        #expect(absent == "{}")
        #expect(listOfOptionals == #"["\#(Self.lowercaseID)",null]"#)
    }

    @Test func uuidsInAListAreWrittenInLowerCase() throws {
        // Given
        struct Row: Encodable {
            let tagIDs: [UUID]
        }

        // When
        let text = try encoded(Row(tagIDs: [Self.id, Self.otherID]))

        // Then
        #expect(text == #"{"tagIDs":["\#(Self.lowercaseID)","f3c522ee-5583-495d-82bd-27b3e7b0c81a"]}"#)
    }

    @Test func aUUIDInANestedValueIsWrittenInLowerCase() throws {
        // Given
        struct Owner: Encodable {
            let id: UUID
        }
        struct Row: Encodable {
            let owner: Owner
            let history: [[Owner]]
        }

        // When
        let text = try encoded(Row(owner: Owner(id: Self.id), history: [[Owner(id: Self.otherID)]]))

        // Then
        #expect(
            text == #"{"history":[[{"id":"f3c522ee-5583-495d-82bd-27b3e7b0c81a"}]],"owner":{"id":"\#(Self.lowercaseID)"}}"#
        )
    }

    @Test func aUUIDAsADictionaryValueIsWrittenInLowerCase() throws {
        #expect(try encoded(["owner": Self.id]) == #"{"owner":"\#(Self.lowercaseID)"}"#)
    }

    @Test func aUUIDInAnEnumsPayloadIsWrittenInLowerCase() throws {
        // Given
        enum Target: Encodable {
            case task(id: UUID)
        }

        // When
        let text = try encoded(Target.task(id: Self.id))

        // Then
        #expect(text == #"{"task":{"id":"\#(Self.lowercaseID)"}}"#)
    }

    @Test func aUUIDAClassWritesThroughItsSuperEncoderIsWrittenInLowerCase() throws {
        // Given
        class Base: Encodable {
            let id = RowCodingUUIDTests.id
        }
        final class Child: Base {
            let title = "Milk"

            enum CodingKeys: String, CodingKey {
                case title
                case base
            }

            override func encode(to encoder: any Encoder) throws {
                var container = encoder.container(keyedBy: CodingKeys.self)
                try container.encode(title, forKey: .title)
                try super.encode(to: container.superEncoder(forKey: .base))
            }
        }

        // When
        let text = try encoded(Child())

        // Then
        #expect(text == #"{"base":{"id":"\#(Self.lowercaseID)"},"title":"Milk"}"#)
    }

    @Test func datesDataAndURLsKeepTheirOwnFormsBesideAUUID() throws {
        // Given
        struct Row: Encodable {
            let id: UUID
            let at: Date
            let later: [Date?]
            let bytes: Data
            let link: URL
            let amount: Decimal
        }
        let at = Date(timeIntervalSince1970: 1_791_115_200.5)

        // When
        let text = try encoded(
            Row(
                id: Self.id,
                at: at,
                later: [at, nil],
                bytes: Data([1, 2, 3]),
                link: URL(string: "https://example.com/a/b")!,
                amount: Decimal(string: "12.5")!
            )
        )

        // Then dates are timestamps (not seconds), data base64, URLs text and decimals numbers
        let stamp = PostgresTimestamp.text(for: at)
        #expect(
            text
                == #"{"amount":12.5,"at":"\#(stamp)","bytes":"AQID","id":"\#(Self.lowercaseID)","#
                + #""later":["\#(stamp)",null],"link":"https:\/\/example.com\/a\/b"}"#
        )
        #expect(stamp == "2026-10-04T12:00:00.500Z")
    }

    @Test(arguments: ["9C822319-F88A-42D8-83E9-942796340802", "9c822319-f88a-42d8-83e9-942796340802"])
    func aUUIDIsReadInEitherCase(text: String) throws {
        // Given
        struct Row: Decodable {
            let id: UUID
        }

        // When
        let row = try RowCoding.decode(Row.self, from: #"{"id":"\#(text)"}"#)

        // Then
        #expect(row.id == Self.id)
    }

    @Test func anInsertSendsQueuesAndCachesItsIdInLowerCase() async throws {
        // Given a table read once while online
        struct Row: Codable, Sendable {
            let id: UUID
            let title: String
        }
        let server = RecordingFakeServer(rows: "[]")
        let client = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server)
        _ = try await client.from("tasks").select().execute()

        // When one row is inserted online and another with the server out of reach
        _ = try await client.from("tasks").insert(Row(id: Self.otherID, title: "Eggs")).execute()
        server.goOffline()
        let outcome = try await client.from("tasks").insert(Row(id: Self.id, title: "Milk")).execute()
        let shown = try await client.rawSelect(#"{"v":1,"table":"tasks","strategy":{"kind":"cacheOnly"}}"#)

        // Then
        guard case .queued(let pending) = outcome else {
            Issue.record("expected a queued write, got \(outcome)")
            return
        }
        let sent = try server.requests(withMethod: "POST").first?.jsonBody
        #expect(sent == [["id": "f3c522ee-5583-495d-82bd-27b3e7b0c81a", "title": "Eggs"]])
        #expect(pending.body == [["id": .string(Self.lowercaseID), "title": "Milk"]])
        #expect(pending.rowKeys == [["id": .string(Self.lowercaseID)]])
        #expect(shown.contains(Self.lowercaseID))
        #expect(!shown.contains(Self.id.uuidString))
        try await client.close()
    }
}

/// `value` as the package writes a body, with its keys sorted so a comparison is exact.
private func encoded(_ value: some Encodable) throws -> String {
    let encoder = RowCoding.makeEncoder()
    encoder.outputFormatting = .sortedKeys
    return String(decoding: try encoder.encode(value), as: UTF8.self)
}
