// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// A live read: filters and transforms, then iterate it for its ``QuerySnapshot`` values.
///
/// The first snapshot comes as the ``SequenceReadStrategy`` says; after it, a new one comes whenever a
/// local write, a sync, a refresh or a server record changes the rows or their state. Only the
/// newest snapshot waits for a slow reader. Each iteration is a subscription of its own: leaving
/// the loop, throwing out of it or canceling its task ends it. Iterating keeps the client open;
/// closing the client ends the iteration normally. A snapshot whose rows do not decode as `Value`
/// ends the iteration with ``OfflineError/Code/decoding``.
public struct QuerySnapshotSequence<Value: Decodable & Sendable>: AsyncSequence, OfflinePostgrestRequestBuilder {

    /// One state of the read.
    public typealias Element = QuerySnapshot<Value>

    /// The table read.
    let target: OfflinePostgrestQueryBuilder

    /// The columns asked for.
    let columns: String

    /// Where the first rows come from.
    let readStrategy: SequenceReadStrategy

    /// The filters, modifiers and response shape so far.
    var shape: RequestShape

    /// Hears how each subscription in the core ended, for tests.
    var onSubscriptionEnd: (@Sendable (String?) -> Void)?

    init(
        target: OfflinePostgrestQueryBuilder,
        columns: String,
        readStrategy: SequenceReadStrategy,
        shape: RequestShape,
        onSubscriptionEnd: (@Sendable (String?) -> Void)? = nil
    ) {
        self.target = target
        self.columns = columns
        self.readStrategy = readStrategy
        self.shape = shape
        self.onSubscriptionEnd = onSubscriptionEnd
    }

    /// This read with `change` made to its request.
    public func applying(_ change: OfflineQueryChange) -> QuerySnapshotSequence<Value> {
        var changed = self
        change.apply(&changed.shape)
        return changed
    }

    /// Subscribes: a new iteration of the read.
    public func makeAsyncIterator() -> Iterator {
        do {
            let snapshots = target.client.querySnapshots(
                description: try descriptionText(),
                decode: BodyDecoding.decoding(Value.self),
                onSubscriptionEnd: onSubscriptionEnd
            )
            return Iterator(base: snapshots.makeAsyncIterator())
        } catch {
            let refused = AsyncThrowingStream<Element, any Error> { $0.finish(throwing: error) }
            return Iterator(base: refused.makeAsyncIterator())
        }
    }

    /// The read's description as the core reads it.
    func descriptionText() throws -> String {
        var fields = try shape.descriptionFields().merging(target.targetFields) { _, target in target }
        fields["columns"] = .string(columns)
        fields["strategy"] = readStrategy.descriptionValue
        return try RequestShape.descriptionText(fields)
    }

    /// One iteration's subscription; releasing it ends the subscription.
    public struct Iterator: AsyncIteratorProtocol {

        /// The subscription's stream.
        var base: AsyncThrowingStream<Element, any Error>.AsyncIterator

        /// The next snapshot, or `nil` once the read has ended (the client closed, or a snapshot
        /// the server answered once for a query the cache never holds).
        ///
        /// - Throws: ``OfflineError``: `cacheMiss` under ``SequenceReadStrategy/cacheOnly`` for a
        ///   shape the cache never holds; under ``SequenceReadStrategy/networkFirstThenCache``,
        ///   `connectivity` with no connection or `networkDisabled` with the network turned off when
        ///   nothing is cached, and the server's refusal; `decoding` or `invalidRequest`. Canceling
        ///   the task ends the iteration.
        public mutating func next() async throws -> Element? {
            try await base.next()
        }
    }
}
