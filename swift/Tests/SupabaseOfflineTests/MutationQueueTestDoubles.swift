// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import os
@testable import SupabaseOffline

/// A live sequence a test iterates in a task of its own, recording every element, until it ends.
final class LiveRecording<Element: Sendable>: Sendable {

    /// The elements so far, in order.
    let values = RecordingList<Element>()

    /// The iteration; its result says how the sequence ended.
    private let iteration: OSAllocatedUnfairLock<Task<Void, any Error>?> = OSAllocatedUnfairLock(initialState: nil)

    /// Starts iterating `sequence`.
    init<Sequence: AsyncSequence & Sendable>(_ sequence: Sequence) where Sequence.Element == Element {
        let values = self.values
        let task = Task {
            for try await element in sequence {
                values.append(element)
            }
        }
        iteration.withLock { $0 = task }
    }

    /// The newest element.
    var last: Element? {
        values.values.last
    }

    /// Waits for the iteration to end: `nil` when the sequence finished normally, otherwise the
    /// error it threw.
    func endingError() async -> (any Error)? {
        guard let task = iteration.withLock({ $0 }) else { return nil }
        if case .failure(let error) = await task.result { return error }
        return nil
    }

    /// Leaves the loop.
    func stop() {
        iteration.withLock { $0 }?.cancel()
    }
}

/// A connectivity signal a test drives, for ``SupabaseOfflineClient/Config/networkAvailability``:
/// each client that opens gets a stream of its own, and a report goes to the newest one.
final class FakeNetworkAvailability: Sendable {

    /// Each stream made so far, oldest first, and whether the client has finished reading it.
    private let streams = OSAllocatedUnfairLock<[(continuation: AsyncStream<Bool>.Continuation, ended: Bool)]>(
        initialState: []
    )

    /// What a configuration is given: makes a new stream each time a client opens.
    var makeStream: @Sendable () -> AsyncStream<Bool> {
        { [self] in newStream() }
    }

    /// How many streams the clients have asked for.
    var streamCount: Int {
        streams.withLock { $0.count }
    }

    /// How many of those streams have ended: their client closed, or the test finished them.
    var endedStreamCount: Int {
        streams.withLock { $0.filter(\.ended).count }
    }

    /// Reports the device online (`true`) or offline to the newest client.
    func report(_ isAvailable: Bool) {
        streams.withLock { $0.last }?.continuation.yield(isAvailable)
    }

    /// Ends every stream, as an app's own monitor might stop.
    func finish() {
        streams.withLock { $0.map(\.continuation) }.forEach { $0.finish() }
    }

    /// A new stream, recorded with its end.
    private func newStream() -> AsyncStream<Bool> {
        let (stream, continuation) = AsyncStream.makeStream(of: Bool.self)
        let recorded = streams
        let index = recorded.withLock { made in
            made.append((continuation, false))
            return made.count - 1
        }
        continuation.onTermination = { _ in recorded.withLock { $0[index].ended = true } }
        return stream
    }
}

extension FakeServer.Response {

    /// PostgREST refusing a duplicate key, as it answers a replayed insert whose row exists.
    static let duplicateKey = FakeServer.Response.json(
        #"{"code":"23505","message":"duplicate key value violates unique constraint","details":"Key (id)=(3) already exists.","hint":null}"#,
        status: 409
    )
}
