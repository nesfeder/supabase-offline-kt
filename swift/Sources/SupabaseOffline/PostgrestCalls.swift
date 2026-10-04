// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineCore

// The core calls behind reads, writes and function calls: each sends a description built by a
// builder, and converts the answer on the core's thread, off the caller's actor.

extension SupabaseOfflineClient {

    /// A one-shot read of `description`, its body decoded by `decode`.
    func selectOnce<Value: Sendable>(
        description: String,
        decode: @escaping @Sendable (String) throws -> Value
    ) async throws -> OfflineResponse<Value> {
        try await connection.call { core, complete in
            core.select(query: description) { result, failure in
                complete(coreResult(result, failure) { try OfflineResponse(core: try requiredValue($0), decode: decode) })
            }
        }
    }

    /// A write of `description` with `body` (JSON text; `nil` for a delete), an applied body
    /// decoded by `decode`.
    func mutate<Value: Sendable>(
        description: String,
        body: String?,
        decode: @escaping @Sendable (String) throws -> Value
    ) async throws -> MutationOutcome<Value> {
        try await connection.call { core, complete in
            core.mutate(mutation: description, body: body) { outcome, failure in
                complete(coreResult(outcome, failure) { try MutationOutcome(core: try requiredValue($0), decode: decode) })
            }
        }
    }

    /// A function call of `description` with `arguments` (a JSON object as text, or `nil`), its
    /// body decoded by `decode`.
    func callFunction<Value: Sendable>(
        description: String,
        arguments: String?,
        decode: @escaping @Sendable (String) throws -> Value
    ) async throws -> OfflineResponse<Value> {
        try await connection.call { core, complete in
            core.rpc(call: description, arguments: arguments) { result, failure in
                complete(coreResult(result, failure) { try OfflineResponse(core: try requiredValue($0), decode: decode) })
            }
        }
    }

    /// A live read of `description`: every snapshot, its rows decoded by `decode`, newest unread
    /// only. A snapshot that does not decode ends the stream with the decoding error and cancels
    /// the core's subscription; `onSubscriptionEnd` hears how the core's subscription ended (the
    /// failure kind's name, or `nil`), for tests.
    func querySnapshots<Value: Sendable>(
        description: String,
        decode: @escaping @Sendable (String) throws -> Value,
        onSubscriptionEnd: (@Sendable (String?) -> Void)? = nil
    ) -> AsyncThrowingStream<QuerySnapshot<Value>, any Error> {
        connection.subscription { core, yield, finish in
            let stopper = SubscriptionStopper()
            let handle = core.observe(
                query: description,
                onSnapshot: { snapshot in
                    do {
                        yield(try QuerySnapshot(core: snapshot, decode: decode))
                    } catch {
                        finish(error)
                        stopper.stop()
                    }
                },
                onCompletion: { failure in
                    onSubscriptionEnd?(failure?.kind.name)
                    finish(failure.map(OfflineError.thrown(for:)))
                }
            )
            stopper.attach(handle)
            return handle
        }
    }
}

/// Decoders for a response body, run on the core's thread.
enum BodyDecoding {

    /// Decodes the body as `type`, failing with ``OfflineError/Code/decoding``.
    static func decoding<Value: Decodable & Sendable>(_ type: Value.Type) -> @Sendable (String) throws -> Value {
        { text in try RowCoding.decode(type, from: text) }
    }

    /// Ignores the body, for a caller that asked for no type.
    static let ignoring: @Sendable (String) throws -> Void = { _ in }
}

/// Cancels a core subscription from inside its own value handler, which runs before the handle may
/// have been returned: a stop that comes first cancels the handle as soon as it is attached.
private final class SubscriptionStopper: @unchecked Sendable {

    /// Guards every property below.
    private let lock = NSLock()

    /// The subscription's handle, once attached.
    private var handle: BridgeCancellable?

    /// Whether the subscription was stopped.
    private var isStopped = false

    /// Keeps the handle, or cancels it at once when the subscription was already stopped.
    func attach(_ handle: BridgeCancellable) {
        lock.lock()
        let cancelsNow = isStopped
        if !cancelsNow { self.handle = handle }
        lock.unlock()
        if cancelsNow { handle.cancel() }
    }

    /// Cancels the subscription, once.
    func stop() {
        lock.lock()
        let handle = isStopped ? nil : self.handle
        isStopped = true
        self.handle = nil
        lock.unlock()
        handle?.cancel()
    }
}
