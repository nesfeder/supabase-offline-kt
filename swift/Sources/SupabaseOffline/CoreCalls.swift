// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineCore
import os

// The machinery every operation goes through: the core's completion blocks become `async` calls,
// its subscriptions become streams, and the app's token closure and the unified log are handed to
// it. Every core callback arrives on a background thread, never the main one.

// MARK: - The opened core

/// The core client, safe to call from any thread; held this way so Swift lets it cross tasks.
struct CoreClientReference: @unchecked Sendable {

    /// The client every call goes to.
    let client: BridgeClient
}

/// Opens the core client once, off the caller's thread, and hands it to every call.
///
/// Opening claims the data directory and opens the database, so it never runs on the thread that
/// creates the ``SupabaseOfflineClient``; a call made meanwhile waits for it.
final class CoreConnection: Sendable {

    /// The open, under way or finished; its failure is every call's error.
    private let opening: Task<CoreClientReference, any Error>

    /// Starts `open` on a background queue.
    init(open: @escaping @Sendable () throws -> CoreClientReference) {
        opening = Task.detached(priority: .userInitiated) {
            try await withCheckedThrowingContinuation { continuation in
                DispatchQueue.global(qos: .userInitiated).async {
                    continuation.resume(with: Result { try open() })
                }
            }
        }
    }

    /// The opened client, or the failure that kept it from opening.
    func openedClient() async throws -> CoreClientReference {
        try await opening.value
    }

    /// One call on the opened client: `start` sends it and passes on its single answer.
    ///
    /// `start` converts the core's value inside the completion, off the caller's actor. It is
    /// `@Sendable`, so no closure written in it inherits an actor: the core calls them on its own
    /// threads, where an actor-isolated closure would trap.
    func call<Value: Sendable>(
        _ start: @Sendable (BridgeClient, _ complete: @escaping @Sendable (Result<Value, any Error>) -> Void) -> BridgeCancellable
    ) async throws -> Value {
        let reference = try await openedClient()
        return try await awaitCompletion { complete in start(reference.client, complete) }
    }

    /// One subscription on the opened client, as a stream that keeps only its newest unread value
    /// and keeps `owner` alive until it ends. Reached only through ``SupabaseOfflineClient/liveSequence(_:)``.
    fileprivate func subscription<Element: Sendable>(
        retaining owner: any Sendable,
        _ start: @escaping @Sendable (
            BridgeClient,
            _ yield: @escaping @Sendable (Element) -> Void,
            _ finish: @escaping @Sendable ((any Error)?) -> Void
        ) -> BridgeCancellable
    ) -> AsyncThrowingStream<Element, any Error> {
        subscriptionStream(retaining: owner) { yield, finish in
            start(try await self.openedClient().client, yield, finish)
        }
    }

    /// Closes the client: waits for calls not yet sent, ends the rest and every live stream.
    ///
    /// A client that never opened has nothing to close; its failure was every call's error.
    func close() async throws {
        guard let reference = try? await openedClient() else { return }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
            reference.client.close { @Sendable failure in
                continuation.resume(with: coreResult(failure))
            }
        }
    }

    /// Closes the client without waiting, when nothing can await the close any more.
    func closeInBackground() {
        let opening = self.opening
        Task.detached {
            guard let reference = try? await opening.value else { return }
            reference.client.close { @Sendable failure in
                guard let failure else { return }
                packageLogger.warning("Closing the client failed: \(redactingRuntimeNames(failure.message), privacy: .public)")
            }
        }
    }
}

extension SupabaseOfflineClient {

    /// A live read of the core in the one shape every public sequence is built on: each iterator
    /// is a subscription of its own, ended when its loop is left however it is left, and the client
    /// stays open while any iterator is in use.
    ///
    /// `start` subscribes and converts each core value inside its value block, off the caller's actor.
    func liveSequence<Element: Sendable>(
        _ start: @escaping @Sendable (
            BridgeClient,
            _ yield: @escaping @Sendable (Element) -> Void,
            _ finish: @escaping @Sendable ((any Error)?) -> Void
        ) -> BridgeCancellable
    ) -> SubscriptionSequence<Element> {
        SubscriptionSequence { [self] in
            connection.subscription(retaining: self, start)
        }
    }
}

// MARK: - Completions as async calls

/// A handle that cancels one call or subscription in the core.
protocol CoreCallHandle {

    /// Asks the core to stop; safe from any thread and more than once.
    func cancel()
}

extension BridgeCancellable: CoreCallHandle {}

/// Awaits one call that `start` sends, returning its handle and calling `complete` once, from any
/// thread, before or after it returns.
///
/// Cancelling the awaiting task throws `CancellationError` at once and cancels the call through
/// its handle exactly once; a completion that arrives after that is dropped. A task cancelled
/// before the call is sent never sends it.
func awaitCompletion<Value: Sendable>(
    _ start: @Sendable (_ complete: @escaping @Sendable (Result<Value, any Error>) -> Void) -> any CoreCallHandle
) async throws -> Value {
    let call = PendingCall<Value>()
    return try await withTaskCancellationHandler {
        try await withCheckedThrowingContinuation { continuation in
            guard call.wait(with: continuation) else { return }
            call.attach(start { result in call.complete(with: result) })
        }
    } onCancel: {
        call.cancel()
    }
}

/// One awaited call: its continuation, resumed once, and its handle, cancelled at most once.
private final class PendingCall<Value: Sendable>: @unchecked Sendable {

    /// Guards every property below.
    private let lock = NSLock()

    /// The awaiting task, until it is resumed.
    private var continuation: CheckedContinuation<Value, any Error>?

    /// The core's handle, from the call's start until it answers or is cancelled.
    private var handle: (any CoreCallHandle)?

    /// Whether the core has answered.
    private var isAnswered = false

    /// Whether the awaiting task was cancelled.
    private var isCancelled = false

    /// Keeps `continuation`; false, after resuming it with `CancellationError`, when the task was
    /// cancelled first, so the call is never sent.
    func wait(with continuation: CheckedContinuation<Value, any Error>) -> Bool {
        lock.lock()
        let cancelledFirst = isCancelled
        if !cancelledFirst { self.continuation = continuation }
        lock.unlock()
        if cancelledFirst { continuation.resume(throwing: CancellationError()) }
        return !cancelledFirst
    }

    /// Keeps the sent call's handle; cancels the call at once when the task was cancelled while it
    /// was being sent, and drops the handle when the call has already answered.
    func attach(_ handle: any CoreCallHandle) {
        lock.lock()
        let cancelsNow = isCancelled && !isAnswered
        if !isCancelled && !isAnswered { self.handle = handle }
        lock.unlock()
        if cancelsNow { handle.cancel() }
    }

    /// Resumes the task with the call's answer; an answer after a cancellation is dropped.
    func complete(with result: Result<Value, any Error>) {
        lock.lock()
        isAnswered = true
        let continuation = self.continuation
        self.continuation = nil
        handle = nil
        lock.unlock()
        continuation?.resume(with: result)
    }

    /// Resumes the task with `CancellationError` and cancels the call, unless it already answered.
    func cancel() {
        lock.lock()
        guard !isCancelled && !isAnswered else {
            isCancelled = true
            lock.unlock()
            return
        }
        isCancelled = true
        let continuation = self.continuation
        let handle = self.handle
        self.continuation = nil
        self.handle = nil
        lock.unlock()
        continuation?.resume(throwing: CancellationError())
        handle?.cancel()
    }
}

/// A core completion's error as the result a Swift caller gets.
func coreResult(_ failure: BridgeError?) -> Result<Void, any Error> {
    failure.map { .failure(OfflineError.thrown(for: $0)) } ?? .success(())
}

/// A core completion's answer as the result a Swift caller gets: the error mapped, or the value
/// converted. `convert` runs on the core's thread, off the caller's actor; what it throws is the
/// result's error.
func coreResult<CoreValue, Value>(
    _ value: CoreValue?,
    _ failure: BridgeError?,
    convert: (CoreValue?) throws -> Value
) -> Result<Value, any Error> {
    if let failure { return .failure(OfflineError.thrown(for: failure)) }
    return Result { try convert(value) }
}

/// `value`, which a completion with no error always carries; a missing one is a defect.
func requiredValue<Value>(_ value: Value?) throws -> Value {
    guard let value else {
        throw OfflineError(code: .unexpected, message: "The call answered with neither a value nor an error")
    }
    return value
}

// MARK: - Subscriptions as streams

/// A stream of the values `start` subscribes to, keeping only the newest `limit` unread ones.
///
/// Every value is a whole state (a snapshot, a count, a list), so a slow reader skips stale ones
/// and nothing piles up. The stream finishes when the subscription ends; when the reader stops
/// first, the subscription is cancelled through its handle exactly once. `owner` lives as long as
/// the stream.
func subscriptionStream<Element: Sendable>(
    keepingNewest limit: Int = 1,
    retaining owner: (any Sendable)? = nil,
    _ start: @escaping @Sendable (
        _ yield: @escaping @Sendable (Element) -> Void,
        _ finish: @escaping @Sendable ((any Error)?) -> Void
    ) async throws -> any CoreCallHandle
) -> AsyncThrowingStream<Element, any Error> {
    precondition(limit > 0, "A subscription keeps at least its newest value")
    let (stream, continuation) = AsyncThrowingStream.makeStream(
        of: Element.self,
        throwing: (any Error).self,
        bufferingPolicy: .bufferingNewest(limit)
    )
    let subscription = LiveSubscription()
    continuation.onTermination = { termination in
        withExtendedLifetime(owner) {}
        if case .cancelled = termination { subscription.cancel() }
    }
    Task.detached {
        guard !subscription.isCancelled else { return }
        do {
            let handle = try await start(
                { value in continuation.yield(value) },
                { failure in continuation.finish(throwing: failure) }
            )
            subscription.attach(handle)
        } catch {
            continuation.finish(throwing: error)
        }
    }
    return stream
}

/// One subscription's handle, cancelled at most once, even when the reader stops before it starts.
private final class LiveSubscription: @unchecked Sendable {

    /// Guards every property below.
    private let lock = NSLock()

    /// The core's handle, once the subscription has started.
    private var handle: (any CoreCallHandle)?

    /// Whether the reader has stopped.
    private var cancelled = false

    /// Whether the reader has stopped, so a subscription not yet started never starts.
    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    /// Keeps the started subscription's handle, or cancels it at once when the reader has stopped.
    func attach(_ handle: any CoreCallHandle) {
        lock.lock()
        let cancelsNow = cancelled
        if !cancelsNow { self.handle = handle }
        lock.unlock()
        if cancelsNow { handle.cancel() }
    }

    /// Cancels the subscription, once.
    func cancel() {
        lock.lock()
        let handle = cancelled ? nil : self.handle
        cancelled = true
        self.handle = nil
        lock.unlock()
        handle?.cancel()
    }
}

/// A sequence whose every iterator is a subscription of its own, started by `makeAsyncIterator()`.
///
/// Only the iterator holds the stream, so leaving a `for try await` loop (a `break`, a `return`, a
/// thrown error or a cancelled task) ends the subscription even while the sequence is still held.
struct SubscriptionSequence<Element: Sendable>: AsyncSequence, Sendable {

    /// Starts one subscription.
    let subscribe: @Sendable () -> AsyncThrowingStream<Element, any Error>

    /// A new subscription's iterator.
    func makeAsyncIterator() -> AsyncThrowingStream<Element, any Error>.AsyncIterator {
        subscribe().makeAsyncIterator()
    }
}

// MARK: - The access token

/// Gives the core the app's access token: the app's closure, run off the main actor, per request.
final class AccessTokenRelay: NSObject, BridgeAccessTokenProvider, Sendable {

    /// The app's closure: a token, or `nil` for the project key alone.
    private let fetchToken: @Sendable () async throws -> String?

    init(_ fetchToken: @escaping @Sendable () async throws -> String?) {
        self.fetchToken = fetchToken
    }

    /// Runs the app's closure in a task of its own and answers `callback` once with its result.
    func fetchAccessToken(callback: BridgeAccessTokenCallback) {
        let answer = TokenAnswer(callback: callback)
        Self.fetch(using: fetchToken) { result in
            switch result {
            case .success(let token): answer.callback.succeed(token: token)
            case .failure(let error): answer.callback.fail(message: "The access token closure threw: \(error)")
            }
        }
    }

    /// Runs `fetchToken` in a detached task, never on the caller's actor, and passes on its result.
    static func fetch(
        using fetchToken: @escaping @Sendable () async throws -> String?,
        then deliver: @escaping @Sendable (Result<String?, any Error>) -> Void
    ) {
        Task.detached {
            do {
                deliver(.success(try await fetchToken()))
            } catch {
                deliver(.failure(error))
            }
        }
    }
}

/// The core's one-time answer for a token, which is safe to call from any thread.
private struct TokenAnswer: @unchecked Sendable {

    /// The callback the token or the failure goes to.
    let callback: BridgeAccessTokenCallback
}

// MARK: - Logging

/// The unified-log subsystem the package writes under.
let logSubsystem = "SupabaseOffline"

/// The package's own messages, as against the core's.
let packageLogger = Logger(subsystem: logSubsystem, category: "Client")

/// Writes the core's log messages to the unified log, under the package's subsystem.
///
/// Above debug a message names no row, filter value or token, so it is public; a debug message
/// may, so it stays private (redacted outside a debugger).
final class SystemLogRelay: NSObject, BridgeLogSink, Sendable {

    /// Writes one message, at the matching unified-log level, in the category its tag maps to.
    func log(level: BridgeLogLevel, tag: String, message: String) {
        guard let type = logType(for: level) else { return }
        let logger = Logger(subsystem: logSubsystem, category: logCategory(forTag: tag))
        let text = redactingRuntimeNames(message)
        if level == .debug {
            logger.log(level: type, "\(text, privacy: .private)")
        } else {
            logger.log(level: type, "\(text, privacy: .public)")
        }
    }
}

/// The unified-log level of a core message's `level`; `nil` for one that is never written.
func logType(for level: BridgeLogLevel) -> OSLogType? {
    switch level {
    case .debug: return .debug
    case .info: return .info
    case .warning: return .default
    case .error: return .error
    default: return nil
    }
}

/// The log category for a core message's `tag`: the area it comes from, in the package's words.
func logCategory(forTag tag: String) -> String {
    let area = tag.hasPrefix("Supabase-") ? String(tag.dropFirst("Supabase-".count)) : tag
    switch area {
    case "Postgrest": return "PostgREST"
    case "Storage": return "Storage"
    case let name where name.hasPrefix("Offline"): return "Offline"
    default: return "Client"
    }
}

/// `text` without the names of the runtime under the package: package-qualified type names are
/// cut to the type's own name, and the runtime's and the core's own names become "core".
func redactingRuntimeNames(_ text: String) -> String {
    text
        .replacing(qualifiedRuntimeName, with: "")
        .replacing(runtimeWord, with: "core")
}

/// A dotted package prefix of the runtime's or its libraries' type names, such as `kotlinx.coroutines.`.
nonisolated(unsafe) private let qualifiedRuntimeName =
    /\b(?:kotlinx?|io\.ktor|io\.github\.jan|io\.github\.nesfeder|okio|androidx)(?:\.[a-z][A-Za-z0-9_]*)*\./

/// A word naming the runtime or the layer between it and Swift, in any case.
nonisolated(unsafe) private let runtimeWord = /(?i)\b(?:kotlin|konan|bridge)\b/
