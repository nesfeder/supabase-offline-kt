// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// A failure the app tells the user about in its own words, where the app knows more than the
/// library's error says: which task a refused write was, or that a photo's file is gone. Every
/// other failure reaches the user as the library reported it, an ``OfflineError``, in the sentence
/// ``sentence(for:)`` gives its code.
nonisolated enum SampleFailure: Error, Equatable {

    /// The server refused a write sent at once, because the network was up and nothing waited
    /// ahead of it. The device's copy is the server's again. A write that waited in the queue is
    /// never thrown: its refusal goes to the rejected list.
    case writeRefused(taskTitle: String?, serverError: OfflineError.ServerError)

    /// The call needs the server, and the network is off or out of reach.
    case needsNetwork

    /// Storage refused an upload, a download or a change.
    case storageRefused(status: Int, message: String)

    /// The picked image could not be read or decoded.
    case photoUnreadable

    /// The photo's file is in neither the device's cache nor the bucket.
    case photoMissing

    /// Realtime's feed could not be reached, or sent a change the app could not read.
    case liveUpdatesStopped

    /// The records of `count` photos waiting to upload could not be read from the device.
    case pendingPhotoUnreadable(count: Int)

    /// What to tell the user, in a sentence or two.
    var message: String {
        switch self {
        case .writeRefused(let taskTitle, let serverError):
            return "Couldn’t save “\(Wording.title(taskTitle))”. \(Wording.refusalReason(serverError))"
        case .needsNetwork:
            return "That needs the network, and you’re offline."
        case .storageRefused(let status, let message):
            return "Storage refused it (\(status)): \(message)"
        case .photoUnreadable:
            return "That image couldn’t be read."
        case .photoMissing:
            return "This photo’s file is missing."
        case .liveUpdatesStopped:
            return "Live updates stopped: the server’s change feed couldn’t be reached. Pull to refresh still works."
        case .pendingPhotoUnreadable(let count):
            return "\(Wording.count(count, "photo", "photos")) waiting to upload couldn’t be read on this device. Clear local cache removes them."
        }
    }
}

nonisolated extension SampleFailure {

    // --8<-- [start:write-failure]
    /// A write's failure: the server's refusal of a write sent at once, named by its task. Any
    /// other failure stays the library's, for ``sentence(for:)``.
    static func write(_ error: any Error, taskTitle: String?) -> any Error {
        guard let offline = error as? OfflineError, offline.code == .server, let serverError = offline.serverError else {
            return error
        }
        return SampleFailure.writeRefused(taskTitle: taskTitle, serverError: serverError)
    }
    // --8<-- [end:write-failure]

    /// A Storage call's failure. Storage answers a missing object with its own `not_found` code
    /// (on an HTTP 400 or 404).
    static func storage(_ error: any Error) -> any Error {
        guard let offline = error as? OfflineError else { return error }
        if offline.isNetworkOrConnectivityFailure { return SampleFailure.needsNetwork }
        guard let serverError = offline.serverError else { return error }
        if serverError.status == 404 || serverError.code == "not_found" { return SampleFailure.photoMissing }
        return SampleFailure.storageRefused(status: serverError.status, message: serverError.message)
    }

    /// An upload's failure: as ``storage(_:)``, but a "not found" is Storage's refusal (of a
    /// missing bucket, say), since there is no photo yet to be missing.
    static func upload(_ error: any Error) -> any Error {
        guard let offline = error as? OfflineError else { return error }
        if offline.isNetworkOrConnectivityFailure { return SampleFailure.needsNetwork }
        guard let serverError = offline.serverError else { return error }
        return SampleFailure.storageRefused(status: serverError.status, message: serverError.message)
    }

    /// Whether another attempt at what failed with `error` can succeed: not when the server
    /// refused it for good (a 4xx but 408 and 429: a policy, a constraint, a missing task), nor
    /// when the photo cannot be read.
    static func isRetryable(_ error: any Error) -> Bool {
        switch error as? SampleFailure {
        case .storageRefused(let status, _)?: return isTransient(status: status)
        case .writeRefused(_, let serverError)?: return isTransient(status: serverError.status)
        case .photoUnreadable?: return false
        default: return true
        }
    }

    /// Whether the server's `status` says a later attempt may be answered differently.
    private static func isTransient(status: Int) -> Bool {
        status >= 500 || status == 408 || status == 429
    }

    // --8<-- [start:reportable-failure]
    /// A line about `error` that is safe to log or report: its code, the server's status and the
    /// kind of failure underneath, never its message, which can hold a filter value, a row or a path.
    static func reportable(_ error: OfflineError) -> String {
        let status = error.httpStatus.map(String.init) ?? "none"
        let underlying = error.underlyingError.map { String(describing: type(of: $0)) } ?? "none"
        return "code=\(error.code) status=\(status) network=\(error.isNetworkOrConnectivityFailure) underlying=\(underlying)"
    }
    // --8<-- [end:reportable-failure]

    /// What to tell the user about `error`, whatever it is; never empty.
    static func message(for error: any Error) -> String {
        if let failure = error as? SampleFailure { return failure.message }
        if let offline = error as? OfflineError { return sentence(for: offline) }
        return "Something went wrong. Try again."
    }

    // --8<-- [start:failure-codes]
    /// The app's sentence for each of the library's failures, by its code.
    static func sentence(for error: OfflineError) -> String {
        switch error.code {
        case .networkDisabled:
            return "Simulate offline is on, and that needs the server. Switch it off on the Sync tab."
        case .connectivity:
            return "That needs the network, and the server can’t be reached. Try again when you’re online."
        case .cacheMiss:
            return "Nothing for that is saved on this device yet. Open it once while you’re online."
        case .mutationNotQueueable:
            return "That change can’t wait in the queue, and the server can’t be reached, so nothing was changed. (\(error.message))"
        case .syncFailed:
            return "The last sync stopped early\(Wording.serverAnswer(status: error.httpStatus, code: error.serverError?.code)). The rest waits, and is sent on the next try."
        case .server:
            return error.serverError.map(Wording.refusalReason) ?? "The server refused it."
        case .clientClosed:
            return "The app’s offline data is closed. Restart the app."
        case .accessToken:
            return "Your sign-in couldn’t be read, so nothing was sent. It’s tried again with the next request."
        case .invalidRequest:
            return "The app asked for something that can’t be sent as written. Nothing was sent."
        case .decoding:
            return "The server’s answer couldn’t be read as the app expects."
        case .encoding:
            return "The change couldn’t be written out to send. Nothing was saved."
        case .configuration:
            return "The app’s offline storage couldn’t be opened. Restart the app."
        case .unexpected:
            return "Something went wrong. Try again."
        default:
            // A code a later release of the library adds.
            return "Something went wrong (\(error.code)). Try again."
        }
    }
    // --8<-- [end:failure-codes]
}

/// Words the app uses in more than one place.
nonisolated enum Wording {

    /// A task shown without its title, which only a refused write can have.
    static let untitled = "(no title)"

    /// `title` as the app shows it: ``untitled`` when it is blank or missing.
    static func title(_ title: String?) -> String {
        guard let title, !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return untitled }
        return title
    }

    /// The title of a task's copy.
    static func copyTitle(of title: String) -> String {
        TaskRules.truncated("Copy of \(title)", to: TaskRules.titleMaxLength)
    }

    /// Why the server refused a write, from the constraint or the status it named.
    static func refusalReason(_ serverError: OfflineError.ServerError) -> String {
        if serverError.message.contains("tasks_title_length") {
            return "The server refused it: a title can’t be blank or longer than 200 characters."
        }
        if serverError.message.contains("tasks_notes_length") {
            return "The server refused it: notes can’t be longer than 2,000 characters."
        }
        if serverError.code == "23503" {
            return "The server refused it: its task isn’t on the server."
        }
        return "The server refused it (\(serverError.status)): \(serverError.message)"
    }

    /// ": the server answered 503 · PGRST001" for a sync that stopped on a server's answer; "" when
    /// it stopped for want of the network.
    static func serverAnswer(status: Int?, code: String?) -> String {
        guard let status else { return "" }
        return ": the server answered \(status)" + (code.map { " · \($0)" } ?? "")
    }

    /// `count` and the noun for it, singular for one: "1 change", "3 changes".
    static func count(_ count: Int, _ singular: String, _ plural: String) -> String {
        "\(count) \(count == 1 ? singular : plural)"
    }
}
