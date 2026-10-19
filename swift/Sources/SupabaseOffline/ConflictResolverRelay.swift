// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineCore

/// Gives the core the app's conflict resolver: the app's closure, run off the main actor, once per
/// conflict.
final class ConflictResolverRelay: NSObject, BridgeConflictResolver, Sendable {

    /// The app's closure.
    private let resolveConflict: @Sendable (MutationConflict) async throws -> ConflictResolution

    init(_ resolveConflict: @escaping @Sendable (MutationConflict) async throws -> ConflictResolution) {
        self.resolveConflict = resolveConflict
    }

    /// Runs the app's closure in a detached task and answers `answer` once; a throw, or a conflict
    /// or resolution that cannot cross, answers as a failure, which holds the write.
    func resolve(conflict: BridgeConflict, answer: BridgeConflictAnswer) {
        let reply = ConflictAnswerReference(answer: answer)
        let decoded: MutationConflict
        do {
            decoded = try MutationConflict(core: conflict)
        } catch {
            reply.answer.fail(message: "The conflict could not be read: \(error)")
            return
        }
        let resolveConflict = self.resolveConflict
        Task.detached {
            do {
                try reply.deliver(try await resolveConflict(decoded))
            } catch {
                reply.answer.fail(message: "The conflict resolver threw: \(error)")
            }
        }
    }
}

/// The core's one-time answer for a conflict, which is safe to call from any thread.
private struct ConflictAnswerReference: @unchecked Sendable {

    /// The answer the resolution or the failure goes to.
    let answer: BridgeConflictAnswer

    /// Hands `resolution` to the core.
    ///
    /// - Throws: ``OfflineError``: `encoding` when the columns of an apply cannot be encoded.
    func deliver(_ resolution: ConflictResolution) throws {
        switch resolution {
        case .keepLocal: answer.keepLocal()
        case .keepServer: answer.keepServer()
        case .apply(let columns): answer.apply(columnsJson: try RowCoding.jsonText(for: columns))
        case .hold: answer.hold()
        }
    }
}
