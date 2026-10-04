// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// Where a one-shot read takes its rows from: the server, the cache, or one and then the other.
///
/// Only a list of rows is cached; a count, `head`, ``OfflinePostgrestRequestBuilder/single()`` or
/// another format goes to the server under every strategy but ``cacheOnly(maxAge:)``.
public enum ReadStrategy: Sendable, Hashable {

    /// Asks the server, and fails when it cannot be reached; the answer is cached.
    case networkOnly

    /// Asks the server, and serves the cached rows when it cannot be reached.
    ///
    /// Only a connectivity failure falls back to the cache; a refusal is thrown even with rows
    /// cached. Cached rows older than `maxAge` count as none.
    case networkFirst(maxAge: Duration? = nil)

    /// Serves the cached rows, and asks the server when there are none, or they are older than
    /// `maxAge`, or a write marked them stale.
    case cacheFirst(maxAge: Duration? = nil)

    /// Serves the cached rows and never sends a request; with none it may serve (or none younger
    /// than `maxAge`), the read throws ``OfflineError/Code/cacheMiss``.
    case cacheOnly(maxAge: Duration? = nil)

    /// ``networkFirst(maxAge:)`` with no age limit: the default.
    public static var networkFirst: ReadStrategy { .networkFirst(maxAge: nil) }

    /// ``cacheFirst(maxAge:)`` with no age limit.
    public static var cacheFirst: ReadStrategy { .cacheFirst(maxAge: nil) }

    /// ``cacheOnly(maxAge:)`` with no age limit.
    public static var cacheOnly: ReadStrategy { .cacheOnly(maxAge: nil) }

    /// The strategy as a read description's `strategy` object.
    ///
    /// - Throws: ``OfflineError/Code/invalidRequest`` for a negative `maxAge`.
    func descriptionValue() throws -> OfflineJSON {
        let (kind, maxAge): (String, Duration?) =
            switch self {
            case .networkOnly: ("networkOnly", nil)
            case .networkFirst(let maxAge): ("networkFirst", maxAge)
            case .cacheFirst(let maxAge): ("cacheFirst", maxAge)
            case .cacheOnly(let maxAge): ("cacheOnly", maxAge)
            }
        guard let maxAge else { return ["kind": .string(kind)] }
        return ["kind": .string(kind), "maxAgeMillis": .int(try Self.milliseconds(in: maxAge))]
    }

    /// `duration` in whole milliseconds, rounded down; past `Int.max` milliseconds counts as `Int.max`.
    static func milliseconds(in duration: Duration) throws -> Int {
        guard duration >= .zero else {
            throw OfflineError(code: .invalidRequest, message: "A maximum age cannot be negative")
        }
        let (seconds, attoseconds) = duration.components
        guard seconds < Int64.max / 1_000 else { return Int.max }
        return Int(seconds * 1_000 + attoseconds / 1_000_000_000_000_000)
    }
}
