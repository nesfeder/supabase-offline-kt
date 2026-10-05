// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

extension SupabaseOfflineClient {

    /// Calls the database function `function` with `params`, an `Encodable` object, by POST.
    ///
    /// Network only: never cached and never queued, since a function may change data. Arguments
    /// go by POST only; a function called by GET or HEAD takes none.
    ///
    /// - Parameters:
    ///   - count: How to count the rows a set-returning function returns.
    ///   - schema: The function's schema; `nil` for the client's default.
    public func rpc(
        _ function: String,
        params: some Encodable,
        count: Count? = nil,
        schema: String? = nil
    ) -> OfflineRPCRequestBuilder {
        OfflineRPCRequestBuilder(client: self, function: function, schema: schema, method: "post", arguments: OfflinePostgrestQueryBuilder.bodyText(params), count: count)
    }

    /// Calls `function` with `params` written as JSON, such as `["points": 5]`, by POST.
    public func rpc(
        _ function: String,
        params: [String: OfflineJSON],
        count: Count? = nil,
        schema: String? = nil
    ) -> OfflineRPCRequestBuilder {
        OfflineRPCRequestBuilder(client: self, function: function, schema: schema, method: "post", arguments: OfflinePostgrestQueryBuilder.bodyText(params), count: count)
    }

    /// Calls `function` with no arguments: by POST, by GET for a function that reads only (`get`),
    /// or by HEAD for its count alone (`head`).
    public func rpc(
        _ function: String,
        head: Bool = false,
        get: Bool = false,
        count: Count? = nil,
        schema: String? = nil
    ) -> OfflineRPCRequestBuilder {
        let method = head ? "head" : get ? "get" : "post"
        return OfflineRPCRequestBuilder(client: self, function: function, schema: schema, method: method, arguments: nil, count: count)
    }
}

/// A database function call: filters and transforms on the rows it returns, then ``execute()``.
public struct OfflineRPCRequestBuilder: OfflinePostgrestRequestBuilder {

    /// The client the call goes through.
    let client: SupabaseOfflineClient

    /// The function called.
    let function: String

    /// The function's schema; `nil` for the client's default.
    let schema: String?

    /// `post`, `get` or `head`.
    let method: String

    /// The arguments as a JSON object's text, or why they could not be encoded; `nil` for none.
    let arguments: Result<String, any Error>?

    /// The filters, modifiers, count and response shape so far.
    var shape: RequestShape

    init(
        client: SupabaseOfflineClient,
        function: String,
        schema: String?,
        method: String,
        arguments: Result<String, any Error>?,
        count: Count?
    ) {
        self.client = client
        self.function = function
        self.schema = schema
        self.method = method
        self.arguments = arguments
        shape = RequestShape()
        shape.count = count
    }

    /// This call with `change` made to its request.
    public func applying(_ change: OfflineQueryChange) -> OfflineRPCRequestBuilder {
        var changed = self
        change.apply(&changed.shape)
        return changed
    }

    /// Calls the function without decoding its result.
    ///
    /// - Throws: ``OfflineError``: the server's refusal, `connectivity` failures, `networkDisabled`
    ///   with the network turned off, `encoding` or `invalidRequest` before anything is sent;
    ///   `CancellationError`.
    @discardableResult
    public func execute() async throws -> OfflineResponse<Void> {
        let (description, arguments) = try request()
        return try await client.callFunction(description: description, arguments: arguments, decode: BodyDecoding.ignoring)
    }

    /// Calls the function and decodes its result as `type`.
    ///
    /// - Throws: As ``execute()``, and ``OfflineError/Code/decoding`` when the result is not a `type`.
    public func execute<Value: Decodable & Sendable>(_ type: Value.Type = Value.self) async throws -> OfflineResponse<Value> {
        let (description, arguments) = try request()
        return try await client.callFunction(description: description, arguments: arguments, decode: BodyDecoding.decoding(type))
    }

    /// The call's description and arguments as the core reads them.
    func request() throws -> (description: String, arguments: String?) {
        let argumentsText = try arguments?.get()
        var fields = try shape.descriptionFields()
        fields["function"] = .string(function)
        if let schema { fields["schema"] = .string(schema) }
        if method != "post" { fields["method"] = .string(method) }
        return (try RequestShape.descriptionText(fields), argumentsText)
    }
}
