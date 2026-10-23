// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// The core's request-rendering fixture, case by case: the Swift chain each case stands for sends
/// exactly the request the fixture records, or fails as it records with nothing sent. The fixture
/// is the file the core's own test reads, so the two cannot drift apart.
struct RequestRenderingFixtureTests {

    /// How one fixture case is reached from Swift.
    enum SwiftForm: Sendable {

        /// The chain that makes the case's request, run on a client of the fixture's URL.
        case chain(@Sendable (SupabaseOfflineClient) async throws -> Void)

        /// No Swift chain makes this description; the reason says why, or which case shows what
        /// the chain sends instead.
        case notExpressible(String)
    }

    /// The fixture's file, beside the core's sources in this repository.
    private static let fixtureURL = URL(filePath: #filePath)
        .deletingLastPathComponent()
        .appending(path: "../../../supabase-offline-swift-bridge/fixtures/request-rendering.json")
        .standardizedFileURL

    /// The fixture's cases, by name.
    private static func fixtureCases() throws -> (baseURL: String, cases: [String: [String: OfflineJSON]]) {
        let fixture = try JSONDecoder().decode(OfflineJSON.self, from: Data(contentsOf: fixtureURL))
        guard case .object(let root) = fixture, case .string(let baseURL) = root["baseUrl"],
            case .array(let cases) = root["cases"]
        else { throw FixtureError(message: "the fixture has no baseUrl or cases") }
        var byName: [String: [String: OfflineJSON]] = [:]
        for item in cases {
            guard case .object(let fields) = item, case .string(let name) = fields["name"] else {
                throw FixtureError(message: "a case has no name")
            }
            byName[name] = fields
        }
        return (baseURL, byName)
    }

    /// A malformed fixture.
    struct FixtureError: Error {

        /// What is wrong with it.
        let message: String
    }

    @Test func everyFixtureCaseHasItsSwiftForm() throws {
        // Given
        let names = Set(try Self.fixtureCases().cases.keys)

        // Then: a case the core adds fails here until its Swift form is written, and one it drops
        // until its form is removed
        let missing = names.subtracting(Self.swiftForms.keys).sorted()
        let stale = Set(Self.swiftForms.keys).subtracting(names).sorted()
        #expect(missing.isEmpty, "no Swift form for: \(missing)")
        #expect(stale.isEmpty, "a Swift form for a case the fixture no longer has: \(stale)")
        #expect(names.count >= 30)
    }

    @Test func everyChainSendsTheRequestTheFixtureRecords() async throws {
        // Given
        let fixture = try Self.fixtureCases()
        var mismatches: [String] = []
        var checked = 0

        for (name, fields) in fixture.cases.sorted(by: { $0.key < $1.key }) {
            guard case .chain(let chain) = Self.swiftForms[name] else { continue }
            // When: each chain runs on a client of its own
            let server = RecordingFakeServer(rows: "[]")
            let client = SupabaseOfflineClient.inMemory(
                supabaseURL: try #require(URL(string: fixture.baseURL)),
                supabaseKey: "fixture-anon-key",
                server: server.server
            )
            var thrown: (any Error)?
            do { try await chain(client) } catch { thrown = error }
            try await client.close()

            // Then
            mismatches += Self.mismatches(name: name, fields: fields, thrown: thrown, requests: server.requests.values)
            checked += 1
        }
        #expect(mismatches.isEmpty, "\(mismatches.joined(separator: "\n"))")
        #expect(checked >= 20)
    }

    /// How a case's outcome differs from the fixture's record of it; empty when it matches.
    private static func mismatches(
        name: String,
        fields: [String: OfflineJSON],
        thrown: (any Error)?,
        requests: [FakeServer.Request]
    ) -> [String] {
        if case .string(let kind) = fields["error"] {
            var found: [String] = []
            let code = (thrown as? OfflineError)?.code
            if code != expectedCode(forKind: kind) { found.append("\(name): expected \(kind), got \(String(describing: thrown))") }
            // A refusal reaches the app from the Swift checks, in the package's words, never the framework's
            if (thrown as? OfflineError)?.message.contains("supabase-kt") == true {
                found.append("\(name): the refusal came from the framework, not the Swift checks")
            }
            if !requests.isEmpty { found.append("\(name): a refused chain sent a request") }
            return found
        }
        guard case .object(let expected) = fields["request"] else { return ["\(name): no request recorded"] }
        guard let request = requests.first else { return ["\(name): nothing was sent (\(String(describing: thrown)))"] }
        var found: [String] = []
        if case .string(let method) = expected["method"], request.method != method {
            found.append("\(name): method \(request.method), expected \(method)")
        }
        if case .string(let path) = expected["path"], request.url.path() != path {
            found.append("\(name): path \(request.url.path()), expected \(path)")
        }
        let query = expected["query"].map(stringPairs) ?? []
        if request.queryParameters.map(columnsAsSet) != query.map(columnsAsSet) {
            found.append("\(name): query \(request.queryParameters), expected \(query)")
        }
        if case .object(let headers) = expected["headers"] {
            for (header, value) in headers where request.header(header) != value.offlineFilterText {
                found.append("\(name): header \(header) is \(String(describing: request.header(header))), expected \(value.offlineFilterText)")
            }
        }
        let body = try? request.jsonBody
        if body != expected["body"] {
            found.append("\(name): body \(String(describing: body)), expected \(String(describing: expected["body"]))")
        }
        return found
    }

    /// A `columns` parameter with its names sorted: Swift writes a body's keys sorted, and PostgREST
    /// reads the list as a set; any other parameter as it is.
    private static func columnsAsSet(_ pair: [String]) -> [String] {
        guard pair.count == 2, pair[0] == "columns" else { return pair }
        return [pair[0], pair[1].split(separator: ",").sorted().joined(separator: ",")]
    }

    /// The code a Swift caller catches for the core's failure `kind`.
    private static func expectedCode(forKind kind: String) -> OfflineError.Code? {
        switch kind {
        case "INVALID_REQUEST", "UNSUPPORTED", "INVALID_ARGUMENT": return .invalidRequest
        case "CACHE_MISS": return .cacheMiss
        default: return nil
        }
    }

    /// `[[name, value], …]` as string pairs.
    private static func stringPairs(_ json: OfflineJSON) -> [[String]] {
        guard case .array(let pairs) = json else { return [] }
        return pairs.map { pair in
            guard case .array(let parts) = pair else { return [] }
            return parts.map(\.offlineFilterText)
        }
    }

    // MARK: - The Swift form of every case

    /// A row whose id is lower-case UUID text, as the fixture writes it.
    private struct NamedRow: Encodable, Sendable {
        let id: String
        let title: String
        let done: Bool
    }

    /// A row with a title.
    private struct TitledRow: Encodable, Sendable {
        let id: Int64
        let title: String
    }

    /// A row with only its key.
    private struct KeyRow: Encodable, Sendable {
        let id: Int
    }

    /// The arguments of `add_points`.
    private struct AddPoints: Encodable, Sendable {
        let user_id: String
        let points: Int
    }

    /// The UUID the fixture filters on, given in upper case so the test proves it is sent in lower.
    private static let owner = UUID(uuidString: "7D6F0C52-9D7E-4B8E-9F51-3C1E2F0A9B11") ?? UUID()

    /// The fixture's `2026-10-04T12:00:00Z`.
    private static let due = Date(timeIntervalSince1970: 1_791_115_200)

    /// Why a description that only a hand-written description reaches has no chain.
    private static let onlyByHand = "the Swift API writes only well-formed descriptions of version 1"

    /// The Swift form of every case, by the fixture's name.
    static let swiftForms: [String: SwiftForm] = [
        "every column, by default": .chain { try await $0.from("todos").select(readStrategy: .networkOnly).execute() },
        "columns lose the spaces outside quotes": .chain {
            try await $0.from("todos").select("id, title, \"due date\"", readStrategy: .networkOnly).execute()
        },
        "a boolean as text, newest first, twenty rows": .chain {
            try await $0.from("todos").select("id,title,done", readStrategy: .networkFirst(maxAge: .seconds(60)))
                .eq("done", value: false).order("created_at", ascending: false).limit(20).execute()
        },
        "the comparison operators": .chain {
            try await $0.from("todos").select(readStrategy: .networkOnly)
                .gte("priority", value: 2).lt("score", value: 9.5).ilike("title", pattern: "%milk%")
                .neq("owner", value: owner).lte("due", value: due).execute()
        },
        "a null value is rendered null": .chain {
            try await $0.from("todos").select(readStrategy: .networkOnly).is("notes", value: nil).execute()
        },
        "a list, quoting a value with a comma": .chain {
            try await $0.from("todos").select(readStrategy: .networkOnly).in("id", values: [owner, "a,b"]).execute()
        },
        "a negated list": .chain {
            try await $0.from("todos").select(readStrategy: .networkOnly).notIn("status", values: ["done", "archived"]).execute()
        },
        "array and range operators": .chain {
            try await $0.from("events").select(readStrategy: .networkOnly)
                .contains("tags", value: ["work", "home"]).overlaps("during", value: "[2026-01-01,2026-02-01]")
                .rangeLt("slot", range: "(1,5)").execute()
        },
        "an or group": .chain {
            try await $0.from("todos").select(readStrategy: .networkOnly).or(#"done.eq.true,title.eq."Buy milk, eggs""#).execute()
        },
        "a negated and group on a referenced table": .notExpressible(
            "the Swift API, as supabase-swift's, has no negated or `and` group: `or(_:referencedTable:)` is its group"
        ),
        "pattern lists and text search": .chain {
            try await $0.from("notes").select(readStrategy: .networkOnly)
                .likeAllOf("body", patterns: ["%cat%", "%dog%"]).iLikeAnyOf("title", patterns: ["a%", "b%"])
                .textSearch("tsv", query: "cat & dog", config: "english", type: .websearch)
                .fts("summary", query: "cat").execute()
        },
        "a range and an order on a referenced table": .chain {
            try await $0.from("books").select(readStrategy: .networkOnly)
                .order("name", referencedTable: "authors").order("title", nullsFirst: true).range(from: 10, to: 19).execute()
        },
        "an exact count, head only": .chain {
            try await $0.from("todos").select(head: true, count: .exact, readStrategy: .networkOnly).execute()
        },
        "one row as an object": .chain {
            try await $0.from("todos").select(readStrategy: .networkOnly).eq("id", value: 1).single().execute()
        },
        "CSV": .chain { try await $0.from("todos").select(readStrategy: .networkOnly).csv().execute() },
        "another schema": .chain { try await $0.from(schema: "app", table: "todos").select(readStrategy: .networkOnly).execute() },
        "an insert of rows, sent at once": .chain {
            try await $0.from("todos").insert([NamedRow(id: owner.offlineFilterText, title: "Buy milk", done: false)]).execute()
        },
        "an insert of one object, returning a column": .chain {
            try await $0.from("todos").insert(TitledRow(id: 9_007_199_254_740_993, title: "Tea"), mutationStrategy: .onlineOnly)
                .select("id").execute()
        },
        "an upsert that ignores duplicates": .chain {
            try await $0.from("todos").upsert([TitledRow(id: 1, title: "Tea")], onConflict: "id", ignoreDuplicates: true).execute()
        },
        "an insert that fills missing columns with their defaults": .chain {
            try await $0.from("todos").insert([KeyRow(id: 2)], defaultToNull: false).execute()
        },
        "an update of one row, with an explicit null": .chain {
            try await $0.from("todos").update(["done": true, "notes": nil]).eq("id", value: 1).execute()
        },
        "a delete of listed rows": .chain { try await $0.from("todos").delete().in("id", values: [1, 2]).execute() },
        "a function call with arguments": .chain {
            try await $0.rpc("add_points", params: AddPoints(user_id: owner.offlineFilterText, points: 5)).gt("total", value: 10).execute()
        },
        "a function call by GET with a count": .chain { try await $0.rpc("list_open", get: true, count: .planned).execute() },
        "a function call by HEAD without arguments": .chain { try await $0.rpc("count_open", head: true, count: .exact).execute() },
        "a function call by GET with arguments is refused": .notExpressible(
            "a function's arguments go by POST only: the GET and HEAD forms of rpc take none"
        ),
        "an operator no supabase-kt has is refused": .chain {
            try await $0.from("todos").select().filter("a", operator: "regex", value: "x").execute()
        },
        "a filter kind this build lacks is refused": .notExpressible(onlyByHand),
        "another description version is refused": .notExpressible(onlyByHand),
        "an unknown field is refused": .notExpressible(onlyByHand),
        "an unknown field of a filter is refused": .notExpressible(onlyByHand),
        "two value forms are refused": .notExpressible(onlyByHand),
        "a number where text belongs is refused": .notExpressible("every Swift filter value is sent as text"),
        "a conflict target on an insert is refused": .notExpressible("only upsert takes onConflict"),
        "a delete with a body is refused": .notExpressible("delete takes no body"),
        "an insert body that is not rows is refused": .chain { try await $0.from("todos").insert([1, 2]).execute() },
        "two or groups nested in one and group": .chain {
            try await $0.from("todos").select(readStrategy: .networkOnly).or("a.eq.1,b.eq.2").or("c.eq.3,d.eq.4").execute()
        },
        "two filters on one column in an and group": .chain {
            try await $0.from("people").select(readStrategy: .networkOnly).gte("age", value: 18).lt("age", value: 65).execute()
        },
        "two or groups at the top level are refused": .notExpressible(
            "the chain sends two or groups in one and group: see \"two or groups nested in one and group\""
        ),
        "two filters on one column at the top level are refused": .notExpressible(
            "the chain sends them in one and group: see \"two filters on one column in an and group\""
        ),
        "a text search after a filter on its column is refused": .notExpressible(
            "the chain sends a text search with a configuration ahead of its column's other filters"
        ),
        "a list for an operator that takes one value is refused": .notExpressible(
            "an array value is sent as one Postgres array literal, such as {x,y}"
        ),
        "a range for an operator that takes one value is refused": .notExpressible("only the range filters take bounds"),
        "a list for a range operator is refused": .chain {
            try await $0.from("todos").select().rangeLt("a", range: ["1", "2", "3"]).execute()
        },
        "one value for in is refused": .chain {
            try await $0.from("todos").select().filter("a", operator: "in", value: "1").execute()
        },
        "a filter with no value is refused": .notExpressible("every Swift filter takes a value"),
        "a null list is refused": .notExpressible(onlyByHand),
        "a range of three bounds is refused": .chain {
            try await $0.from("todos").select().rangeLt("a", range: "(1,2,3)").execute()
        },
        "an empty group is refused": .chain { try await $0.from("todos").select().or("").execute() },
        "an empty group inside a group is refused": .chain {
            try await $0.from("todos").select().or("a.eq.1,or()").execute()
        },
        "a quote in an in list is refused": .chain {
            try await $0.from("todos").select().in("title", values: ["Say \"hi\"", "ok"]).execute()
        },
        "a backslash in an or group is refused": .chain {
            try await $0.from("todos").select().or(#"title.ilike.%50\%%,done.eq.true"#).execute()
        },
        "a quote in an and group is refused": .chain {
            try await $0.from("todos").delete().eq("title", value: "Say \"hi\"").neq("title", value: "x").execute()
        },
        "a quote beside a comma in an or group is quoted": .chain {
            try await $0.from("todos").select(readStrategy: .networkOnly).or(#"title.eq."Say \"hi\", ok",done.eq.true"#).execute()
        },
        "a quote in an in list nested in a group is refused": .chain {
            try await $0.from("todos").select().or(#"and(done.eq.true,title.in.("Say \"hi\""))"#).execute()
        },
        "a quote in a single top-level filter is sent as written": .chain {
            try await $0.from("todos").select(readStrategy: .networkOnly).eq("title", value: "Say \"hi\"").execute()
        },
    ]
}
