import XCTest
@testable import SupabaseOfflineDemo

final class SupabaseOfflineDemoTests: XCTestCase {

    /// Verify that the Codable mapping matches the snake_case columns in
    /// `demo/supabase/schema.sql` (`is_done`, `created_at`, `updated_at`).
    func testTodoTaskDecodesSnakeCaseColumns() throws {
        let json = """
        {
            "id": "11111111-1111-1111-1111-111111111111",
            "title": "Buy milk",
            "notes": "Whole, not skim",
            "is_done": false,
            "created_at": "2026-05-22T15:00:00+00:00",
            "updated_at": "2026-05-22T15:00:00+00:00"
        }
        """.data(using: .utf8)!

        let task = try JSONDecoder().decode(TodoTask.self, from: json)

        XCTAssertEqual(task.id, "11111111-1111-1111-1111-111111111111")
        XCTAssertEqual(task.title, "Buy milk")
        XCTAssertEqual(task.notes, "Whole, not skim")
        XCTAssertEqual(task.isDone, false)
        XCTAssertEqual(task.createdAt, "2026-05-22T15:00:00+00:00")
        XCTAssertEqual(task.updatedAt, "2026-05-22T15:00:00+00:00")
    }

    /// Verify the inverse — that `isDone` is encoded as `is_done` so PATCH
    /// requests match the schema's column name.
    func testTodoTaskPatchEncodesSnakeCase() throws {
        let patch = TodoTaskPatch(title: "Updated title", notes: nil, isDone: true)

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(patch)
        let json = String(data: data, encoding: .utf8) ?? ""

        XCTAssertTrue(json.contains("\"is_done\":true"), "Expected is_done in: \(json)")
        XCTAssertTrue(json.contains("\"title\":\"Updated title\""), "Expected title in: \(json)")
        // notes is nil — when JSONEncoder skips nil by default, it should not appear at all.
        XCTAssertFalse(json.contains("\"notes\""), "Did not expect notes key in: \(json)")
    }

    /// Verify a fresh insert payload includes only the columns the app fills in,
    /// letting Postgres defaults populate id / created_at / updated_at.
    func testTodoTaskInsertOmitsServerSideDefaults() throws {
        let task = TodoTask(title: "New task")

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let json = String(data: try encoder.encode(task), encoding: .utf8) ?? ""

        XCTAssertTrue(json.contains("\"title\":\"New task\""), "Got: \(json)")
        XCTAssertTrue(json.contains("\"is_done\":false"), "Got: \(json)")
        // id / notes / created_at / updated_at are nil and should be omitted.
        XCTAssertFalse(json.contains("\"id\""), "Got: \(json)")
        XCTAssertFalse(json.contains("\"created_at\""), "Got: \(json)")
        XCTAssertFalse(json.contains("\"updated_at\""), "Got: \(json)")
    }

    /// Verify TaskPhoto maps the snake_case bucket / object path / size columns.
    func testTaskPhotoDecodesSnakeCaseColumns() throws {
        let json = """
        {
            "id": "22222222-2222-2222-2222-222222222222",
            "task_id": "11111111-1111-1111-1111-111111111111",
            "bucket_id": "task-photos",
            "object_path": "11111111-1111-1111-1111-111111111111/abc.jpg",
            "content_type": "image/jpeg",
            "size_bytes": 12345,
            "uploaded_at": "2026-05-22T15:05:00+00:00"
        }
        """.data(using: .utf8)!

        let photo = try JSONDecoder().decode(TaskPhoto.self, from: json)

        XCTAssertEqual(photo.taskId, "11111111-1111-1111-1111-111111111111")
        XCTAssertEqual(photo.bucketId, "task-photos")
        XCTAssertEqual(photo.objectPath, "11111111-1111-1111-1111-111111111111/abc.jpg")
        XCTAssertEqual(photo.contentType, "image/jpeg")
        XCTAssertEqual(photo.sizeBytes, 12345)
    }
}
