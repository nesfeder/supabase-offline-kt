// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing

@testable import OfflineTasks

/// Photos through the offline bucket: uploaded online, kept once shown, shown offline.
@Suite("Photos on the offline client", .serialized)
struct PhotoTests {

    @Test("A photo shown once online still shows offline, from the device's copy")
    func photoCachedThenShownOffline() async throws {
        // Given: a task with a photo, online
        let test = TestApp()
        try await test.start()
        try await test.addTask("Paint the door")
        let task = try await test.task(titled: "Paint the door")
        let detail = TaskDetailModel(taskID: task.id, repository: test.repository, failures: test.app.failureSink)
        let watching = Task { await detail.observePhotos(isSimulatingOffline: false) }
        try await test.repository.attachPhoto(taskID: task.id, photo: try await PhotoEncoder.encode(jpegFixture()))
        try await eventually("the photo is listed") { detail.photos.count == 1 }
        let photo = try #require(detail.photos.first)
        let userID = try #require(test.session.userID)
        #expect(photo.objectPath == "\(userID)/\(task.id)/\(photo.id).jpg")
        #expect(photo.objectPath == photo.objectPath.lowercased())
        #expect(await test.server.objectPaths() == [photo.objectPath])

        // When: shown once online, then offline
        await detail.loadImage(for: photo)
        guard case .loaded = detail.images[photo.id] else {
            Issue.record("The photo did not load online: \(String(describing: detail.images[photo.id]))")
            return
        }
        try await test.setOffline(true)
        let requestsOffline = await test.server.requests.count
        await detail.loadImage(for: photo)

        // Then: it shows, and nothing reached the server
        guard case .onDevice = await test.repository.photoFile(for: photo) else {
            Issue.record("The photo is not on the device")
            return
        }
        guard case .loaded = detail.images[photo.id] else {
            Issue.record("The photo did not show offline: \(String(describing: detail.images[photo.id]))")
            return
        }
        #expect(await test.server.requests.count == requestsOffline)

        // And a photo the device does not hold waits for the network
        try await test.repository.clearLocalData()
        #expect(await test.repository.photoFile(for: photo) == .notOnDevice)
        watching.cancel()
        try await test.stop()
    }

    @Test("Offline, Add photo fails at once with the network's reason and nothing is uploaded")
    func attachOfflineNeedsTheNetwork() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        try await test.addTask("Sort the shed")
        let task = try await test.task(titled: "Sort the shed")
        try await test.setOffline(true)

        // When
        let failure = await #expect(throws: SampleFailure.self) {
            try await test.repository.attachPhoto(taskID: task.id, photo: try await PhotoEncoder.encode(jpegFixture()))
        }

        // Then
        #expect(failure == .needsNetwork)
        #expect(failure?.message == "That needs the network, and you’re offline.")
        #expect(await test.server.objectPaths().isEmpty)
        try await test.stop()
    }

    @Test("A photo whose row the server refuses at once takes its file with it")
    func refusedRowRemovesItsFile() async throws {
        // Given: the server refuses every photo row, as it does when the task is gone from it
        let test = TestApp()
        try await test.start()
        try await test.addTask("Mend the gate")
        let task = try await test.task(titled: "Mend the gate")
        try await eventually("the task reaches the server") { await test.server.rows(of: Tables.tasks).count == 1 }
        let refusal = Self.missingTaskRefusal
        await test.server.intercept { request, _ in
            let isPhotoRow = request.method == "POST" && request.url.path().hasSuffix("/rest/v1/\(Tables.taskPhotos)")
            return isPhotoRow ? refusal : nil
        }

        // When
        let failure = await #expect(throws: SampleFailure.self) {
            try await test.repository.attachPhoto(taskID: task.id, photo: try await PhotoEncoder.encode(jpegFixture()))
        }

        // Then: the refusal is said, and the file uploaded for the row is gone
        guard case .writeRefused(_, let serverError)? = failure else {
            Issue.record("Expected a refused write, got \(String(describing: failure))")
            return
        }
        #expect(serverError.code == "23503")
        #expect(await test.server.requests.contains { $0.method == "POST" && $0.path.hasPrefix("/storage/v1/object/") })
        #expect(await test.server.objectPaths().isEmpty)
        try await test.stop()
    }

    @Test("A photo whose row only fails to arrive keeps its file, and its row waits in the queue")
    func unansweredRowKeepsItsFile() async throws {
        // Given: the server fails every photo row in a way that may pass
        let test = TestApp()
        try await test.start()
        try await test.addTask("Oil the hinges")
        let task = try await test.task(titled: "Oil the hinges")
        try await eventually("the task reaches the server") { await test.server.rows(of: Tables.tasks).count == 1 }
        await test.server.intercept { request, _ in
            let isPhotoRow = request.method == "POST" && request.url.path().hasSuffix("/rest/v1/\(Tables.taskPhotos)")
            return isPhotoRow ? .status(503) : nil
        }

        // When
        let photo = try await test.repository.attachPhoto(taskID: task.id, photo: try await PhotoEncoder.encode(jpegFixture()))

        // Then: the file stays for the row the queue will send
        #expect(await test.server.objectPaths() == [photo.objectPath])
        #expect(try await test.repository.client.pendingMutationCount(table: Tables.taskPhotos) == 1)
        try await test.stop()
    }

    /// PostgREST's answer to a photo row whose task is not on the server.
    private static let missingTaskRefusal = FakeServer.Response.json(
        #"{"code":"23503","message":"insert or update on table \"task_photos\" violates foreign key constraint \"task_photos_task_fkey\"","details":null,"hint":null}"#,
        status: 409
    )

    @Test("A picked image is re-encoded as a JPEG under the bucket's limit, its size kept")
    func encodesJPEG() async throws {
        // When
        let photo = try await PhotoEncoder.encode(jpegFixture())

        // Then
        #expect(photo.jpeg.starts(with: [0xFF, 0xD8]))
        #expect(photo.jpeg.count < PhotoPath.maxBytes)
        #expect(photo.width == 24)
        #expect(photo.height == 16)
    }
}
