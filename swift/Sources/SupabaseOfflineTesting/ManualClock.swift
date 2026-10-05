// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
import SupabaseOfflineCore
import os

/// The wall clock an in-memory client reads: when it fetched a result, queued a write, and whether
/// a cached result is older than a read allows. It moves only when the test moves it.
public final class ManualClock: Sendable {

    /// The time the client sees.
    private let current: OSAllocatedUnfairLock<Date>

    /// A clock that reads `now` until it is moved.
    public init(now: Date = Date()) {
        current = OSAllocatedUnfairLock(initialState: now)
    }

    /// The time the client sees.
    public var now: Date {
        current.withLock { $0 }
    }

    /// Moves the clock forward by `duration`, or back by a negative one.
    public func advance(by duration: Duration) {
        let (seconds, attoseconds) = duration.components
        let interval = Double(seconds) + Double(attoseconds) / 1e18
        current.withLock { $0 += interval }
    }

    /// Sets the clock to `date`.
    public func set(to date: Date) {
        current.withLock { $0 = date }
    }
}

/// Hands a ``ManualClock``'s time to the core.
final class ManualClockRelay: NSObject, BridgeClock, Sendable {

    /// The test's clock.
    private let clock: ManualClock

    init(_ clock: ManualClock) {
        self.clock = clock
    }

    /// The clock's time in whole milliseconds since 1970.
    func nowEpochMillis() -> Int64 {
        Int64((clock.now.timeIntervalSince1970 * 1_000).rounded(.down))
    }
}
