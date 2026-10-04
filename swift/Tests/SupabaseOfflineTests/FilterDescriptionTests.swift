// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// What a chain writes into the core's description: PostgREST text read into structured filters,
/// and only filter lists the core sends exactly as written.
struct FilterDescriptionTests {

    /// A client whose requests these tests never send.
    private let client = SupabaseOfflineClient.inMemory()

    /// The filters a read of `todos` chained by `chain` describes.
    private func filters(
        _ chain: (OfflineSelectRequestBuilder) -> OfflineSelectRequestBuilder
    ) throws -> OfflineJSON? {
        try descriptionFilters(chain(client.from("todos").select()).descriptionText())
    }

    // MARK: - PostgREST text

    @Test func anOrListIsReadIntoConditionsAndNestedGroups() throws {
        // When
        let nodes = try PostgrestFilterText.nodes(
            inLogicList: #"done.eq.true,title.eq."milk, \"eggs\"",not.and(a.gt.1,b.not.is.null),id.in.(1,"2,3"),tags.cs.{a,b}"#
        )

        // Then
        #expect(nodes == [
            .condition(column: "done", operator: .eq, value: .single("true"), isNegated: false),
            .condition(column: "title", operator: .eq, value: .single(#"milk, "eggs""#), isNegated: false),
            .group(kind: .and, children: [
                .condition(column: "a", operator: .gt, value: .single("1"), isNegated: false),
                .condition(column: "b", operator: .is, value: .single("null"), isNegated: true),
            ], isNegated: true, referencedTable: nil),
            .condition(column: "id", operator: .in, value: .list(["1", "2,3"]), isNegated: false),
            .condition(column: "tags", operator: .cs, value: .single("{a,b}"), isNegated: false),
        ])
    }

    @Test func textSearchesAndPatternListsAreReadWithTheirArguments() throws {
        // When
        let nodes = try PostgrestFilterText.nodes(
            inLogicList: "tsv.wfts(english).cat dog,body.like(all).{%a%,%b%},summary.fts.cat,slot.sl.(1,5)"
        )

        // Then
        #expect(nodes == [
            .textSearch(column: "tsv", query: "cat dog", type: .websearch, config: "english"),
            .patternList(kind: .likeAll, column: "body", patterns: ["%a%", "%b%"]),
            .condition(column: "summary", operator: .fts, value: .single("cat"), isNegated: false),
            .condition(column: "slot", operator: .sl, value: .range("1", "5"), isNegated: false),
        ])
    }

    @Test(arguments: [
        "", "a.eq.1,", "a.eq.1,or()", "a", "a.regex.x", "a.eq.\"open", "a.sl.[1,5)", "a.not.like(all).{x}",
        "a.not.fts(english).x",
    ])
    func textTheCoreCannotSendIsRefused(text: String) {
        #expect(throws: FilterTextError.self) { try PostgrestFilterText.nodes(inLogicList: text) }
    }

    @Test func aBackslashEscapesInAnArrayLiteralAndIsKeptInAList() throws {
        #expect(try PostgrestFilterText.listElements(of: #"{a\\b,c\,d,x\"y,"e f"}"#, opening: "{", closing: "}")
            == [#"a\b"#, "c,d", #"x"y"#, "e f"])
        #expect(try PostgrestFilterText.listElements(of: #"(a\b,c)"#, opening: "(", closing: ")") == [#"a\b"#, "c"])
        #expect(throws: FilterTextError.self) {
            try PostgrestFilterText.listElements(of: #"{a\"#, opening: "{", closing: "}")
        }
    }

    @Test func aSingleConditionTakesItsValueAsWritten() throws {
        #expect(try PostgrestFilterText.node(column: "a", operatorText: "eq", valueText: #""x,y""#)
            == .condition(column: "a", operator: .eq, value: .single(#""x,y""#), isNegated: false))
        #expect(try PostgrestFilterText.node(column: "a", operatorText: "not.in", valueText: "(1,2)")
            == .condition(column: "a", operator: .in, value: .list(["1", "2"]), isNegated: true))
        #expect(try PostgrestFilterText.node(column: "a", operatorText: "plfts(simple)", valueText: "cat dog")
            == .textSearch(column: "a", query: "cat dog", type: .plain, config: "simple"))
        #expect(throws: FilterTextError.self) {
            try PostgrestFilterText.node(column: "a", operatorText: "isdistinct", valueText: "1")
        }
    }

    // MARK: - Filters the core sends as written

    @Test func twoConditionsOnOneColumnGoInOneAndGroup() throws {
        // When
        let filters = try filters { $0.eq("done", value: false).gte("age", value: 18).lt("age", value: 65) }

        // Then
        #expect(filters == [
            ["kind": "filter", "column": "done", "operator": "eq", "value": "false"],
            ["kind": "and", "filters": [
                ["kind": "filter", "column": "age", "operator": "gte", "value": "18"],
                ["kind": "filter", "column": "age", "operator": "lt", "value": "65"],
            ]],
        ])
    }

    @Test func twoOrGroupsOnOneTableGoInOneAndGroupOnThatTable() throws {
        // When
        let filters = try filters {
            $0.or("a.eq.1").or("b.eq.2").or("c.eq.3", referencedTable: "authors").or("d.eq.4", referencedTable: "authors")
                .or("e.eq.5", referencedTable: "tags")
        }

        // Then
        #expect(filters == [
            ["kind": "and", "filters": [
                ["kind": "or", "filters": [["kind": "filter", "column": "a", "operator": "eq", "value": "1"]]],
                ["kind": "or", "filters": [["kind": "filter", "column": "b", "operator": "eq", "value": "2"]]],
            ]],
            ["kind": "and", "referencedTable": "authors", "filters": [
                ["kind": "or", "filters": [["kind": "filter", "column": "c", "operator": "eq", "value": "3"]]],
                ["kind": "or", "filters": [["kind": "filter", "column": "d", "operator": "eq", "value": "4"]]],
            ]],
            ["kind": "or", "referencedTable": "tags", "filters": [
                ["kind": "filter", "column": "e", "operator": "eq", "value": "5"],
            ]],
        ])
    }

    @Test func aTextSearchGoesFirstInItsColumnsGroup() throws {
        // When
        let filters = try filters { $0.neq("tsv", value: "x").textSearch("tsv", query: "cat", config: "english") }

        // Then
        #expect(filters == [
            ["kind": "and", "filters": [
                ["kind": "textSearch", "column": "tsv", "query": "cat", "config": "english"],
                ["kind": "filter", "column": "tsv", "operator": "neq", "value": "x"],
            ]],
        ])
    }

    @Test func aChainWithNothingToRearrangeIsWrittenInCallOrder() throws {
        // When
        let filters = try filters { $0.eq("b", value: 1).or("a.eq.1,a.eq.2").eq("a", value: 3) }

        // Then
        #expect(filters == [
            ["kind": "filter", "column": "b", "operator": "eq", "value": "1"],
            ["kind": "or", "filters": [
                ["kind": "filter", "column": "a", "operator": "eq", "value": "1"],
                ["kind": "filter", "column": "a", "operator": "eq", "value": "2"],
            ]],
            ["kind": "filter", "column": "a", "operator": "eq", "value": "3"],
        ])
    }

    @Test func twoConditionsOnAnEmbeddedColumnGoInThatTablesOwnAndGroup() throws {
        // When
        let filters = try filters {
            $0.gte("authors.born", value: 1900).lt("authors.born", value: 2000)
                .or("name.eq.A", referencedTable: "authors").or("name.eq.B", referencedTable: "authors")
        }

        // Then
        #expect(filters == [
            ["kind": "and", "referencedTable": "authors", "filters": [
                ["kind": "filter", "column": "born", "operator": "gte", "value": "1900"],
                ["kind": "filter", "column": "born", "operator": "lt", "value": "2000"],
                ["kind": "or", "filters": [["kind": "filter", "column": "name", "operator": "eq", "value": "A"]]],
                ["kind": "or", "filters": [["kind": "filter", "column": "name", "operator": "eq", "value": "B"]]],
            ]],
        ])
    }

    @Test func anEmbeddedColumnIsSplitAtItsLastDotBeforeAnyJSONPath() {
        let split: (String) -> [String]? = { column in
            TopLevelFilters.embeddedColumn(column).map { [$0.table, $0.column] }
        }
        #expect(split("authors.born") == ["authors", "born"])
        #expect(split("authors.books.title") == ["authors.books", "title"])
        #expect(split("authors.data->>age") == ["authors", "data->>age"])
        #expect(split("data->>a.b") == nil)
        #expect(split(#""due.date""#) == nil)
        #expect(split("born") == nil)
    }

    @Test(arguments: [
        "two range filters on one column",
        "a range inside an or text",
        "a text search whose query has a comma, folded",
        "a quoted value inside a negated and text",
    ])
    func whatAGroupCannotCarryFailsAsAnInvalidRequest(chain: String) {
        let chains: [String: (OfflineSelectRequestBuilder) -> OfflineSelectRequestBuilder] = [
            "two range filters on one column": { $0.rangeGt("slot", range: "(1,5)").rangeLt("slot", range: "(9,12)") },
            "a range inside an or text": { $0.or("slot.sl.(1,5),done.eq.true") },
            "a text search whose query has a comma, folded": {
                $0.textSearch("tsv", query: "cat, dog", config: "english").neq("tsv", value: "x")
            },
            "a quoted value inside a negated and text": { $0.or(#"not.and(title.eq."a\\b",done.eq.true)"#) },
        ]
        let error = #expect(throws: OfflineError.self) {
            try (chains[chain] ?? { $0 })(client.from("todos").select()).descriptionText()
        }
        #expect(error?.code == .invalidRequest, "\(chain)")
    }

    @Test func whatNoArrangementCanSendFailsAsAnInvalidRequest() {
        // Two text searches on one column: the second would replace the first
        let twoSearches = client.from("notes").select()
            .textSearch("tsv", query: "cat", config: "english").textSearch("tsv", query: "dog", config: "english")
        #expect(TopLevelFilters.refusal(of: [.group(kind: .or, children: [], isNegated: false, referencedTable: nil)]) != nil)

        // Then
        let error = #expect(throws: OfflineError.self) { try twoSearches.descriptionText() }
        #expect(error?.code == .invalidRequest)
    }

    @Test func aFilterTextRefusalFailsTheRequestWithItsReason() {
        let error = #expect(throws: OfflineError.self) {
            try client.from("todos").select().rangeLt("slot", range: "[1,5)").descriptionText()
        }
        #expect(error?.code == .invalidRequest)
        #expect(error?.message.contains("(lower,upper)") == true)
        #expect(error?.message.contains("[1,5)") == false)
        let unreadable = #expect(throws: OfflineError.self) {
            try client.from("todos").select().or(#"done.eq.true,title.eq."zqx"#).descriptionText()
        }
        #expect(unreadable?.code == .invalidRequest)
        #expect(unreadable?.message.contains("zqx") == false)
    }

    // MARK: - The rest of a description

    @Test func aReadDescribesItsColumnsHeadCountTransformsAndStrategy() throws {
        // When
        let description = try descriptionJSON(
            client.from(schema: "app", table: "todos")
                .select("id,title", head: true, count: .planned, readStrategy: .cacheFirst(maxAge: .milliseconds(1_500)))
                .order("title", ascending: false, nullsFirst: true).limit(5, referencedTable: "tags").range(from: 0, to: 9)
                .single().geojson().stripNulls().descriptionText()
        )

        // Then
        #expect(description == [
            "v": 1, "table": "todos", "schema": "app", "columns": "id,title", "head": true, "count": "planned",
            "single": true, "format": "geojson", "stripNulls": true,
            "strategy": ["kind": "cacheFirst", "maxAgeMillis": 1_500],
            "modifiers": [
                ["kind": "order", "column": "title", "ascending": false, "nullsFirst": true],
                ["kind": "limit", "count": 5, "referencedTable": "tags"],
                ["kind": "range", "from": 0, "to": 9],
            ],
        ])
    }

    @Test func aLiveReadDescribesItsStrategy() throws {
        let description = try descriptionJSON(
            client.from("todos").selectAsSequence(as: [TodoRow].self, readStrategy: .networkFirstThenCache).descriptionText()
        )
        #expect(description == [
            "v": 1, "table": "todos", "columns": "*", "strategy": ["kind": "networkFirstThenCache"],
        ])
    }

    @Test func aNegativeMaximumAgeIsRefused() {
        let error = #expect(throws: OfflineError.self) {
            try client.from("todos").select(readStrategy: .cacheOnly(maxAge: .seconds(-1))).descriptionText()
        }
        #expect(error?.code == .invalidRequest)
        #expect(ReadStrategy.cacheOnly(maxAge: .seconds(Int64.max)) != .cacheOnly)
        #expect(throws: Never.self) { try ReadStrategy.milliseconds(in: .seconds(Int64.max)) == Int.max }
    }

    @Test func aWriteDescribesOnlyWhatItsOperationTakes() throws {
        // When
        let insert = try client.from("todos").insert(TodoRow(id: 1, title: "Milk", done: false), defaultToNull: false)
            .select("id").request()
        let upsert = try client.from("todos").upsert(["id": 1], onConflict: "id", ignoreDuplicates: true, mutationStrategy: .onlineOnly)
            .request()
        let delete = try client.from("todos").delete().eq("id", value: 1).request()

        // Then
        #expect(try descriptionJSON(insert.description) == [
            "v": 1, "operation": "insert", "table": "todos", "strategy": "queueIfOffline", "returning": "id",
            "defaultToNull": false,
        ])
        #expect(try descriptionJSON(upsert.description) == [
            "v": 1, "operation": "upsert", "table": "todos", "strategy": "onlineOnly", "onConflict": "id",
            "ignoreDuplicates": true,
        ])
        #expect(try descriptionJSON(delete.description) == [
            "v": 1, "operation": "delete", "table": "todos", "strategy": "queueIfOffline",
            "filters": [["kind": "filter", "column": "id", "operator": "eq", "value": "1"]],
        ])
        #expect(insert.body == #"{"done":false,"id":1,"title":"Milk"}"#)
        #expect(delete.body == nil)
    }

    @Test func anUpdateWithOfflineJSONSendsAnExplicitNull() throws {
        let update = try client.from("todos").update(["notes": nil]).eq("id", value: 1).request()
        #expect(update.body == #"{"notes":null}"#)
    }

    @Test func aBodyThatCannotBeEncodedFailsAsEncodingWhenExecuted() {
        let error = #expect(throws: OfflineError.self) {
            try client.from("todos").insert(["score": Double.nan]).request()
        }
        #expect(error?.code == .encoding)
    }

    @Test func aFunctionCallDescribesItsMethodAndTakesArgumentsByPost() throws {
        let post = try client.rpc("add_points", params: ["points": 5], schema: "app").request()
        let get = try client.rpc("list_open", get: true, count: .exact).limit(3).request()
        #expect(try descriptionJSON(post.description) == ["v": 1, "function": "add_points", "schema": "app"])
        #expect(post.arguments == #"{"points":5}"#)
        #expect(try descriptionJSON(get.description) == [
            "v": 1, "function": "list_open", "method": "get", "count": "exact",
            "modifiers": [["kind": "limit", "count": 3]],
        ])
        #expect(get.arguments == nil)
    }
}
