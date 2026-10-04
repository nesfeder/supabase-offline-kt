// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
import SupabaseOfflineCore

/// The server an in-memory client talks to: a closure from each request to its response.
///
/// The closure runs off the main actor, once per request, and may take its time: the request
/// waits for it. ``Response/noConnection`` answers as a network that never reached the server.
public struct FakeServer: Sendable {

    /// One request the client sent.
    public struct Request: Sendable, Hashable {

        /// The HTTP method, such as `GET`.
        public let method: String

        /// The full URL, query included.
        public let url: URL

        /// The headers, a header sent more than once joined with commas; the body's
        /// `Content-Type` among them.
        public let headers: [String: String]

        /// The body; empty when there is none.
        public let body: Data
    }

    /// The answer to one request.
    public struct Response: Sendable, Hashable {

        /// What the client receives.
        enum Answer: Sendable, Hashable {

            /// An HTTP response.
            case http(status: Int, headers: [String: String], body: Data)

            /// A connection that failed before reaching the server.
            case noConnection
        }

        /// What the client receives.
        let answer: Answer

        /// An HTTP response with `status`, and `headers` and `body` when given.
        public static func status(_ status: Int, headers: [String: String] = [:], body: Data = Data()) -> Response {
            Response(answer: .http(status: status, headers: headers, body: body))
        }

        /// A JSON response: `json` as the body, with `Content-Type: application/json`.
        public static func json(_ json: String, status: Int = 200, headers: [String: String] = [:]) -> Response {
            let headers = headers.merging(["Content-Type": "application/json"]) { given, _ in given }
            return Response(answer: .http(status: status, headers: headers, body: Data(json.utf8)))
        }

        /// No response: the request fails as if the device were offline.
        public static let noConnection = Response(answer: .noConnection)
    }

    /// Answers each request.
    let respond: @Sendable (Request) async -> Response

    /// A server whose every request `respond` answers.
    public init(respond: @escaping @Sendable (Request) async -> Response) {
        self.respond = respond
    }

    /// A server that is never reachable: every request fails as if the device were offline.
    public init() {
        self.init { _ in .noConnection }
    }
}

/// Hands the core's requests to a ``FakeServer`` and its responses back.
final class FakeServerRelay: NSObject, BridgeFakeServer, Sendable {

    /// The app's server.
    private let server: FakeServer

    init(_ server: FakeServer) {
        self.server = server
    }

    /// Answers `request` through `responder` from a task of its own, off the core's thread.
    func respond(request: BridgeHttpRequest, responder: BridgeHttpResponder) {
        let answer = ResponderReference(responder: responder)
        guard let url = URL(string: request.url) else {
            responder.failWithoutConnection(message: "The request's URL could not be read")
            return
        }
        let request = FakeServer.Request(method: request.method, url: url, headers: request.headers, body: request.body)
        let server = self.server
        Task.detached {
            switch await server.respond(request).answer {
            case .http(let status, let headers, let body):
                answer.responder.respond(status: Int32(clamping: status), headers: headers, body: body)
            case .noConnection:
                answer.responder.failWithoutConnection(message: "The fake server is unreachable")
            }
        }
    }
}

/// The core's one-time answer for a request, which is safe to call from any thread.
private struct ResponderReference: @unchecked Sendable {

    /// The responder the response goes to.
    let responder: BridgeHttpResponder
}
