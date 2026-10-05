// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineCore
@testable import SupabaseOffline

/// A core call handle that counts its cancellations.
final class RecordingCallHandle: CoreCallHandle, @unchecked Sendable {

    /// Guards `count`.
    private let lock = NSLock()

    /// How many times `cancel()` ran.
    private var count = 0

    /// How many times the call was canceled.
    var cancelCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    func cancel() {
        lock.lock()
        count += 1
        lock.unlock()
    }
}

/// A core call a test answers by hand: it keeps the completion `awaitCompletion` hands it.
final class FakeCoreCall<Value: Sendable>: @unchecked Sendable {

    /// Guards the properties below.
    private let lock = NSLock()

    /// The completion, once the call was sent.
    private var completion: (@Sendable (Result<Value, any Error>) -> Void)?

    /// Whether `start` ran.
    private var sent = false

    /// Whether the call was sent.
    var wasSent: Bool {
        lock.lock()
        defer { lock.unlock() }
        return sent
    }

    /// The handle the call hands back.
    let handle = RecordingCallHandle()

    /// Sends the call: keeps `complete` and returns the handle.
    func start(_ complete: @escaping @Sendable (Result<Value, any Error>) -> Void) -> any CoreCallHandle {
        lock.lock()
        completion = complete
        sent = true
        lock.unlock()
        return handle
    }

    /// Answers the call with `result`, as the core would, from whichever thread calls this.
    func answer(_ result: Result<Value, any Error>) {
        lock.lock()
        let completion = self.completion
        lock.unlock()
        completion?(result)
    }
}

/// A gate a test opens once; everything waiting on it resumes then.
actor Gate {

    /// Whether the gate is open.
    private var isOpen = false

    /// The tasks waiting for it to open.
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// Returns once the gate is open.
    func wait() async {
        guard !isOpen else { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    /// Opens the gate.
    func open() {
        isOpen = true
        waiters.forEach { $0.resume() }
        waiters.removeAll()
    }
}

/// Values a test collects from any thread.
final class RecordingList<Element: Sendable>: @unchecked Sendable {

    /// Guards `elements`.
    private let lock = NSLock()

    /// The values recorded so far.
    private var elements: [Element] = []

    /// The values recorded so far, in order.
    var values: [Element] {
        lock.lock()
        defer { lock.unlock() }
        return elements
    }

    /// Records `element`.
    func append(_ element: Element) {
        lock.lock()
        elements.append(element)
        lock.unlock()
    }
}

/// A weak reference a test reads from any thread, to see when an object is freed.
final class WeakReference<Object: AnyObject>: @unchecked Sendable {

    /// Guards `object`.
    private let lock = NSLock()

    /// The object, until nothing else holds it.
    private weak var object: Object?

    init(_ object: Object) {
        self.object = object
    }

    /// The object, or `nil` once it is freed.
    var value: Object? {
        lock.lock()
        defer { lock.unlock() }
        return object
    }
}

/// Waits until `condition` holds, polling, for at most `limit`; the condition's last value.
func eventually(within limit: Duration = .seconds(5), _ condition: @Sendable () -> Bool) async -> Bool {
    let clock = ContinuousClock()
    let deadline = clock.now + limit
    while !condition() {
        guard clock.now < deadline else { return false }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return true
}

/// Whether the current thread is the main thread, from any context.
func isOnMainThread() -> Bool {
    pthread_main_np() != 0
}

extension SupabaseOfflineClient {

    /// Sends a raw read `description` to the core and returns the response body.
    func rawSelect(_ description: String) async throws -> String {
        try await connection.call { core, complete in
            core.select(query: description) { result, failure in
                complete(coreResult(result, failure) { try requiredValue($0).data })
            }
        }
    }

    /// The core's count of queued writes, which needs only the local database.
    func rawPendingMutationCount() async throws -> Int {
        try await connection.call { core, complete in
            core.pendingMutationCount(table: nil) { count, failure in
                complete(coreResult(count, failure) { try requiredValue($0).intValue })
            }
        }
    }

    /// The core's count of queued writes, as a stream.
    func pendingMutationCounts(recordingEndsIn ends: RecordingList<String>? = nil) -> SubscriptionSequence<Int> {
        liveSequence { core, yield, finish in
            core.observePendingMutationCount(
                table: nil,
                onCount: { count in yield(count.intValue) },
                onCompletion: { failure in
                    ends?.append(failure?.kind.name ?? "nil")
                    finish(failure.map { OfflineError.thrown(for: $0) })
                }
            )
        }
    }
}

/// A server for a test that sends no request: any request fails as if offline.
final class FakeSilentServer: NSObject, BridgeFakeServer {

    func respond(request: BridgeHttpRequest, responder: BridgeHttpResponder) {
        responder.failWithoutConnection(message: "no request was expected")
    }
}

/// A read description for `table` with the one-shot strategy `strategy`.
func readDescription(table: String = "todos", strategy: String) -> String {
    #"{"v":1,"table":"\#(table)","strategy":{"kind":"\#(strategy)"}}"#
}
