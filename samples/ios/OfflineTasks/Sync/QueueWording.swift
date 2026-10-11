// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// The words for the mutation queue, its refusals, a sync's report and a list's provenance: pure
/// functions of the library's values, so the tests build those values with the testing product.
nonisolated extension Wording {

    // --8<-- [start:waiting-change]
    /// A waiting write as a row of the Sync tab: "Added “Buy milk” · 2 min ago", from the
    /// write's table, operation, body and creation time.
    static func waitingChange(_ mutation: PendingMutation, now: Date) -> String {
        let what = change(mutation.operation, table: mutation.table, body: mutation.body)
        return "\(what) · \(age(of: mutation.createdAt, now: now))"
    }
    // --8<-- [end:waiting-change]

    /// "Added “Buy milk”", "Edited a task", "Deleted a photo": what a write of `operation` to
    /// `table` does, named by the title its `body` carries when it carries one.
    static func change(_ operation: MutationOperation, table: String, body: OfflineJSON?) -> String {
        let verb =
            switch operation {
            case .insert: "Added"
            case .upsert: "Saved"
            case .update: "Edited"
            case .delete: "Deleted"
            }
        guard table == Tables.tasks else { return "\(verb) a photo" }
        guard let title = titles(in: body).first else { return "\(verb) a task" }
        let others = titles(in: body).count - 1
        return others > 0 ? "\(verb) “\(Self.title(title))” and \(Self.count(others, "other", "others"))" : "\(verb) “\(Self.title(title))”"
    }

    /// The `title` values a write's body carries: one for a row or a patch, several for a list.
    static func titles(in body: OfflineJSON?) -> [String] {
        switch body {
        case .object(let values)?:
            guard case .string(let title)? = values["title"] else { return [] }
            return [title]
        case .array(let rows)?:
            return rows.flatMap { titles(in: $0) }
        default:
            return []
        }
    }

    // --8<-- [start:new-task-outcome]
    /// What New task says once its write returns: the time the server stored the task at, or that
    /// it waits on this device.
    static func newTaskResult(_ result: NewTaskResult) -> String {
        switch result {
        case .saved(let storedAt):
            "Saved — the server stored it at \(storedAt.formatted(date: .omitted, time: .shortened))"
        case .savedOnDevice:
            "Saved on this device — it syncs when the server can be reached"
        }
    }
    // --8<-- [end:new-task-outcome]

    /// What a write that returned `outcome` did: `applied` once the server has it, or that it
    /// waits on this device.
    static func outcome<Value>(_ outcome: MutationOutcome<Value>, applied: String) -> String {
        outcome.isQueued ? "\(applied) on this device — it syncs when the server can be reached" : applied
    }

    // --8<-- [start:rejected-row]
    /// What a refused write did, when it was made and refused, and the rows it was for: "Added
    /// “Buy milk” · made 10:41 · refused 10:42 · row 3f2a…".
    static func refusedChange(_ rejection: RejectedMutation) -> String {
        let what = change(rejection.operation, table: rejection.table, body: rejection.body)
        let made = rejection.createdAt.formatted(date: .omitted, time: .shortened)
        let refused = rejection.rejectedAt.formatted(date: .omitted, time: .shortened)
        return "\(what) · made \(made) · refused \(refused) · \(rowKeys(rejection.rowKeys))"
    }
    // --8<-- [end:rejected-row]

    /// The rows a write was for, by the start of their keys: "row 3f2a…", "2 rows", "no row key".
    static func rowKeys(_ keys: [[String: OfflineJSON]]) -> String {
        guard keys.count == 1, case .string(let id)? = keys[0]["id"] else {
            return keys.isEmpty ? "no row key" : count(keys.count, "row", "rows")
        }
        return "row \(id.prefix(4))…"
    }

    // --8<-- [start:sync-report]
    /// What one Sync now did, every count of its report spelled out.
    static func syncReport(_ report: SyncReport) -> String {
        [
            "Sent \(report.syncedCount)",
            "refused \(report.rejected.count)",
            "still waiting \(report.remainingCount)",
            "already on the server \(report.alreadyOnServerCount)",
            "held behind other tables \(report.heldBehindOtherTablesCount)",
        ].joined(separator: " · ")
    }
    // --8<-- [end:sync-report]

    // --8<-- [start:provenance-caption]
    /// The caption under the Tasks title: where the list shown came from, and how old it is.
    static func caption(_ provenance: ListProvenance, pendingCount: Int, now: Date) -> String {
        if provenance.hasPendingMutations, pendingCount > 0 {
            return "\(count(pendingCount, "change", "changes")) waiting"
        }
        guard let fetchedAt = provenance.fetchedAt else { return "Not fetched from the server yet" }
        let age = age(of: fetchedAt, now: now)
        return provenance.isFromCache ? "From this device, \(age)" : "Updated \(age)"
    }
    // --8<-- [end:provenance-caption]

    /// The device's copy of a photo's file, for the Details sheet: "Fetched 3 min ago · 48 KB".
    static func deviceCopy(_ file: CachedFile, now: Date) -> String {
        "Fetched \(age(of: file.fetchedAt, now: now)) · \(file.sizeBytes.formatted(.byteCount(style: .file)))"
    }

    /// How long ago `date` was, in the app's words: "just now", "3 min ago", "2 h ago", or the day.
    static func age(of date: Date, now: Date) -> String {
        let seconds = now.timeIntervalSince(date)
        switch seconds {
        case ..<60: return "just now"
        case ..<3_600: return "\(Int(seconds / 60)) min ago"
        case ..<86_400: return "\(Int(seconds / 3_600)) h ago"
        default: return date.formatted(date: .abbreviated, time: .omitted)
        }
    }
}
