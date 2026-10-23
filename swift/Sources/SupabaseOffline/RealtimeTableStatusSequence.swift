// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// Where each of the client's ``SupabaseOfflineClient/Config/realtimeTables`` stands: ``value``
/// now, without waiting, and, iterated, the statuses now and then each change.
///
/// Each iteration is a subscription of its own; leaving the loop, throwing out of it or canceling
/// its task ends it. Only the newest statuses wait for a slow reader. Iterating keeps the client
/// open; closing the client ends the iteration normally.
public struct RealtimeTableStatusSequence: AsyncSequence, Sendable {

    /// Each named table's status, keyed by the table as the configuration names it.
    public typealias Element = [RealtimeTable: RealtimeStatus]

    /// The subscription each iteration starts.
    let live: SubscriptionSequence<Element>

    /// Reads the statuses now.
    let current: @Sendable () -> Element

    /// The statuses now, read without waiting; it never fails.
    ///
    /// Before the client has opened its local database (it opens in the background as it is
    /// created) every named table reads ``RealtimeStatus/off``; after
    /// ``SupabaseOfflineClient/close()`` every one does as well. With no table named it is empty.
    public var value: [RealtimeTable: RealtimeStatus] {
        current()
    }

    /// Subscribes: a new iteration of the statuses.
    public func makeAsyncIterator() -> Iterator {
        Iterator(base: live.makeAsyncIterator())
    }

    /// One iteration's subscription; releasing it ends the subscription.
    public struct Iterator: AsyncIteratorProtocol {

        /// The subscription's stream.
        var base: AsyncThrowingStream<Element, any Error>.AsyncIterator

        /// The next statuses, or `nil` once the client has closed.
        ///
        /// - Throws: ``OfflineError``: `clientClosed` for an iteration begun after the client closed.
        public mutating func next() async throws -> Element? {
            try await base.next()
        }
    }
}
