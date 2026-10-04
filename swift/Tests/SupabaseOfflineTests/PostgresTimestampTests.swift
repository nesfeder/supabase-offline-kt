// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import Testing
@testable import SupabaseOffline

/// The date forms PostgREST sends and reads.
struct PostgresTimestampTests {

    /// 2026-09-21T10:15:30Z, in seconds since 1970.
    private static let instant: Int64 = 1_789_985_730

    /// Each accepted form and the instant it names, in microseconds since 1970.
    private static let acceptedForms: [(text: String, microseconds: Int64)] = [
        ("2026-09-21T10:15:30Z", instant * 1_000_000),
        ("2026-09-21T10:15:30z", instant * 1_000_000),
        ("2026-09-21T10:15:30.1Z", instant * 1_000_000 + 100_000),
        ("2026-09-21T10:15:30.123Z", instant * 1_000_000 + 123_000),
        ("2026-09-21T10:15:30.123456Z", instant * 1_000_000 + 123_456),
        ("2026-09-21T10:15:30.123456789Z", instant * 1_000_000 + 123_457),
        ("2026-09-21T10:15:30.123456+00:00", instant * 1_000_000 + 123_456),
        ("2026-09-21T12:15:30.123456+02:00", instant * 1_000_000 + 123_456),
        ("2026-09-21T15:45:30+0530", instant * 1_000_000),
        ("2026-09-21T02:15:30-08", instant * 1_000_000),
        ("2026-09-21 10:15:30.5+00", instant * 1_000_000 + 500_000),
        ("2026-09-21T10:15:30", instant * 1_000_000),
        ("2026-09-21T10:15:30.000001", instant * 1_000_000 + 1),
        ("2026-09-21", 1_789_948_800 * 1_000_000),
        ("1969-12-31T23:59:59.999999Z", -1),
        ("1970-01-01T00:00:00Z", 0),
        ("2024-02-29T00:00:00Z", 1_709_164_800 * 1_000_000),
        ("2000-02-29T23:59:59Z", 951_868_799 * 1_000_000),
    ]

    @Test(arguments: acceptedForms)
    func readsEveryFormPostgrestSends(text: String, microseconds: Int64) throws {
        // When
        let date = try #require(PostgresTimestamp.date(from: text))

        // Then
        #expect(microsecondsSince1970(date) == microseconds, "\(text)")
    }

    @Test(arguments: [
        "", "2026-09-21T", "2026-9-21", "21/09/2026", "2026-02-30T00:00:00Z", "2026-13-01T00:00:00Z",
        "2023-02-29", "2026-09-21T24:00:00Z", "2026-09-21T10:60:00Z", "2026-09-21T10:15Z",
        "2026-09-21T10:15:30.Z", "2026-09-21T10:15:30.1234567890Z", "2026-09-21T10:15:30+5",
        "2026-09-21T10:15:30+05:3", "2026-09-21T10:15:30+19:00", "2026-09-21T10:15:30Z ", "2026-09-21X10:15:30Z",
    ])
    func refusesEverythingElse(text: String) {
        #expect(PostgresTimestamp.date(from: text) == nil, "\(text)")
    }

    @Test func writesUtcWithOnlyTheFractionDigitsNeeded() {
        #expect(PostgresTimestamp.text(for: date(microseconds: Self.instant * 1_000_000)) == "2026-09-21T10:15:30Z")
        #expect(
            PostgresTimestamp.text(for: date(microseconds: Self.instant * 1_000_000 + 120_000))
                == "2026-09-21T10:15:30.120Z"
        )
        #expect(
            PostgresTimestamp.text(for: date(microseconds: Self.instant * 1_000_000 + 123_456))
                == "2026-09-21T10:15:30.123456Z"
        )
        #expect(PostgresTimestamp.text(for: date(microseconds: -1)) == "1969-12-31T23:59:59.999999Z")
        #expect(PostgresTimestamp.text(for: date(microseconds: 0)) == "1970-01-01T00:00:00Z")
    }

    @Test func aMicrosecondTimestampSurvivesARoundTrip() throws {
        for text in ["2026-09-21T10:15:30.123456Z", "2099-12-31T23:59:59.999999Z", "1901-01-01T00:00:00.000001Z"] {
            let date = try #require(PostgresTimestamp.date(from: text))
            #expect(PostgresTimestamp.text(for: date) == text)
        }
    }

    @Test func civilDatesAndDayCountsAgreeAcrossFourCenturies() {
        for days in stride(from: Int64(-146_097), through: 146_097 * 2, by: 7) {
            let civil = PostgresTimestamp.civilDate(fromDaysSince1970: days)
            #expect(PostgresTimestamp.daysSince1970(year: civil.year, month: civil.month, day: civil.day) == days)
        }
    }

    @Test func rowsEncodeAndDecodeDatesInThatText() throws {
        // Given
        struct Stamped: Codable, Equatable {
            let at: Date
        }
        let stamped = Stamped(at: date(microseconds: Self.instant * 1_000_000 + 123_456))

        // When
        let text = try RowCoding.jsonText(for: stamped)
        let decoded = try RowCoding.decode(Stamped.self, from: #"{"at":"2026-09-21T12:15:30.123456+02:00"}"#)

        // Then
        #expect(text == #"{"at":"2026-09-21T10:15:30.123456Z"}"#)
        #expect(decoded == stamped)
    }

    @Test func aDateNotInAnyFormFailsDecodingWithTheDecodersError() {
        // Given
        struct Stamped: Decodable {
            let at: Date
        }

        // When
        let error = #expect(throws: OfflineError.self) {
            try RowCoding.decode(Stamped.self, from: #"{"at":"yesterday"}"#)
        }

        // Then
        #expect(error?.code == .decoding)
        #expect(error?.underlyingError is DecodingError)
        #expect(error?.message.contains("yesterday") == false)
    }
}

/// `date` in whole microseconds since 1970, rounded.
private func microsecondsSince1970(_ date: Date) -> Int64 {
    Int64((date.timeIntervalSinceReferenceDate * 1_000_000).rounded()) + PostgresTimestamp.secondsFrom1970To2001 * 1_000_000
}

/// The date `microseconds` after 1970.
private func date(microseconds: Int64) -> Date {
    Date(
        timeIntervalSinceReferenceDate: Double(microseconds - PostgresTimestamp.secondsFrom1970To2001 * 1_000_000)
            / 1_000_000
    )
}
