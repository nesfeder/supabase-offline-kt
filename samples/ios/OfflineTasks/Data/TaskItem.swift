// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation

/// The two tables the app reads and writes, by their names in the schema.
nonisolated enum Tables {

    /// `public.tasks`.
    static let tasks = "tasks"

    /// `public.task_photos`.
    static let taskPhotos = "task_photos"
}

/// A row of `tasks`. The app makes every id, so a task created offline can be opened, edited and
/// given photos before it reaches the server.
nonisolated struct TaskItem: Identifiable, Hashable, Sendable {

    /// The task's id: a UUID in lower case, as Postgres writes it.
    let id: String

    /// The user it belongs to.
    let userID: String

    /// What the task is; never blank once the server has it.
    let title: String

    /// Anything more; `nil` when there is none.
    let notes: String?

    /// Whether it is done.
    let isDone: Bool

    /// When it was created, by the device that created it.
    let createdAt: Date

    /// Whether a change to it waits in the mutation queue.
    let hasPendingMutations: Bool
}

/// A row of `task_photos`: one JPEG in the private `task-photos` bucket.
nonisolated struct TaskPhoto: Identifiable, Hashable, Sendable {

    /// The photo's id, in lower case.
    let id: String

    /// The user it belongs to.
    let userID: String

    /// The task it is attached to.
    let taskID: String

    /// Its file in the bucket: always `<user id>/<task id>/<photo id>.jpg`, in lower case.
    let objectPath: String

    /// Its size in pixels, when known, so the screen can reserve its space.
    let width: Int?

    /// Its height in pixels, when known.
    let height: Int?

    /// When it was attached.
    let createdAt: Date

    /// Whether its row waits in the mutation queue.
    let hasPendingMutations: Bool

    /// Where its upload stands while it waits on this device, picked here and not uploaded yet;
    /// `nil` once its file is stored.
    var pendingUpload: PendingPhoto.State?
}

/// The rules the schema enforces, so the app can stop a write the server would refuse.
nonisolated enum TaskRules {

    /// The longest title the schema accepts (`tasks_title_length`).
    static let titleMaxLength = 200

    /// The longest notes the schema accepts (`tasks_notes_length`).
    static let notesMaxLength = 2_000

    /// The title the Sync screen queues to show a refused write: blank, which the schema refuses.
    static let invalidTitle = "   "

    /// Whether the server would refuse `title`: blank (spaces, tabs or line breaks only) or too long.
    static func isTitleRefused(_ title: String) -> Bool {
        title.trimmingCharacters(in: CharacterSet(charactersIn: " \t\r\n")).isEmpty
            || characterCount(title) > titleMaxLength
    }

    /// How many characters the schema's `char_length` counts in `text`: Unicode scalars, so an
    /// emoji made of several scalars counts each one, as it does on the server.
    static func characterCount(_ text: String) -> Int {
        text.unicodeScalars.count
    }

    /// The first `maxCharacters` characters of `text`, counted as ``characterCount(_:)`` counts.
    static func truncated(_ text: String, to maxCharacters: Int) -> String {
        guard characterCount(text) > maxCharacters else { return text }
        var scalars = String.UnicodeScalarView()
        scalars.append(contentsOf: text.unicodeScalars.prefix(maxCharacters))
        return String(scalars)
    }
}

/// Ids made on the device.
nonisolated enum DeviceID {

    /// A new random id in lower case: the schema's paths and its `object_path` check compare text,
    /// and Postgres writes a UUID in lower case, while `uuidString` is upper case.
    static func make() -> String {
        UUID().uuidString.lowercased()
    }
}

/// Where a photo's file lives in the bucket.
nonisolated enum PhotoPath {

    /// The private bucket every photo lives in.
    static let bucket = "task-photos"

    /// The largest file the bucket accepts: 5 MB.
    static let maxBytes = 5 * 1_024 * 1_024

    /// The one path the schema allows for a photo: `<user id>/<task id>/<photo id>.jpg`. The ids are
    /// lower-case UUIDs, so the path never holds `%`, `?` or `#`, which the device's file cache
    /// cannot keep.
    static func objectPath(userID: String, taskID: String, photoID: String) -> String {
        "\(userID)/\(taskID)/\(photoID).jpg"
    }
}
