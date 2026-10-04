// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineCore

extension OfflineError {

    /// The error a caller catches for a core failure: `CancellationError`, or an ``OfflineError``.
    static func thrown(for failure: BridgeError) -> any Error {
        thrown(
            kind: failure.kind,
            message: failure.message,
            httpStatus: failure.httpStatus?.intValue,
            serverCode: failure.code,
            details: failure.details,
            hint: failure.hint,
            isConnectivityFailure: failure.isConnectivityFailure
        )
    }

    /// The error a caller catches for a core failure of `kind`, with the fields it carries.
    ///
    /// Total: a kind this release does not know is ``Code/unexpected``. Only the text the core
    /// writes about its own failures is cleared of runtime names; a server's text, the app's and
    /// the request's own values reach the caller exactly as written.
    static func thrown(
        kind: BridgeErrorKind,
        message rawMessage: String,
        httpStatus: Int?,
        serverCode: String?,
        details: String?,
        hint: String?,
        isConnectivityFailure: Bool
    ) -> any Error {
        let code: Code
        var describesTheCore = false
        switch kind {
        case .cancelled: return CancellationError()
        case .server, .http: code = .server
        case .connectivity: (code, describesTheCore) = (.connectivity, true)
        case .cacheMiss: code = .cacheMiss
        case .networkDisabled: code = .networkDisabled
        case .mutationNotQueueable: code = .mutationNotQueueable
        case .syncFailed: code = .syncFailed
        case .clientClosed: code = .clientClosed
        case .accessToken: code = .accessToken
        case .invalidRequest, .unsupported: code = .invalidRequest
        case .invalidArgument: (code, describesTheCore) = (.invalidRequest, true)
        case .decoding: (code, describesTheCore) = (.decoding, true)
        default: (code, describesTheCore) = (.unexpected, true)
        }
        let message: String
        if code == .syncFailed && httpStatus == nil && !isConnectivityFailure {
            message = localSyncFailureMessage
        } else {
            message = describesTheCore ? redactingRuntimeNames(rawMessage) : rawMessage
        }
        let serverError = httpStatus.map {
            ServerError(status: $0, code: serverCode, message: message, details: details, hint: hint)
        }
        return OfflineError(
            code: code,
            message: message,
            serverError: serverError,
            isNetworkOrConnectivityFailure: isConnectivityFailure || code == .connectivity
        )
    }

    /// The message of a sync pass that a failure on the device stopped, neither the server's nor
    /// the network's: the core's own text for it names the failure's class.
    static let localSyncFailureMessage = "A failure on this device stopped the sync pass; the queued writes stay queued for a later pass"
}
