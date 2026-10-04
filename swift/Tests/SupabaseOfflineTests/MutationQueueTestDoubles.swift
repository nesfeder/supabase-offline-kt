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

/// A connectivity signal a test drives, for ``SupabaseOfflineClient/Config/networkAvailability``.
struct FakeNetworkAvailability: Sendable {

    /// The stream the client reads.
    let stream: AsyncStream<Bool>

    /// Where the test's reports go.
    private let continuation: AsyncStream<Bool>.Continuation

    init() {
        (stream, continuation) = AsyncStream.makeStream(of: Bool.self)
    }

    /// Reports the device online (`true`) or offline.
    func report(_ isAvailable: Bool) {
        continuation.yield(isAvailable)
    }

    /// Ends the signal.
    func finish() {
        continuation.finish()
    }
}

extension FakeServer.Response {

    /// PostgREST refusing a duplicate key, as it answers a replayed insert whose row exists.
    static let duplicateKey = FakeServer.Response.json(
        #"{"code":"23505","message":"duplicate key value violates unique constraint","details":"Key (id)=(3) already exists.","hint":null}"#,
        status: 409
    )
}
