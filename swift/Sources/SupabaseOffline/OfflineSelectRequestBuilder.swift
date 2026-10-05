// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// A one-shot read: filters and transforms, then ``execute()``.
public struct OfflineSelectRequestBuilder: OfflinePostgrestRequestBuilder {

    /// The table read.
    let target: OfflinePostgrestQueryBuilder

    /// The columns asked for.
    let columns: String

    /// Whether only the count is fetched.
    let isHead: Bool

    /// Where the rows come from.
    let readStrategy: ReadStrategy

    /// The filters, modifiers, count and response shape so far.
    var shape: RequestShape

    /// This read with `change` made to its request.
    public func applying(_ change: OfflineQueryChange) -> OfflineSelectRequestBuilder {
        var changed = self
        change.apply(&changed.shape)
        return changed
    }

    /// Reads the rows without decoding them: for a count, a `head` read, or a body read from
    /// ``OfflineResponse/data``.
    ///
    /// - Throws: ``OfflineError``: `connectivity` with no connection and nothing cached it may serve
    ///   (any ``ReadStrategy/networkOnly`` read, and a count, `head`, ``OfflinePostgrestRequestBuilder/single()``
    ///   or other format under every strategy but `cacheOnly`); `cacheMiss` under
    ///   ``ReadStrategy/cacheOnly(maxAge:)`` with nothing cached; `networkDisabled` with the network
    ///   turned off and nothing cached; the server's refusal; or `invalidRequest` for a filter that
    ///   cannot be sent; `CancellationError`.
    @discardableResult
    public func execute() async throws -> OfflineResponse<Void> {
        try await target.client.selectOnce(description: try descriptionText(), decode: BodyDecoding.ignoring)
    }

    /// Reads the rows and decodes them as `type`, such as `[Todo].self`, or `Todo.self` after
    /// ``OfflinePostgrestRequestBuilder/single()``.
    ///
    /// Decoding runs off the caller's actor. Under Xcode's main-actor default isolation, declare the
    /// row type `nonisolated` so its `Decodable` conformance may run there.
    ///
    /// - Throws: As ``execute()``, and ``OfflineError/Code/decoding`` when the body is not a `type`.
    public func execute<Value: Decodable & Sendable>(_ type: Value.Type = Value.self) async throws -> OfflineResponse<Value> {
        try await target.client.selectOnce(description: try descriptionText(), decode: BodyDecoding.decoding(type))
    }

    /// The read's description as the core reads it.
    func descriptionText() throws -> String {
        var fields = try shape.descriptionFields().merging(target.targetFields) { _, target in target }
        fields["columns"] = .string(columns)
        if isHead { fields["head"] = true }
        fields["strategy"] = try readStrategy.descriptionValue()
        return try RequestShape.descriptionText(fields)
    }
}
