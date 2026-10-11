// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// A failure the app tells the user about, in its own words. ``TaskRepository`` turns the
/// library's ``OfflineError`` into one of these, so every screen says the same thing for the same
/// failure.
nonisolated enum SampleFailure: Error, Equatable {

    /// The server refused a write sent at once, because the network was up and nothing waited
    /// ahead of it. The device's copy is the server's again. A write that waited in the queue is
    /// never thrown: its refusal goes to the rejected list.
    case writeRefused(taskTitle: String?, serverError: OfflineError.ServerError)

    /// The call needs the server, and the network is off or out of reach.
    case needsNetwork

    /// The network is up, but the server failed to answer properly.
    case serverUnreachable

    /// A pull to refresh offline: the list shown is the one saved on the device.
    case offlineRefresh

    /// Storage refused an upload, a download or a delete.
    case storageRefused(status: Int, message: String)

    /// The picked image could not be read or decoded.
    case photoUnreadable

    /// The photo's file is in neither the device's cache nor the bucket.
    case photoMissing

    /// Anything else; the user is told to try again.
    case unexpected

    /// What to tell the user, in a sentence or two.
    var message: String {
        switch self {
        case .writeRefused(let taskTitle, let serverError):
            let title = Wording.title(taskTitle)
            return "Couldn’t save “\(title)”. \(Wording.refusalReason(serverError))"
        case .needsNetwork:
            return "That needs the network, and you’re offline."
        case .serverUnreachable:
            return "The server couldn’t be reached. Try again in a moment."
        case .offlineRefresh:
            return "You’re offline — showing what’s saved on this device."
        case .storageRefused(let status, let message):
            return "Storage refused it (\(status)): \(message)"
        case .photoUnreadable:
            return "That image couldn’t be read."
        case .photoMissing:
            return "This photo’s file is missing."
        case .unexpected:
            return "Something went wrong. Try again."
        }
    }
}

extension SampleFailure {

    /// A write's failure: the server's refusal of a write sent at once, or the network.
    static func write(_ error: any Error, taskTitle: String?) -> any Error {
        guard let offline = error as? OfflineError else { return error }
        if offline.isNetworkOrConnectivityFailure || offline.code == .mutationNotQueueable {
            return SampleFailure.needsNetwork
        }
        if offline.code == .server, let serverError = offline.serverError {
            return SampleFailure.writeRefused(taskTitle: taskTitle, serverError: serverError)
        }
        return error
    }

    /// A read's failure: the network, or a server that answers with an error.
    static func read(_ error: any Error) -> any Error {
        guard let offline = error as? OfflineError else { return error }
        if offline.isNetworkOrConnectivityFailure { return SampleFailure.needsNetwork }
        if offline.httpStatus != nil { return SampleFailure.serverUnreachable }
        return error
    }

    /// A Storage call's failure. Storage answers a missing object with its own `not_found` code
    /// (on an HTTP 400 or 404).
    static func storage(_ error: any Error) -> any Error {
        guard let offline = error as? OfflineError else { return error }
        if offline.isNetworkOrConnectivityFailure { return SampleFailure.needsNetwork }
        guard let serverError = offline.serverError else { return error }
        if serverError.status == 404 || serverError.code == "not_found" { return SampleFailure.photoMissing }
        return SampleFailure.storageRefused(status: serverError.status, message: serverError.message)
    }

    /// What to tell the user about `error`, whatever it is; never empty.
    static func message(for error: any Error) -> String {
        (error as? SampleFailure ?? .unexpected).message
    }
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

    /// `count` and the noun for it, singular for one: "1 change", "3 changes".
    static func count(_ count: Int, _ singular: String, _ plural: String) -> String {
        "\(count) \(count == 1 ? singular : plural)"
    }
}
