// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation

/// Dates as PostgREST reads and writes them: ISO 8601 text, to the microsecond.
///
/// Parsing accepts `T` or a space between date and time, 0 to 9 fraction digits, and `Z`, an
/// offset (`+02`, `+0200`, `+02:00`) or no zone (read as UTC, as a `timestamp` column sends);
/// a bare date is midnight UTC. Formatting writes UTC with 0, 3 or 6 fraction digits.
enum PostgresTimestamp {

    /// The date `text` names, or `nil` when it is not one of the forms above.
    static func date(from text: String) -> Date? {
        var reader = TimestampReader(text)
        return reader.readDate()
    }

    /// `date` as UTC text, rounded to the microsecond: no fraction when it is whole seconds, three
    /// digits when it is whole milliseconds, six otherwise.
    static func text(for date: Date) -> String {
        let microseconds =
            Int64((date.timeIntervalSinceReferenceDate * 1_000_000).rounded()) + secondsFrom1970To2001 * 1_000_000
        let (seconds, fraction) = floorDivision(microseconds, by: 1_000_000)
        let (days, secondOfDay) = floorDivision(seconds, by: 86_400)
        let (year, month, day) = civilDate(fromDaysSince1970: days)
        var text = "\(padded(year, to: 4))-\(padded(month, to: 2))-\(padded(day, to: 2))T"
        text += "\(padded(secondOfDay / 3_600, to: 2)):\(padded(secondOfDay % 3_600 / 60, to: 2))"
        text += ":\(padded(secondOfDay % 60, to: 2))"
        if fraction != 0 {
            text += fraction % 1_000 == 0 ? ".\(padded(fraction / 1_000, to: 3))" : ".\(padded(fraction, to: 6))"
        }
        return text + "Z"
    }

    /// The seconds between 1970 and 2001, the reference date `Date` counts from; counting from it
    /// keeps a microsecond exact through the arithmetic.
    static let secondsFrom1970To2001: Int64 = 978_307_200

    /// Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's `days_from_civil`).
    static func daysSince1970(year: Int64, month: Int64, day: Int64) -> Int64 {
        let shiftedYear = month <= 2 ? year - 1 : year
        let era = (shiftedYear >= 0 ? shiftedYear : shiftedYear - 399) / 400
        let yearOfEra = shiftedYear - era * 400
        let dayOfYear = (153 * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    /// The proleptic Gregorian date `days` after 1970-01-01 (Howard Hinnant's `civil_from_days`).
    static func civilDate(fromDaysSince1970 days: Int64) -> (year: Int64, month: Int64, day: Int64) {
        let shifted = days + 719_468
        let era = (shifted >= 0 ? shifted : shifted - 146_096) / 146_097
        let dayOfEra = shifted - era * 146_097
        let yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        let shiftedMonth = (5 * dayOfYear + 2) / 153
        let day = dayOfYear - (153 * shiftedMonth + 2) / 5 + 1
        let month = shiftedMonth < 10 ? shiftedMonth + 3 : shiftedMonth - 9
        return (yearOfEra + era * 400 + (month <= 2 ? 1 : 0), month, day)
    }

    /// The quotient rounded down and the non-negative remainder.
    private static func floorDivision(_ value: Int64, by divisor: Int64) -> (Int64, Int64) {
        let remainder = value % divisor
        return remainder < 0 ? (value / divisor - 1, remainder + divisor) : (value / divisor, remainder)
    }

    /// `value` in decimal with leading zeros to `width` digits.
    private static func padded(_ value: Int64, to width: Int) -> String {
        let digits = String(value)
        return digits.count >= width ? digits : String(repeating: "0", count: width - digits.count) + digits
    }
}

/// Reads one timestamp's text left to right; every `read` returns `nil` on a character it did not expect.
private struct TimestampReader {

    /// The text's UTF-8 bytes.
    private let bytes: [UInt8]

    /// The index of the next byte to read.
    private var position = 0

    init(_ text: String) {
        bytes = Array(text.utf8)
    }

    /// The whole text as a date, or `nil` when anything in it is out of place or out of range.
    mutating func readDate() -> Date? {
        guard let year = readNumber(digits: 4), read("-"), let month = readNumber(digits: 2), read("-"),
            let day = readNumber(digits: 2), (1...12).contains(month), (1...daysIn(month, of: year)).contains(day)
        else { return nil }
        var seconds = PostgresTimestamp.daysSince1970(year: year, month: month, day: day) * 86_400
        var fraction = 0.0
        if position < bytes.count {
            guard read("T") || read("t") || read(" "), let time = readTime() else { return nil }
            seconds += time.seconds
            fraction = time.fraction
            guard let offset = readOffset() else { return nil }
            seconds -= offset
        }
        guard position == bytes.count else { return nil }
        return Date(timeIntervalSinceReferenceDate: Double(seconds - PostgresTimestamp.secondsFrom1970To2001) + fraction)
    }

    /// `HH:mm:ss` and an optional fraction, as seconds into the day and a fraction of a second.
    private mutating func readTime() -> (seconds: Int64, fraction: Double)? {
        guard let hour = readNumber(digits: 2), read(":"), let minute = readNumber(digits: 2), read(":"),
            let second = readNumber(digits: 2), hour < 24, minute < 60, second < 60
        else { return nil }
        var fraction = 0.0
        if read(".") {
            var digits: Int64 = 0
            var scale = 1.0
            while let digit = peekDigit(), scale < 1e10 {
                digits = digits * 10 + Int64(digit)
                scale *= 10
                position += 1
            }
            guard scale > 1, scale < 1e10 else { return nil }
            fraction = Double(digits) / scale
        }
        return (hour * 3_600 + minute * 60 + second, fraction)
    }

    /// The zone, as seconds east of UTC: `0` for `Z` or no zone.
    private mutating func readOffset() -> Int64? {
        if position == bytes.count || read("Z") || read("z") { return 0 }
        let sign: Int64
        if read("+") {
            sign = 1
        } else if read("-") {
            sign = -1
        } else {
            return nil
        }
        guard let hours = readNumber(digits: 2), hours <= 18 else { return nil }
        var minutes: Int64 = 0
        if position < bytes.count {
            _ = read(":")
            guard let readMinutes = readNumber(digits: 2), readMinutes < 60 else { return nil }
            minutes = readMinutes
        }
        return sign * (hours * 3_600 + minutes * 60)
    }

    /// Exactly `digits` decimal digits as a number.
    private mutating func readNumber(digits: Int) -> Int64? {
        var value: Int64 = 0
        for _ in 0..<digits {
            guard let digit = peekDigit() else { return nil }
            value = value * 10 + Int64(digit)
            position += 1
        }
        return value
    }

    /// The next byte's value when it is a decimal digit.
    private func peekDigit() -> UInt8? {
        guard position < bytes.count, (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(bytes[position]) else {
            return nil
        }
        return bytes[position] - UInt8(ascii: "0")
    }

    /// Consumes `character` when it is next.
    private mutating func read(_ character: Unicode.Scalar) -> Bool {
        guard position < bytes.count, bytes[position] == UInt8(ascii: character) else { return false }
        position += 1
        return true
    }

    /// The days in `month` of `year`, leap years included.
    private func daysIn(_ month: Int64, of year: Int64) -> Int64 {
        switch month {
        case 2: return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }
}
