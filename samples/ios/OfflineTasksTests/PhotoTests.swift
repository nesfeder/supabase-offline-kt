// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
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
        test.app.tasks.addTask(title: "Paint the door", notes: "")
        let task = try await test.task(titled: "Paint the door")
        let detail = TaskDetailModel(taskID: task.id, repository: test.repository, failures: test.app.failureSink)
        let watching = Task { await detail.observePhotos() }
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
        test.app.tasks.addTask(title: "Sort the shed", notes: "")
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
