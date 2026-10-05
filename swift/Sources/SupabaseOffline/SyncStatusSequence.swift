// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// The client's ``SyncStatus``: ``value`` now, without waiting, and, iterated, the status now and
/// then each change.
///
/// Each iteration is a subscription of its own; leaving the loop, throwing out of it or cancelling
/// its task ends it. Only the newest status waits for a slow reader. Iterating keeps the client
/// open; closing the client ends the iteration normally.
public struct SyncStatusSequence: AsyncSequence, Sendable {

    /// One status.
    public typealias Element = SyncStatus

    /// The subscription each iteration starts.
    let live: SubscriptionSequence<Element>

    /// Reads the status now.
    let current: @Sendable () -> Element

    /// The status now, read without waiting; it never fails.
    ///
    /// Before the client has opened its local database (it opens in the background as it is
    /// created) this is the status of a client that has done nothing yet; after ``SupabaseOfflineClient/close()``
    /// it is the last status.
    public var value: SyncStatus {
        current()
    }

    /// Subscribes: a new iteration of the status.
    public func makeAsyncIterator() -> Iterator {
        Iterator(base: live.makeAsyncIterator())
    }

    /// One iteration's subscription; releasing it ends the subscription.
    public struct Iterator: AsyncIteratorProtocol {

        /// The subscription's stream.
        var base: AsyncThrowingStream<Element, any Error>.AsyncIterator

        /// The next status, or `nil` once the client has closed.
        ///
        /// - Throws: ``OfflineError``: `clientClosed` for an iteration begun after the client closed.
        public mutating func next() async throws -> Element? {
            try await base.next()
        }
    }
}
