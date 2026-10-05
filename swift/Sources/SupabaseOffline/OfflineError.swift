// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation

/// A failure the package throws; a cancelled call throws `CancellationError` instead.
///
/// Match it by code, as `URLError` is matched: `catch OfflineError.cacheMiss { … }`. A later
/// release may add codes, so a `switch` over ``code`` keeps a `default`.
public struct OfflineError: Error, Sendable, CustomStringConvertible {

    /// What failed. Extensible: a later release may add codes without breaking a `switch`.
    public struct Code: RawRepresentable, Hashable, Sendable, CustomStringConvertible {

        /// The code's name, such as `cacheMiss`.
        public let rawValue: String

        /// The code named `rawValue`; a name this release does not define matches nothing it throws.
        public init(rawValue: String) {
            self.rawValue = rawValue
        }

        /// The code's name.
        public var description: String { rawValue }

        /// The server refused the request; ``OfflineError/serverError`` says how.
        public static let server = Code(rawValue: "server")

        /// The request could not reach the server, or got no answer in time.
        public static let connectivity = Code(rawValue: "connectivity")

        /// A cache-only read found nothing it may serve; nothing was sent.
        public static let cacheMiss = Code(rawValue: "cacheMiss")

        /// A request that must reach the server was made while the network was turned off.
        public static let networkDisabled = Code(rawValue: "networkDisabled")

        /// A write the mutation queue cannot hold could not be sent for want of a connection.
        public static let mutationNotQueueable = Code(rawValue: "mutationNotQueueable")

        /// A sync pass stopped with writes still queued.
        public static let syncFailed = Code(rawValue: "syncFailed")

        /// The client is closed, or was closed while the call ran.
        public static let clientClosed = Code(rawValue: "clientClosed")

        /// The access-token closure threw.
        public static let accessToken = Code(rawValue: "accessToken")

        /// The request was refused before it was sent: an argument or option the client cannot use.
        public static let invalidRequest = Code(rawValue: "invalidRequest")

        /// A response could not be decoded as the type asked for.
        public static let decoding = Code(rawValue: "decoding")

        /// A value could not be encoded as JSON.
        public static let encoding = Code(rawValue: "encoding")

        /// The client could not be opened with its configuration.
        public static let configuration = Code(rawValue: "configuration")

        /// A defect in the package, reported as an error instead of ending the app.
        public static let unexpected = Code(rawValue: "unexpected")

        /// Whether `error` is an ``OfflineError`` with this code, so `catch OfflineError.cacheMiss` works.
        public static func ~= (code: Code, error: any Error) -> Bool {
            (error as? OfflineError)?.code == code
        }
    }

    /// The server's account of a refusal. For ``Code/server`` every field is as the server sent
    /// it; for ``Code/syncFailed`` only `status` and `code` are the server's, and `message` is the
    /// library's summary of the stopped sync.
    public struct ServerError: Sendable, Hashable {

        /// The HTTP status.
        public let status: Int

        /// The Postgres SQLSTATE or PostgREST code, such as `23505` or `PGRST116`, when one was sent.
        public let code: String?

        /// The server's message; for a sync failure, the library's summary of it.
        public let message: String

        /// The server's details, when it sent any; they may quote the request's values.
        public let details: String?

        /// The server's hint, when it sent one.
        public let hint: String?

        package init(status: Int, code: String?, message: String, details: String?, hint: String?) {
            self.status = status
            self.code = code
            self.message = message
            self.details = details
            self.hint = hint
        }
    }

    /// What failed.
    public let code: Code

    /// What happened, in words. The library's own messages never quote a token, nor a request's URL
    /// or values; a server's text is passed on as the server wrote it.
    public let message: String

    /// The server's refusal: set for ``Code/server``, and for a ``Code/syncFailed`` the server
    /// caused, where only its `status` and `code` are the server's.
    public let serverError: ServerError?

    /// The Swift error behind this one: the `DecodingError` of a ``Code/decoding`` failure, or the
    /// `EncodingError` of an ``Code/encoding`` one; otherwise `nil`.
    public let underlyingError: (any Error)?

    /// Whether the network failed rather than the server: true for ``Code/connectivity``,
    /// ``Code/networkDisabled`` and ``Code/mutationNotQueueable``, and for a ``Code/syncFailed``
    /// the network caused.
    public let isNetworkOrConnectivityFailure: Bool

    /// The server's HTTP status, when the server answered: ``ServerError/status``.
    public var httpStatus: Int? { serverError?.status }

    package init(
        code: Code,
        message: String,
        serverError: ServerError? = nil,
        underlyingError: (any Error)? = nil,
        isNetworkOrConnectivityFailure: Bool = false
    ) {
        self.code = code
        self.message = message
        self.serverError = serverError
        self.underlyingError = underlyingError
        self.isNetworkOrConnectivityFailure = isNetworkOrConnectivityFailure
    }

    /// The code and message, and the server's status and code when it refused; never its details.
    public var description: String {
        guard let serverError else { return "\(code): \(message)" }
        let serverCode = serverError.code.map { ", code \($0)" } ?? ""
        return "\(code) (status \(serverError.status)\(serverCode)): \(message)"
    }
}

extension OfflineError: LocalizedError {

    /// The message, for alerts and `localizedDescription`.
    public var errorDescription: String? { message }
}

extension OfflineError {

    /// ``Code/server``, for `catch OfflineError.server`.
    public static var server: Code { .server }

    /// ``Code/connectivity``, for `catch OfflineError.connectivity`.
    public static var connectivity: Code { .connectivity }

    /// ``Code/cacheMiss``, for `catch OfflineError.cacheMiss`.
    public static var cacheMiss: Code { .cacheMiss }

    /// ``Code/networkDisabled``, for `catch OfflineError.networkDisabled`.
    public static var networkDisabled: Code { .networkDisabled }

    /// ``Code/mutationNotQueueable``, for `catch OfflineError.mutationNotQueueable`.
    public static var mutationNotQueueable: Code { .mutationNotQueueable }

    /// ``Code/syncFailed``, for `catch OfflineError.syncFailed`.
    public static var syncFailed: Code { .syncFailed }

    /// ``Code/clientClosed``, for `catch OfflineError.clientClosed`.
    public static var clientClosed: Code { .clientClosed }

    /// ``Code/accessToken``, for `catch OfflineError.accessToken`.
    public static var accessToken: Code { .accessToken }

    /// ``Code/invalidRequest``, for `catch OfflineError.invalidRequest`.
    public static var invalidRequest: Code { .invalidRequest }

    /// ``Code/decoding``, for `catch OfflineError.decoding`.
    public static var decoding: Code { .decoding }

    /// ``Code/encoding``, for `catch OfflineError.encoding`.
    public static var encoding: Code { .encoding }

    /// ``Code/configuration``, for `catch OfflineError.configuration`.
    public static var configuration: Code { .configuration }

    /// ``Code/unexpected``, for `catch OfflineError.unexpected`.
    public static var unexpected: Code { .unexpected }
}
