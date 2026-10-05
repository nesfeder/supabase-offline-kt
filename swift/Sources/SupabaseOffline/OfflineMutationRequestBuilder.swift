// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// A write: filters for an update or a delete, the rows to send back, then ``execute()``.
public struct OfflineMutationRequestBuilder: OfflinePostgrestFilterBuilder {

    /// The table written.
    let target: OfflinePostgrestQueryBuilder

    /// What the write does.
    let operation: MutationOperation

    /// The body as JSON text, or why it could not be encoded; `nil` for a delete.
    let body: Result<String, any Error>?

    /// Whether the write may wait in the mutation queue.
    let mutationStrategy: MutationStrategy

    /// Whether a column some inserted rows leave out is `null` in them rather than its default.
    let defaultsToNull: Bool

    /// The columns an upsert conflicts on; `nil` for the primary key.
    let onConflict: String?

    /// Whether an upsert leaves a conflicting row as it is.
    let ignoresDuplicates: Bool

    /// The columns the server sends back for the written rows; `nil` for none.
    var returning: String?

    /// The filters and response shape so far.
    var shape = RequestShape()

    /// This write with `change` made to its request.
    public func applying(_ change: OfflineQueryChange) -> OfflineMutationRequestBuilder {
        var changed = self
        change.apply(&changed.shape)
        return changed
    }

    /// Asks the server to send back `columns` of the written rows, to read from the
    /// ``MutationOutcome/applied(result:)`` result; a queued write has none until it is synced.
    public func select(_ columns: String = "*") -> OfflineMutationRequestBuilder {
        var changed = self
        changed.returning = columns
        return changed
    }

    /// The one written row sent back as an object rather than a list.
    public func single() -> OfflineMutationRequestBuilder {
        applying(OfflineQueryChange { $0.isSingle = true })
    }

    /// Makes the write, without decoding what the server sends back.
    ///
    /// - Returns: ``MutationOutcome/applied(result:)`` once the server applied it, or
    ///   ``MutationOutcome/queued(pendingMutation:)`` when it waits in the mutation queue.
    /// - Throws: ``OfflineError``: the server's refusal of a write sent at once (it then leaves the
    ///   queue), `connectivity` for an ``MutationStrategy/onlineOnly`` write with no connection,
    ///   `mutationNotQueueable` for a write the queue cannot hold made with no connection,
    ///   `networkDisabled` for an ``MutationStrategy/onlineOnly`` write with the network turned off,
    ///   `encoding` or `invalidRequest` before anything is sent; `CancellationError` (a queued write
    ///   stays queued).
    @discardableResult
    public func execute() async throws -> MutationOutcome<Void> {
        let (description, body) = try request()
        return try await target.client.mutate(description: description, body: body, decode: BodyDecoding.ignoring)
    }

    /// Makes the write and decodes the rows the server sends back as `type`; ask for them with
    /// ``select(_:)``.
    ///
    /// - Throws: As ``execute()``, and ``OfflineError/Code/decoding`` when an applied write's body
    ///   is not a `type`.
    @discardableResult
    public func execute<Value: Decodable & Sendable>(_ type: Value.Type = Value.self) async throws -> MutationOutcome<Value> {
        let (description, body) = try request()
        return try await target.client.mutate(description: description, body: body, decode: BodyDecoding.decoding(type))
    }

    /// The write's description and body as the core reads them.
    func request() throws -> (description: String, body: String?) {
        let bodyText = try body?.get()
        var fields = try shape.descriptionFields().merging(target.targetFields) { _, target in target }
        fields["operation"] = .string(operation.rawValue)
        fields["strategy"] = mutationStrategy.descriptionValue
        if let returning { fields["returning"] = .string(returning) }
        if !defaultsToNull, operation == .insert || operation == .upsert { fields["defaultToNull"] = false }
        if operation == .upsert {
            if let onConflict { fields["onConflict"] = .string(onConflict) }
            if ignoresDuplicates { fields["ignoreDuplicates"] = true }
        }
        return (try RequestShape.descriptionText(fields), bodyText)
    }
}
