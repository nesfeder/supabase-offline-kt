// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing

@testable import OfflineTasks

/// Photos picked on this device: kept in the app's own queue, shown from it at once, and uploaded
/// by the uploader as the network allows, the file before its row.
@Suite("Photos picked on this device", .serialized)
struct PendingPhotoTests {

    // MARK: - The queue on disk

    @Test("The queue keeps each photo's JPEG and record on disk, and a later launch reads them back")
    func recordRoundTrip() async throws {
        // Given: two photos, the first being uploaded and the second failed, and what a crash
        // between a JPEG and its record, or a damaged record, leaves behind
        let folder = URL.temporaryDirectory.appending(path: "PendingPhotoTests-\(UUID())", directoryHint: .isDirectory)
        defer { try? FileManager.default.removeItem(at: folder) }
        let queue = PendingPhotoQueue(folder: folder)
        try queue.load()
        let first = Self.pendingPhoto(pickedAt: Date(timeIntervalSince1970: 1_760_000_000.125))
        let second = Self.pendingPhoto(pickedAt: Date(timeIntervalSince1970: 1_760_000_000.25))
        try await queue.add(second, jpeg: Data([0xFF, 0xD8, 2]))
        try await queue.add(first, jpeg: Data([0xFF, 0xD8, 1]))
        #expect(queue.photos.map(\.id) == [first.id, second.id])
        #expect(try queue.startNextUpload(of: Self.userID)?.id == first.id)
        try queue.markFailed(second, reason: "Storage refused it (403): no", isRetryable: false)
        try Data([1]).write(to: folder.appending(path: "orphan.jpg"))
        try Data("{".utf8).write(to: folder.appending(path: "damaged.json"))

        // When: a later launch reads the folder
        let later = PendingPhotoQueue(folder: folder)
        #expect(throws: SampleFailure.pendingPhotoUnreadable(count: 1)) { try later.load() }

        // Then: both are back, oldest picked first; the one being uploaded waits again
        #expect(later.photos.map(\.id) == [first.id, second.id])
        #expect(later.photos.first == first)
        #expect(later.photos.last?.state == .failed(reason: "Storage refused it (403): no", isRetryable: false))
        #expect(later.photos.last?.attempts == 1)
        #expect(try await later.jpeg(of: first) == Data([0xFF, 0xD8, 1]))
        #expect(!FileManager.default.fileExists(atPath: folder.appending(path: "orphan.jpg").path(percentEncoded: false)))
        let record = try JSONSerialization.jsonObject(with: Data(contentsOf: folder.appending(path: "\(second.id).json")))
        let keys = try #require(record as? [String: Any]).keys
        #expect(
            Set(keys) == ["photo_id", "task_id", "user_id", "object_path", "picked_at", "content_type", "width", "height", "state", "attempts"]
        )
    }

    @Test("A photo both stored and waiting shows once, from the device, among its task's photos oldest first")
    func merge() {
        // Given: two stored photos of the task, one of another task, and two waiting photos, one
        // of which has just had its row written
        let start = Date(timeIntervalSince1970: 1_760_000_000)
        let stored = Self.row(taskID: "task", createdAt: start)
        let other = Self.row(taskID: "other task", createdAt: start)
        let failed = Self.pendingPhoto(taskID: "task", pickedAt: start + 1, state: .failed(reason: "No", isRetryable: true))
        let uploaded = Self.pendingPhoto(taskID: "task", pickedAt: start + 2, state: .uploading)

        // When
        let photos = TaskRepository.photos(
            ofTask: "task",
            rows: [other, TaskPhotoRow(uploaded), stored],
            queuedRowIDs: [uploaded.id],
            pending: [failed, uploaded]
        )

        // Then
        #expect(photos.map(\.id) == [stored.id, failed.id, uploaded.id])
        #expect(photos.map(\.pendingUpload) == [nil, failed.state, .uploading])
        #expect(photos.map(\.hasPendingMutations) == [false, false, false])
    }

    // MARK: - Showing and uploading

    @Test("Offline, a picked photo is listed for its task at once, shown from the device, and nothing is sent")
    func listedBeforeUpload() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        try await test.addTask("Frame the print")
        let task = try await test.task(titled: "Frame the print")
        try await test.setOffline(true)
        let detail = TaskDetailModel(taskID: task.id, repository: test.repository, failures: test.app.failureSink)
        let watching = Task { await detail.observePhotos(isSimulatingOffline: true) }
        let requestsBefore = await test.server.requests.count

        // When
        let filed = try await test.repository.filePhoto(try await PhotoEncoder.encode(jpegFixture()), forTask: task.id)

        // Then: the task's photos, the Photos tab and the list's count all have it, from the device
        try await eventually("the photo is listed") { detail.photos.map(\.id) == [filed.id] }
        #expect(detail.photos.first?.pendingUpload == .waiting)
        #expect(test.app.photos.photos.map(\.id) == [filed.id])
        #expect(test.app.tasks.photoCount(ofTask: task.id) == 1)
        await detail.loadImage(for: try #require(detail.photos.first))
        guard case .loaded = detail.images[filed.id] else {
            Issue.record("The photo did not show from the device: \(String(describing: detail.images[filed.id]))")
            return
        }
        #expect(await test.server.requests.count == requestsBefore)
        watching.cancel()
        try await test.stop()
    }

    @Test("Online, the uploader stores the file, then writes the row, then has the file cache download it")
    func uploadsBeforeTheRow() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        try await test.addTask("Hang the mirror")
        let task = try await test.task(titled: "Hang the mirror")
        try await eventually("the task reaches the server") { await test.server.rows(of: Tables.tasks).count == 1 }
        let requestsBefore = await test.server.requests.count

        // When
        let filed = try await test.repository.filePhoto(try await PhotoEncoder.encode(jpegFixture()), forTask: task.id)

        // Then: the file, then the row, then the download; the device's own copy is gone
        try await eventually("the upload lands") { test.repository.pendingPhotos.photos.isEmpty }
        let requests = Array(await test.server.requests[requestsBefore...])
        let upload = requests.firstIndex { $0.method == "POST" && $0.path == "/storage/v1/object/task-photos/\(filed.objectPath)" }
        let row = requests.firstIndex { $0.method == "POST" && $0.path == "/rest/v1/\(Tables.taskPhotos)" }
        let download = requests.firstIndex { $0.method == "GET" && $0.path.hasSuffix("/task-photos/\(filed.objectPath)") }
        let (uploadIndex, rowIndex, downloadIndex) = (try #require(upload), try #require(row), try #require(download))
        #expect(uploadIndex < rowIndex)
        #expect(rowIndex < downloadIndex)
        #expect(try await test.repository.photoBucket.cachedFile(path: filed.objectPath) != nil)
        #expect(await test.server.rows(of: Tables.taskPhotos).map { $0["object_path"] } == [.string(filed.objectPath)])
        #expect(!FileManager.default.fileExists(atPath: test.repository.pendingPhotos.jpegURL(ofPhoto: filed.id).path(percentEncoded: false)))
        try await test.stop()
    }

    @Test("A failed upload keeps the device's copy, marked with the app's sentence, and Retry uploads it")
    func failedUploadThenRetry() async throws {
        // Given: Storage fails every upload
        let test = TestApp()
        try await test.start()
        try await test.addTask("Paint the shed")
        let task = try await test.task(titled: "Paint the shed")
        try await eventually("the task reaches the server") { await test.server.rows(of: Tables.tasks).count == 1 }
        await test.server.intercept(Self.failingUploads(with: Self.storageDown))
        let filed = try await test.repository.filePhoto(try await PhotoEncoder.encode(jpegFixture()), forTask: task.id)
        let failed = try await test.pendingPhoto(filed.id, "the upload fails") { $0.state != .waiting && $0.state != .uploading }
        #expect(failed.state == .failed(reason: "Storage refused it (500): Storage is down", isRetryable: true))
        #expect(failed.attempts == 1)
        #expect(FileManager.default.fileExists(atPath: test.repository.pendingPhotos.jpegURL(ofPhoto: filed.id).path(percentEncoded: false)))
        #expect(await test.server.rows(of: Tables.taskPhotos).isEmpty)

        // When: Storage is back, and the user taps Retry
        await test.server.intercept(nil)
        try test.repository.retryUpload(of: failed.photo)

        // Then
        try await eventually("the retry lands") { test.repository.pendingPhotos.photos.isEmpty }
        #expect(await test.server.objectPaths() == [filed.objectPath])
        #expect(await test.server.rows(of: Tables.taskPhotos).count == 1)
        try await test.stop()
    }

    @Test("Nothing uploads while the network is switched off; switching it on uploads, and retries a failure")
    func waitsForTheNetwork() async throws {
        // Given: a photo picked with the network switched off
        let test = TestApp()
        try await test.start()
        try await test.addTask("Wash the car")
        let task = try await test.task(titled: "Wash the car")
        try await eventually("the task reaches the server") { await test.server.rows(of: Tables.tasks).count == 1 }
        try await test.setOffline(true)
        let filed = try await test.repository.filePhoto(try await PhotoEncoder.encode(jpegFixture()), forTask: task.id)

        // Then: what must not happen cannot be awaited, so the uploader is given time to act
        try await Task.sleep(for: .milliseconds(300))
        #expect(test.repository.pendingPhotos.photo(id: filed.id)?.state == .waiting)
        #expect(await test.server.requests.contains { $0.path.hasPrefix("/storage/v1/object/task-photos/") } == false)

        // When: the network is switched on
        try await test.setOffline(false)

        // Then
        try await eventually("the upload lands") { test.repository.pendingPhotos.photos.isEmpty }
        #expect(await test.server.objectPaths() == [filed.objectPath])

        // And a failure another attempt can help is tried again as the network comes back
        await test.server.intercept(Self.failingUploads(with: .status(503)))
        let second = try await test.repository.filePhoto(try await PhotoEncoder.encode(jpegFixture()), forTask: task.id)
        _ = try await test.pendingPhoto(second.id, "the upload fails") { $0.state != .waiting && $0.state != .uploading }
        await test.server.intercept(nil)
        try await test.setOffline(true)
        try await test.setOffline(false)
        try await eventually("the upload lands once the network is back") { test.repository.pendingPhotos.photos.isEmpty }
        #expect(await test.server.objectPaths().count == 2)
        try await test.stop()
    }

    @Test("New task files its photos once the task is saved, and back online they upload behind it")
    func newTaskWithPhotos() async throws {
        // Given: offline
        let test = TestApp()
        try await test.start()
        try await test.setOffline(true)
        let photos = [try await PhotoEncoder.encode(jpegFixture()), try await PhotoEncoder.encode(SamplePhoto.jpeg())]

        // When: a task that needs a connection fails, and so files nothing; one that waits files both
        let refused = await test.app.tasks.addTask(title: "Hang the shelf", notes: "", needsConnection: true, photos: photos)
        #expect(refused != nil)
        #expect(test.repository.pendingPhotos.photos.isEmpty)
        let saved = await test.app.tasks.addTask(title: "Hang the shelf", notes: "", needsConnection: false, photos: photos)

        // Then: both wait for the task, oldest picked first
        #expect(saved == nil)
        #expect(test.app.notice == "Saved on this device — it syncs when the server can be reached; its 2 photos upload when the network allows")
        let task = try await test.task(titled: "Hang the shelf")
        #expect(test.repository.pendingPhotos.photos(ofTask: task.id).count == 2)

        // When: back online
        try await test.setOffline(false)

        // Then: the task's row first; each photo's file before its row
        try await eventually("both photos land") { test.repository.pendingPhotos.photos.isEmpty }
        let requests = await test.server.requests
        let taskRow = try #require(requests.firstIndex { $0.method == "POST" && $0.path == "/rest/v1/\(Tables.tasks)" })
        let photoRows = requests.indices.filter { requests[$0].method == "POST" && requests[$0].path == "/rest/v1/\(Tables.taskPhotos)" }
        let uploads = requests.indices.filter { requests[$0].method == "POST" && requests[$0].path.hasPrefix("/storage/v1/object/task-photos/") }
        #expect(photoRows.count == 2)
        #expect(uploads.count == 2)
        #expect(photoRows.allSatisfy { $0 > taskRow })
        #expect(zip(uploads, photoRows).allSatisfy { $0 < $1 })
        #expect(await test.server.rows(of: Tables.taskPhotos).count == 2)
        try await test.stop()
    }

    // MARK: - Deletes and clears

    @Test("Deleting a task removes its photos waiting to upload, and only its")
    func deleteTaskRemovesItsPendingPhotos() async throws {
        // Given: offline, two photos waiting for one task and one for another
        let test = TestApp()
        try await test.start()
        try await test.setOffline(true)
        try await test.addTask("Clean the oven")
        try await test.addTask("Clean the fridge")
        let oven = try await test.task(titled: "Clean the oven")
        let fridge = try await test.task(titled: "Clean the fridge")
        let photo = try await PhotoEncoder.encode(jpegFixture())
        let ovenPhotos = [try await test.repository.filePhoto(photo, forTask: oven.id), try await test.repository.filePhoto(photo, forTask: oven.id)]
        let fridgePhoto = try await test.repository.filePhoto(photo, forTask: fridge.id)

        // When
        try await test.repository.deleteTask(id: oven.id)

        // Then
        #expect(test.repository.pendingPhotos.photos.map(\.id) == [fridgePhoto.id])
        for removed in ovenPhotos {
            #expect(!FileManager.default.fileExists(atPath: test.repository.pendingPhotos.jpegURL(ofPhoto: removed.id).path(percentEncoded: false)))
        }
        try await test.stop()
    }

    @Test("Clear local cache deletes every photo waiting to upload")
    func clearEmptiesTheQueue() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        try await test.setOffline(true)
        try await test.addTask("Tidy the desk")
        let task = try await test.task(titled: "Tidy the desk")
        try await test.repository.filePhoto(try await PhotoEncoder.encode(jpegFixture()), forTask: task.id)

        // When
        try await test.repository.clearLocalData()

        // Then
        #expect(test.repository.pendingPhotos.photos.isEmpty)
        #expect(!FileManager.default.fileExists(atPath: test.repository.pendingPhotos.folder.path(percentEncoded: false)))
        try await test.stop()
    }

    @Test("Start over deletes every photo waiting to upload, and the next user's photos upload")
    func startOverEmptiesTheQueue() async throws {
        // Given: a photo Storage refused for good, which stays until removed
        let test = TestApp()
        try await test.start()
        try await test.addTask("Mow the lawn")
        let task = try await test.task(titled: "Mow the lawn")
        try await eventually("the task reaches the server") { await test.server.rows(of: Tables.tasks).count == 1 }
        await test.server.intercept(Self.failingUploads(with: Self.policyRefusal))
        let refused = try await test.repository.filePhoto(try await PhotoEncoder.encode(jpegFixture()), forTask: task.id)
        let failed = try await test.pendingPhoto(refused.id, "the upload is refused") { $0.state != .waiting && $0.state != .uploading }
        #expect(failed.state == .failed(reason: "Storage refused it (400): new row violates row-level security policy", isRetryable: false))
        await test.server.intercept(nil)

        // When
        try await test.repository.startOver()

        // Then: the queue is empty, and a photo of the next user's uploads
        #expect(test.repository.pendingPhotos.photos.isEmpty)
        #expect(!FileManager.default.fileExists(atPath: test.repository.pendingPhotos.jpegURL(ofPhoto: refused.id).path(percentEncoded: false)))
        test.startReads()
        try await test.addTask("Water the lawn")
        let next = try await test.task(titled: "Water the lawn")
        try await test.addPhoto(to: next.id)
        #expect(await test.server.objectPaths().count == 1)
        try await test.stop()
    }

    // MARK: - Helpers

    /// The user the queue tests' photos belong to.
    private static let userID = DeviceID.make()

    /// A photo of `taskID` picked at `pickedAt`, in `state`.
    private static func pendingPhoto(taskID: String = "task", pickedAt: Date, state: PendingPhoto.State = .waiting) -> PendingPhoto {
        let id = DeviceID.make()
        return PendingPhoto(
            id: id,
            taskID: taskID,
            userID: userID,
            objectPath: PhotoPath.objectPath(userID: userID, taskID: taskID, photoID: id),
            pickedAt: pickedAt,
            contentType: "image/jpeg",
            width: 24,
            height: 16,
            state: state
        )
    }

    /// A stored photo row of `taskID`, created at `createdAt`.
    private static func row(taskID: String, createdAt: Date) -> TaskPhotoRow {
        let id = DeviceID.make()
        return TaskPhotoRow(
            id: id,
            userID: userID,
            taskID: taskID,
            objectPath: PhotoPath.objectPath(userID: userID, taskID: taskID, photoID: id),
            width: 24,
            height: 16,
            createdAt: createdAt
        )
    }

    /// An interception answering every photo upload with `response`.
    private static func failingUploads(with response: FakeServer.Response) -> @Sendable (FakeServer.Request, String?) -> FakeServer.Response? {
        { request, _ in
            request.method == "POST" && request.url.path().hasPrefix("/storage/v1/object/task-photos/") ? response : nil
        }
    }

    /// Storage's answer when it fails, in a way a later attempt may not.
    private static let storageDown = FakeServer.Response.json(
        #"{"statusCode":"500","error":"internal_error","message":"Storage is down"}"#,
        status: 500
    )

    /// Storage's answer to an upload its policies refuse: an HTTP 400 that says 403 inside.
    private static let policyRefusal = FakeServer.Response.json(
        #"{"statusCode":"403","error":"Unauthorized","message":"new row violates row-level security policy"}"#,
        status: 400
    )
}
