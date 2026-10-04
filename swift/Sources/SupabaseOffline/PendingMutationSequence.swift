// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// The current user's queued writes as the mutation queue changes: the list now, then a new list
/// after each change, in the order the writes will be sent.
///
/// Each iteration is a subscription of its own; leaving the loop, throwing out of it or cancelling
/// its task ends it. Only the newest list waits for a slow reader. Iterating keeps the client open;
/// closing the client ends the iteration normally. ``first()`` reads the list once without
/// subscribing.
public struct PendingMutationSequence: AsyncSequence, Sendable {

    /// The queued writes, in the order they will be sent.
    public typealias Element = [PendingMutation]

    /// The subscription each iteration starts.
    let live: SubscriptionSequence<Element>

    /// Reads the list once.
    let current: @Sendable () async throws -> Element

    /// Subscribes: a new iteration of the queue.
    public func makeAsyncIterator() -> Iterator {
        Iterator(base: live.makeAsyncIterator())
    }

    /// The list as it is now, read once, as the sequence's first element would be.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func first() async throws -> Element {
        try await current()
    }

    /// One iteration's subscription; releasing it ends the subscription.
    public struct Iterator: AsyncIteratorProtocol {

        /// The subscription's stream.
        var base: AsyncThrowingStream<Element, any Error>.AsyncIterator

        /// The next list, or `nil` once the client has closed.
        ///
        /// - Throws: ``OfflineError``: `clientClosed` for an iteration begun after the client
        ///   closed, `decoding` for an entry the package cannot read.
        public mutating func next() async throws -> Element? {
            try await base.next()
        }
    }
}
