// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// How many of the current user's writes wait, as the mutation queue changes: the count now, then a
/// new count each time it differs from the last.
///
/// Each iteration is a subscription of its own; leaving the loop, throwing out of it or canceling
/// its task ends it. Iterating keeps the client open; closing the client ends the iteration
/// normally. ``SupabaseOfflineClient/pendingMutationCount(table:)`` reads the count once.
public struct PendingMutationCountSequence: AsyncSequence, Sendable {

    /// A count of queued writes.
    public typealias Element = Int

    /// The subscription each iteration starts.
    let live: SubscriptionSequence<Element>

    /// Subscribes: a new iteration of the count.
    public func makeAsyncIterator() -> Iterator {
        Iterator(base: live.makeAsyncIterator())
    }

    /// One iteration's subscription; releasing it ends the subscription.
    public struct Iterator: AsyncIteratorProtocol {

        /// The subscription's stream.
        var base: AsyncThrowingStream<Element, any Error>.AsyncIterator

        /// The next count, or `nil` once the client has closed.
        ///
        /// - Throws: ``OfflineError``: `clientClosed` for an iteration begun after the client closed.
        public mutating func next() async throws -> Element? {
            try await base.next()
        }
    }
}
