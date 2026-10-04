// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import os
@testable import SupabaseOffline

/// A row of the `todos` table the PostgREST tests read and write.
struct TodoRow: Codable, Sendable, Hashable {

    /// The key.
    let id: Int

    /// The title.
    let title: String

    /// Whether it is done.
    let done: Bool
}

/// A fake server for one test that records every request and answers each with `answer`, which a
/// test may change mid-way (to take the server offline, say).
final class RecordingFakeServer: Sendable {

    /// Every request so far, in order.
    let requests = RecordingList<FakeServer.Request>()

    /// How the next request is answered.
    private let answer: OSAllocatedUnfairLock<@Sendable (FakeServer.Request) async -> FakeServer.Response>

    /// A server that answers every request with `answer`.
    init(answer: @escaping @Sendable (FakeServer.Request) async -> FakeServer.Response) {
        self.answer = OSAllocatedUnfairLock(initialState: answer)
    }

    /// A server that answers a read with `rows` (JSON text) and a write with `201` and no body.
    convenience init(rows: String = "[]") {
        self.init { request in request.method == "GET" ? .json(rows) : .status(201) }
    }

    /// Answers every later request with `answer`.
    func answer(with answer: @escaping @Sendable (FakeServer.Request) async -> FakeServer.Response) {
        self.answer.withLock { $0 = answer }
    }

    /// Fails every later request as if the device were offline.
    func goOffline() {
        answer { _ in .noConnection }
    }

    /// The server the in-memory client is given.
    var server: FakeServer {
        FakeServer { request in
            self.requests.append(request)
            let answer = self.answer.withLock { $0 }
            return await answer(request)
        }
    }

    /// The requests so far with `method`.
    func requests(withMethod method: String) -> [FakeServer.Request] {
        requests.values.filter { $0.method == method }
    }
}

extension FakeServer.Request {

    /// The query parameters, decoded, in the order they were sent (`+` read as a space).
    var queryParameters: [[String]] {
        guard let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedQuery,
            !query.isEmpty
        else { return [] }
        return query.split(separator: "&", omittingEmptySubsequences: false).map { pair in
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            return parts.map { String($0).replacing("+", with: " ").removingPercentEncoding ?? String($0) }
                + (parts.count == 1 ? [""] : [])
        }
    }

    /// The value of the header `name`, matched without regard to case.
    func header(_ name: String) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }

    /// The body as JSON; `nil` when there is none.
    var jsonBody: OfflineJSON? {
        get throws {
            body.isEmpty ? nil : try JSONDecoder().decode(OfflineJSON.self, from: body)
        }
    }
}

extension QuerySnapshotSequence {

    /// This read, telling `ends` how each of its subscriptions in the core ended (`nil` for an end
    /// the core reached by itself without a failure).
    func recordingEnds(in ends: RecordingList<String?>) -> QuerySnapshotSequence<Value> {
        var recording = self
        recording.onSubscriptionEnd = { ends.append($0) }
        return recording
    }
}

/// The description a builder would send, as JSON, for tests of what the chain writes.
func descriptionJSON(_ text: String) throws -> OfflineJSON {
    try JSONDecoder().decode(OfflineJSON.self, from: Data(text.utf8))
}

/// The `filters` array of a description.
func descriptionFilters(_ text: String) throws -> OfflineJSON? {
    guard case .object(let fields) = try descriptionJSON(text) else { return nil }
    return fields["filters"]
}
