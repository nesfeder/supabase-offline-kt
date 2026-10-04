// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation

/// Reads the URLs the core answers with as text: Storage's public, authenticated, signed and
/// upload URLs, which hold the object's name as it was written, spaces and all.
///
/// Every OS reads them alike: a URL must be valid as written (iOS 16's rule, and iOS 17's with
/// encoding turned off), or valid once the characters it cannot hold are percent-encoded.
enum CoreURL {

    /// `text` as a URL: as written when that is valid, otherwise with every character a URL cannot
    /// hold percent-encoded and the escapes already in it kept.
    static func parse(_ text: String) throws -> URL {
        guard let url = strictlyParsed(text) ?? strictlyParsed(encodingInvalidCharacters(in: text)) else {
            throw OfflineError(code: .decoding, message: "A Storage URL could not be read")
        }
        return url
    }

    /// `text` as a URL only when it is valid as written: nothing is encoded for it, on any OS.
    static func strictlyParsed(_ text: String) -> URL? {
        if #available(iOS 17, *) {
            return URL(string: text, encodingInvalidCharacters: false)
        }
        return URL(string: text)
    }

    /// `text` with every byte after its scheme and host that a URL cannot hold percent-encoded as
    /// UTF-8; a `%` followed by two hex digits is an escape already, and is kept.
    static func encodingInvalidCharacters(in text: String) -> String {
        let pathStart = startOfPath(in: text)
        let tail = Array(text[pathStart...].utf8)
        var encoded = Array(text[..<pathStart].utf8)
        var index = 0
        while index < tail.count {
            let byte = tail[index]
            if byte == UInt8(ascii: "%"), index + 2 < tail.count, isHexDigit(tail[index + 1]), isHexDigit(tail[index + 2]) {
                encoded.append(contentsOf: tail[index...index + 2])
                index += 3
                continue
            }
            if allowedInURL.contains(byte) {
                encoded.append(byte)
            } else {
                encoded.append(contentsOf: [UInt8(ascii: "%"), hexDigits[Int(byte >> 4)], hexDigits[Int(byte & 0x0F)]])
            }
            index += 1
        }
        return String(decoding: encoded, as: UTF8.self)
    }

    /// Where the part after the scheme and host begins: the first `/`, `?` or `#` after `://`.
    private static func startOfPath(in text: String) -> String.Index {
        guard let scheme = text.range(of: "://") else { return text.startIndex }
        return text[scheme.upperBound...].firstIndex { "/?#".contains($0) } ?? text.endIndex
    }

    /// Whether `byte` is an ASCII hex digit.
    private static func isHexDigit(_ byte: UInt8) -> Bool {
        hexDigits.contains(byte) || (UInt8(ascii: "a")...UInt8(ascii: "f")).contains(byte)
    }

    /// The upper-case hex digits, by value.
    private static let hexDigits = Array("0123456789ABCDEF".utf8)

    /// The bytes a URL's path, query and fragment may hold as they are (RFC 3986's unreserved
    /// characters, sub-delimiters, `:`, `@`, `/`, `?` and `#`).
    private static let allowedInURL = Set(
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/?#".utf8
    )
}
