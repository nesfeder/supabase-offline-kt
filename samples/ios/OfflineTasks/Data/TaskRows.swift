// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation

// The rows as the tables hold them. They are `nonisolated` because the client decodes them off the
// main actor, which is this target's default isolation.

// --8<-- [start:row]
/// A row of `tasks` as the server and the cache send it.
nonisolated struct TaskRow: Codable, Sendable {
    let id: String
    let userID: String
    let title: String
    let notes: String?
    let done: Bool
    let createdAt: Date

    /// The table's column names.
    enum CodingKeys: String, CodingKey {
        case id
        case userID = "user_id"
        case title
        case notes
        case done
        case createdAt = "created_at"
    }
}
// --8<-- [end:row]

/// A `tasks` row as the server stored it, with `updated_at`, the time the server set on it.
nonisolated struct StoredTaskRow: Codable, Sendable {
    let id: String
    let userID: String
    let title: String
    let notes: String?
    let done: Bool
    let createdAt: Date
    let updatedAt: Date

    /// The table's column names.
    enum CodingKeys: String, CodingKey {
        case id
        case userID = "user_id"
        case title
        case notes
        case done
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

/// A new row of `tasks`. It carries its owner and its creation time, so a task made offline sorts
/// where it belongs before the server has it; `updated_at` is the server's to set.
nonisolated struct NewTaskRow: Encodable, Sendable {
    let id: String
    let userID: String
    let title: String
    /// Left out when `nil`, so the column keeps its default, `null`.
    let notes: String?
    let done: Bool
    let createdAt: Date

    /// The table's column names.
    enum CodingKeys: String, CodingKey {
        case id
        case userID = "user_id"
        case title
        case notes
        case done
        case createdAt = "created_at"
    }
}

/// A row of `task_photos`, read or written.
nonisolated struct TaskPhotoRow: Codable, Sendable {
    let id: String
    let userID: String
    let taskID: String
    let objectPath: String
    let width: Int?
    let height: Int?
    let createdAt: Date

    /// The table's column names.
    enum CodingKeys: String, CodingKey {
        case id
        case userID = "user_id"
        case taskID = "task_id"
        case objectPath = "object_path"
        case width
        case height
        case createdAt = "created_at"
    }
}

/// A photo row read for its file alone.
nonisolated struct ObjectPathRow: Decodable, Sendable {
    let objectPath: String

    /// The table's column name.
    enum CodingKeys: String, CodingKey {
        case objectPath = "object_path"
    }
}

// --8<-- [start:off-main-actor]
/// The text a task's screen edits, sent as one typed update. A cleared note is written as an
/// explicit `null`, so the server's copy clears too, where a synthesized `Encodable` would leave
/// the column out.
nonisolated struct TaskTextPatch: Encodable, Sendable {
    let title: String
    let notes: String?

    /// The table's column names.
    enum CodingKeys: String, CodingKey {
        case title
        case notes
    }

    func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(title, forKey: .title)
        try container.encode(notes, forKey: .notes)
    }
}
// --8<-- [end:off-main-actor]

/// A row's key alone, as a read of ids returns it.
nonisolated struct RowKey: Codable, Sendable {
    let id: String
}

extension TaskRow {

    /// The task, marked when `pendingIDs` holds its id.
    func task(pendingIDs: Set<String>) -> TaskItem {
        TaskItem(
            id: id,
            userID: userID,
            title: title,
            notes: notes,
            isDone: done,
            createdAt: createdAt,
            hasPendingMutations: pendingIDs.contains(id)
        )
    }
}

extension TaskPhotoRow {

    /// The photo, marked when `pendingIDs` holds its id.
    func photo(pendingIDs: Set<String>) -> TaskPhoto {
        TaskPhoto(
            id: id,
            userID: userID,
            taskID: taskID,
            objectPath: objectPath,
            width: width,
            height: height,
            createdAt: createdAt,
            hasPendingMutations: pendingIDs.contains(id)
        )
    }
}
