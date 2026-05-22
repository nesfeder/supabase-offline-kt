import Foundation

/// Mirrors the `public.tasks` row in `demo/supabase/schema.sql`. Named `TodoTask` to avoid
/// colliding with `Swift.Task` in this file's scope.
struct TodoTask: Codable, Equatable, Identifiable {
    var id: String?
    var title: String
    var notes: String?
    var isDone: Bool
    var createdAt: String?
    var updatedAt: String?

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case notes
        case isDone = "is_done"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }

    init(
        id: String? = nil,
        title: String,
        notes: String? = nil,
        isDone: Bool = false,
        createdAt: String? = nil,
        updatedAt: String? = nil
    ) {
        self.id = id
        self.title = title
        self.notes = notes
        self.isDone = isDone
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

/// Patch shape for PATCH `tasks?id=eq.<id>` — only writes the columns present in the JSON.
struct TodoTaskPatch: Codable, Equatable {
    var title: String?
    var notes: String?
    var isDone: Bool?

    enum CodingKeys: String, CodingKey {
        case title
        case notes
        case isDone = "is_done"
    }
}

/// Mirrors the `public.task_photos` row.
struct TaskPhoto: Codable, Equatable, Identifiable {
    var id: String?
    var taskId: String
    var bucketId: String
    var objectPath: String
    var contentType: String?
    var sizeBytes: Int64?
    var uploadedAt: String?

    enum CodingKeys: String, CodingKey {
        case id
        case taskId = "task_id"
        case bucketId = "bucket_id"
        case objectPath = "object_path"
        case contentType = "content_type"
        case sizeBytes = "size_bytes"
        case uploadedAt = "uploaded_at"
    }
}

/// View-model row for the detail screen photo grid.
struct TaskPhotoRow: Equatable, Identifiable {
    let id: String
    let objectPath: String
    let localFileUrl: String?
}
