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
            hint: failure.hint
        )
    }

    /// The error a caller catches for a core failure of `kind`, with the fields it carries.
    ///
    /// Total: a kind this release does not know is ``Code/unexpected``.
    static func thrown(
        kind: BridgeErrorKind,
        message rawMessage: String,
        httpStatus: Int?,
        serverCode: String?,
        details: String?,
        hint: String?
    ) -> any Error {
        let message = redactingRuntimeNames(rawMessage)
        let serverError = httpStatus.map {
            ServerError(status: $0, code: serverCode, message: message, details: details, hint: hint)
        }
        let code: Code
        switch kind {
        case .cancelled: return CancellationError()
        case .server, .http: code = .server
        case .connectivity: code = .connectivity
        case .cacheMiss: code = .cacheMiss
        case .networkDisabled: code = .networkDisabled
        case .mutationNotQueueable: code = .mutationNotQueueable
        case .syncFailed: code = .syncFailed
        case .clientClosed: code = .clientClosed
        case .accessToken: code = .accessToken
        case .invalidRequest, .unsupported, .invalidArgument: code = .invalidRequest
        case .decoding: code = .decoding
        case .unexpected: code = .unexpected
        default: code = .unexpected
        }
        return OfflineError(code: code, message: message, serverError: serverError)
    }
}
