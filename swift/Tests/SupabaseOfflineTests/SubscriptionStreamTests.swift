// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import Testing
@testable import SupabaseOffline

/// A subscription becomes a stream: its values, its end, and what stops it.
struct SubscriptionStreamTests {

    @Test func deliversValuesInOrderThenFinishes() async throws {
        // Given
        let handle = RecordingCallHandle()
        let stream = subscriptionStream(keepingNewest: 10) { (yield: @escaping @Sendable (Int) -> Void, finish) in
            (1...3).forEach(yield)
            finish(nil)
            return handle
        }

        // When
        var received: [Int] = []
        for try await value in stream { received.append(value) }

        // Then
        #expect(received == [1, 2, 3])
        #expect(handle.cancelCount == 0)
    }

    @Test func aFailureEndsTheStreamWithTheError() async {
        // Given
        let stream = subscriptionStream(keepingNewest: 10) { (yield: @escaping @Sendable (Int) -> Void, finish) in
            yield(1)
            yield(2)
            finish(OfflineError(code: .network, message: "lost the connection"))
            return RecordingCallHandle()
        }

        // When
        var received: [Int] = []
        var failure: (any Error)?
        do {
            for try await value in stream { received.append(value) }
        } catch {
            failure = error
        }

        // Then
        #expect(received == [1, 2])
        #expect((failure as? OfflineError)?.code == .network)
    }

    @Test func aSlowReaderSeesTheNewestValueAndNothingPilesUp() async throws {
        // Given a subscription that emits 2,000 values as fast as it can
        let stream = subscriptionStream { (yield: @escaping @Sendable (Int) -> Void, finish) in
            DispatchQueue.global().async {
                (0..<2_000).forEach(yield)
                finish(nil)
            }
            return RecordingCallHandle()
        }

        // When the reader takes 5 ms over each value
        var received: [Int] = []
        for try await value in stream {
            received.append(value)
            try await Task.sleep(for: .milliseconds(5))
        }

        // Then it skipped the stale ones and still saw the last
        #expect(received.count < 2_000)
        #expect(received.last == 1_999)
        #expect(received == received.sorted())
    }

    @Test func breakingOutCancelsTheSubscriptionOnceWhileTheSequenceIsStillHeld() async throws {
        // Given a subscription that emits until it is cancelled
        let handle = RecordingCallHandle()
        let sequence = SubscriptionSequence { endlessCounter(handle: handle) }

        // When
        var seen = 0
        for try await _ in sequence {
            seen += 1
            if seen == 3 { break }
        }

        // Then
        #expect(await eventually { handle.cancelCount > 0 })
        try await Task.sleep(for: .milliseconds(50))
        #expect(handle.cancelCount == 1)
        withExtendedLifetime(sequence) {}
    }

    @Test func cancellingTheReadingTaskCancelsTheSubscription() async throws {
        // Given
        let handle = RecordingCallHandle()
        let reading = RecordingList<Int>()
        let task = Task {
            for try await value in SubscriptionSequence(subscribe: { endlessCounter(handle: handle) }) {
                reading.append(value)
            }
        }
        #expect(await eventually { !reading.values.isEmpty })

        // When
        task.cancel()
        _ = await task.result

        // Then
        #expect(await eventually { handle.cancelCount == 1 })
    }

    @Test func aSubscriptionThatEndsByItselfIsNotCancelled() async throws {
        // Given
        let handle = RecordingCallHandle()
        let stream = subscriptionStream { (yield: @escaping @Sendable (Int) -> Void, finish) in
            yield(1)
            finish(nil)
            return handle
        }

        // When
        for try await _ in stream {}

        // Then
        try await Task.sleep(for: .milliseconds(20))
        #expect(handle.cancelCount == 0)
    }

    @Test func aSubscriptionStartedAfterTheReaderStoppedIsCancelledAtOnce() async throws {
        // Given a subscription whose start has begun and waits for a gate
        let gate = Gate()
        let handle = RecordingCallHandle()
        let starts = RecordingList<Bool>()
        let task = Task {
            let stream = subscriptionStream { (_: @escaping @Sendable (Int) -> Void, _) in
                starts.append(true)
                await gate.wait()
                return handle
            }
            for try await _ in stream {}
        }
        #expect(await eventually { starts.values == [true] })

        // When the reader stops, and only then the subscription's handle comes back
        task.cancel()
        _ = await task.result
        await gate.open()

        // Then
        #expect(await eventually { handle.cancelCount == 1 })
        try await Task.sleep(for: .milliseconds(20))
        #expect(handle.cancelCount == 1)
    }

    @Test func aStartThatThrowsEndsTheStreamWithItsError() async {
        // Given
        let stream = subscriptionStream { (_: @escaping @Sendable (Int) -> Void, _) -> any CoreCallHandle in
            throw OfflineError(code: .configuration, message: "could not open")
        }

        // When
        var failure: (any Error)?
        do {
            for try await _ in stream {}
        } catch {
            failure = error
        }

        // Then
        #expect((failure as? OfflineError)?.code == .configuration)
    }
}

/// A stream of 0, 1, 2, … every millisecond until `handle` is cancelled.
private func endlessCounter(handle: RecordingCallHandle) -> AsyncThrowingStream<Int, any Error> {
    subscriptionStream { yield, _ in
        DispatchQueue.global().async {
            var next = 0
            while handle.cancelCount == 0 {
                yield(next)
                next += 1
                Thread.sleep(forTimeInterval: 0.001)
            }
        }
        return handle
    }
}
