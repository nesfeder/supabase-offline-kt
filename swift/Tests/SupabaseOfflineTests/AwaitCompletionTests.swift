// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import Testing
@testable import SupabaseOffline

/// A completion becomes an `async` result, and cancellation before, during and after the call.
struct AwaitCompletionTests {

    @Test func returnsTheAnswer() async throws {
        // Given
        let call = FakeCoreCall<Int>()

        // When
        let value = try await awaitCompletion { complete in
            let handle = call.start(complete)
            DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(10)) { call.answer(.success(42)) }
            return handle
        }

        // Then
        #expect(value == 42)
        #expect(call.handle.cancelCount == 0)
    }

    @Test func throwsTheFailure() async {
        // Given
        let call = FakeCoreCall<Int>()

        // When
        let result = await Task {
            try await awaitCompletion { complete in
                let handle = call.start(complete)
                call.answer(.failure(OfflineError(code: .network, message: "offline")))
                return handle
            }
        }.result

        // Then
        #expect(throws: OfflineError.self) { try result.get() }
        #expect(call.handle.cancelCount == 0)
    }

    @MainActor
    @Test func anAnswerGivenBeforeTheHandleReturnsResumesOnceOnTheMainActor() async throws {
        // Given a call that answers on the caller's thread, as one on a closed client may
        let call = FakeCoreCall<Int>()

        // When
        let value = try await awaitCompletion { complete in
            let handle = call.start(complete)
            call.answer(.success(5))
            return handle
        }

        // Then
        #expect(value == 5)
        #expect(call.handle.cancelCount == 0)
        MainActor.assertIsolated()
    }

    @Test func cancelledBeforeTheCallIsSentNeverSendsIt() async {
        // Given
        let call = FakeCoreCall<Int>()

        // When
        let started = ContinuousClock.now
        let result = await Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await awaitCompletion { call.start($0) }
        }.result

        // Then
        #expect(throws: CancellationError.self) { try result.get() }
        #expect(!call.wasSent)
        #expect(call.handle.cancelCount == 0)
        #expect(ContinuousClock.now - started < .seconds(1))
    }

    @Test func cancelledWhileRunningThrowsAtOnceAndCancelsTheCallOnce() async throws {
        // Given a call the core never answers
        let call = FakeCoreCall<Int>()
        let task = Task { try await awaitCompletion { call.start($0) } }
        #expect(await eventually { call.wasSent })

        // When
        let cancelled = ContinuousClock.now
        task.cancel()
        let result = await task.result

        // Then
        #expect(throws: CancellationError.self) { try result.get() }
        #expect(ContinuousClock.now - cancelled < .seconds(1))
        #expect(call.handle.cancelCount == 1)

        // When the core answers late, then the answer is dropped
        call.answer(.success(7))
        task.cancel()
        #expect(call.handle.cancelCount == 1)
    }

    @Test func cancelledWhileTheCallIsBeingSentCancelsItOnceItIsSent() async {
        // Given
        let call = FakeCoreCall<Int>()

        // When the task is cancelled after the call starts but before its handle comes back
        let result = await Task {
            try await awaitCompletion { complete in
                let handle = call.start(complete)
                withUnsafeCurrentTask { $0?.cancel() }
                return handle
            }
        }.result

        // Then
        #expect(throws: CancellationError.self) { try result.get() }
        #expect(call.handle.cancelCount == 1)
    }

    @Test func cancelledAfterTheAnswerKeepsTheAnswer() async throws {
        // Given
        let call = FakeCoreCall<Int>()

        // When the core answers, and only then the task is cancelled
        let value = try await Task {
            try await awaitCompletion { complete in
                let handle = call.start(complete)
                call.answer(.success(7))
                withUnsafeCurrentTask { $0?.cancel() }
                return handle
            }
        }.value

        // Then
        #expect(value == 7)
        #expect(call.handle.cancelCount == 0)
    }

    @Test func anAnswerRacingACancellationResumesOnceAndCancelsAtMostOnce() async {
        for _ in 0..<500 {
            // Given
            let call = FakeCoreCall<Int>()
            let task = Task {
                try await awaitCompletion { complete in
                    let handle = call.start(complete)
                    DispatchQueue.global().async { call.answer(.success(1)) }
                    return handle
                }
            }

            // When
            task.cancel()
            let result = await task.result

            // Then
            switch result {
            case .success(let value): #expect(value == 1)
            case .failure(let error): #expect(error is CancellationError)
            }
            #expect(call.handle.cancelCount <= 1)
        }
    }
}
