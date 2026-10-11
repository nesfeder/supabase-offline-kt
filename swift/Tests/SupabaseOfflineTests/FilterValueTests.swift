// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import Testing
@testable import SupabaseOffline

/// Filter values are written once, as PostgREST reads them, in one fixed form per type.
struct FilterValueTests {

    /// A status stored as text, which declares the conformance and gets its text from the raw value.
    private enum Status: String, OfflineFilterValue {
        case open
        case archived
    }

    @Test func plainValuesAreWrittenAsPostgresReadsThem() throws {
        let uuid = try #require(UUID(uuidString: "7D6F0C52-9D7E-4B8E-9F51-3C1E2F0A9B11"))
        let cases: [(any OfflineFilterValue, String)] = [
            ("milk", "milk"), (true, "true"), (false, "false"), (42, "42"), (Int64.min, "-9223372036854775808"),
            (UInt8(7), "7"), (uuid, "7d6f0c52-9d7e-4b8e-9f51-3c1e2f0a9b11"),
            (Decimal(string: "12345678901234567.89") ?? 0, "12345678901234567.89"),
            (Status.archived, "archived"), (String?.none, "null"), (Int?.some(3), "3"),
        ]
        for (value, expected) in cases {
            #expect(value.offlineFilterText == expected, "\(value)")
        }
    }

    @Test(arguments: [
        (2.0, "2.0"), (9.5, "9.5"), (-0.5, "-0.5"), (0.001, "0.001"), (0.0001, "1.0E-4"), (1e-7, "1.0E-7"),
        (1_234_567.0, "1234567.0"), (1e7, "1.0E7"), (12_345_678.0, "1.2345678E7"), (1e16, "1.0E16"),
        (1.5e300, "1.5E300"), (0.0, "0.0"), (-0.0, "-0.0"), (Double.nan, "NaN"), (Double.infinity, "Infinity"),
        (-Double.infinity, "-Infinity"),
    ])
    func doublesAreWrittenInOneFormWithoutAPlusSign(value: Double, expected: String) {
        #expect(value.offlineFilterText == expected)
        #expect(!value.offlineFilterText.contains("+"))
    }

    @Test func aFloatKeepsItsOwnShortestDigits() {
        #expect(Float(0.1).offlineFilterText == "0.1")
        #expect(Float(3e-5).offlineFilterText == "3.0E-5")
    }

    @Test func aDateIsUTCToTheMicrosecond() {
        let date = Date(timeIntervalSince1970: 1_791_115_200)
        #expect(date.offlineFilterText == "2026-10-04T12:00:00Z")
        #expect(date.addingTimeInterval(0.25).offlineFilterText == "2026-10-04T12:00:00.250Z")
        #expect(date.addingTimeInterval(0.000_125).offlineFilterText == "2026-10-04T12:00:00.000125Z")
    }

    @Test func anArrayIsAPostgresArrayLiteralQuotedWhereItMustBe() {
        #expect(["work", "home"].offlineFilterText == "{work,home}")
        #expect(["at home", "a,b", "", "null", #"say "hi""#, #"back\slash"#].offlineFilterText
            == #"{"at home","a,b","","null","say \"hi\"","back\\slash"}"#)
        #expect([[1, 2], [3]].offlineFilterText == "{{1,2},{3}}")
        #expect([Int?.some(1), nil].offlineFilterText == "{1,NULL}")
        #expect([String]().offlineFilterText == "{}")
    }

    @Test func offlineJSONIsWrittenAsItsValue() {
        let object: OfflineJSON = ["b": 1, "a": ["x": nil]]
        #expect(object.offlineFilterText == #"{"a":{"x":null},"b":1}"#)
        #expect(OfflineJSON.array(["a b", 2, nil, [true]]).offlineFilterText == #"{"a b",2,NULL,{true}}"#)
        #expect(OfflineJSON.null.offlineFilterText == "null")
        #expect(OfflineJSON.double(1e-7).offlineFilterText == "1.0E-7")
    }

    @Test func anArrayIsAListForTheListOperators() {
        let value: any OfflineFilterValue = [1, 2]
        #expect((value as? any FilterValueList)?.elementTexts == ["1", "2"])
        #expect(("(1,2)" as any OfflineFilterValue) as? any FilterValueList == nil)
    }
}
