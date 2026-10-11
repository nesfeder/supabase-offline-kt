// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineCore

extension OfflineError {

    /// The error a caller catches for a core failure: `CancellationError`, or an ``OfflineError``.
    ///
    /// An unexpected failure's type is written to the log, never to the error.
    static func thrown(for failure: BridgeError) -> any Error {
        let thrown = thrown(
            kind: failure.kind,
            message: failure.message,
            httpStatus: failure.httpStatus?.intValue,
            serverCode: failure.code,
            details: failure.details,
            hint: failure.hint,
            isConnectivityFailure: failure.isConnectivityFailure
        )
        if (thrown as? OfflineError)?.code == .unexpected {
            let type = redactingRuntimeNames(failure.debugType)
            packageLogger.error("A call failed unexpectedly (\(type, privacy: .public))")
        }
        return thrown
    }

    /// The message of every ``Code/unexpected`` failure: it names no type and no package.
    static let unexpectedFailureMessage = "The call failed unexpectedly"

    /// The error a caller catches for a core failure of `kind`, with the fields it carries.
    ///
    /// Total: a kind this release does not know is ``Code/unexpected``, whose message is always
    /// ``unexpectedFailureMessage``. Only the text the core writes about its own failures is cleared
    /// of runtime names; a server's text, the library's sync summary, the app's text and the
    /// request's own values reach the caller exactly as written.
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
        default: code = .unexpected
        }
        let message: String
        if code == .unexpected {
            message = unexpectedFailureMessage
        } else if describesTheCore {
            message = redactingRuntimeNames(rawMessage)
        } else {
            message = rawMessage
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
}
