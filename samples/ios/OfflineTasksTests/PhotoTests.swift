// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing

@testable import OfflineTasks

/// Photos through the offline bucket: uploaded by the app's own queue, kept once shown, shown
/// offline.
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
        try await test.addPhoto(to: task.id)
        try await eventually("the stored photo is listed") { detail.photos.count == 1 && detail.photos.first?.pendingUpload == nil }
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

    @Test("A photo whose row the server refuses at once takes its file with it, and stays failed")
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
        let filed = try await test.repository.filePhoto(try await PhotoEncoder.encode(jpegFixture()), forTask: task.id)

        // Then: the refusal is said, the file uploaded for the row is gone, and the device's copy stays
        let failed = try await test.pendingPhoto(filed.id, "the upload fails") { $0.state != .waiting && $0.state != .uploading }
        #expect(failed.state == .failed(reason: "The server refused it: its task isn’t on the server.", isRetryable: false))
        #expect(failed.attempts == 1)
        #expect(await test.server.requests.contains { $0.method == "POST" && $0.path.hasPrefix("/storage/v1/object/") })
        #expect(await test.server.objectPaths().isEmpty)
        #expect(FileManager.default.fileExists(atPath: test.repository.pendingPhotos.jpegURL(ofPhoto: filed.id).path(percentEncoded: false)))
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
        let filed = try await test.repository.filePhoto(try await PhotoEncoder.encode(jpegFixture()), forTask: task.id)

        // Then: the file stays for the row the queue will send, and the device's copy has gone
        try await eventually("the upload ends") { test.repository.pendingPhotos.photos.isEmpty }
        #expect(await test.server.objectPaths() == [filed.objectPath])
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

/// What the photo viewer and the Storage section do with a photo's file, through the offline bucket.
@Suite("Photo actions on the offline client", .serialized)
struct PhotoActionTests {

    /// A started app with two tasks, the first with one photo.
    private static func appWithPhoto() async throws -> (TestApp, TaskPhoto, TaskItem) {
        let test = TestApp()
        try await test.start()
        try await test.addTask("Paint the door")
        try await test.addTask("Sand the door")
        let painted = try await test.task(titled: "Paint the door")
        let sanded = try await test.task(titled: "Sand the door")
        try await eventually("both reach the server") { await test.server.rows(of: Tables.tasks).count == 2 }
        let photo = try await test.addPhoto(to: painted.id)
        return (test, photo, sanded)
    }

    @Test("Details read Storage's facts, both addresses and the device's copy")
    func details() async throws {
        // Given
        let (test, photo, _) = try await Self.appWithPhoto()
        _ = await test.repository.photoFile(for: photo)

        // When
        let details = await test.repository.photoDetails(for: photo)

        // Then
        #expect(details.inBucket?.name == photo.objectPath)
        #expect(details.inBucket?.contentType == "image/jpeg")
        #expect(details.inBucket?.metadata?["task_id"] == .string(photo.taskID))
        #expect(details.existsInBucket == true)
        #expect(details.authenticatedURL?.path().hasSuffix("/object/authenticated/task-photos/\(photo.objectPath)") == true)
        #expect(details.publicURL?.path().hasSuffix("/object/public/task-photos/\(photo.objectPath)") == true)
        #expect(details.onDevice?.sizeBytes == (await test.server.fileData(at: photo.objectPath))?.count)
        try await test.stop()
    }

    @Test("Share link signs a resized copy, and Share all signs every photo in one request")
    func shareLinks() async throws {
        // Given
        let (test, photo, _) = try await Self.appWithPhoto()

        // When
        let link = try await test.repository.shareLink(for: photo)
        let links = try await test.repository.shareLinks(for: [photo])

        // Then
        #expect(link.path().contains("/object/sign/task-photos/\(photo.objectPath)"))
        #expect(links.count == 1)
        let signing = await test.server.requests.first { $0.path.hasSuffix("/object/sign/task-photos/\(photo.objectPath)") }
        #expect(signing?.body.contains("\"width\":512") == true)
        try await test.stop()
    }

    @Test("Replace writes over the file at its path, and the row takes the new size")
    func replace() async throws {
        // Given
        let (test, photo, _) = try await Self.appWithPhoto()
        let replacement = try await PhotoEncoder.encode(SamplePhoto.jpeg())

        // When
        try await test.repository.replacePhoto(photo, with: replacement)

        // Then
        #expect(await test.server.fileData(at: photo.objectPath) == replacement.jpeg)
        try await eventually("the row has the new size") {
            await test.server.rows(of: Tables.taskPhotos).first?["width"] == .int(replacement.width)
        }
        try await test.stop()
    }

    @Test("Move takes the file and the row to the other task; Duplicate adds a copy there")
    func moveAndCopy() async throws {
        // Given
        let (test, photo, other) = try await Self.appWithPhoto()

        // When
        try await test.repository.copyPhoto(photo, toTask: other.id)
        try await test.repository.movePhoto(photo, toTask: other.id)

        // Then
        let moved = PhotoPath.objectPath(userID: photo.userID, taskID: other.id, photoID: photo.id)
        let paths = await test.server.objectPaths()
        #expect(paths.count == 2)
        #expect(paths.contains(moved))
        #expect(!paths.contains(photo.objectPath))
        try await eventually("both rows are the other task's") {
            await test.server.rows(of: Tables.taskPhotos).allSatisfy { $0["task_id"] == .string(other.id) }
        }
        try await test.stop()
    }

    @Test("The bucket's listing pages, sorts and filters by name")
    func bucketListing() async throws {
        // Given
        let (test, photo, _) = try await Self.appWithPhoto()

        // When
        let folders = try await test.repository.bucketEntries(in: "", page: BucketPage())
        let files = try await test.repository.bucketEntries(in: photo.taskID, page: BucketPage(newestFirst: true))
        let none = try await test.repository.bucketEntries(in: photo.taskID, page: BucketPage(search: "no such name"))

        // Then
        #expect(folders.map(\.name) == [photo.taskID])
        #expect(folders.first?.id == nil)
        #expect(files.map(\.name) == ["\(photo.id).jpg"])
        #expect(files.first?.metadata?["mimetype"] == "image/jpeg")
        #expect(none.isEmpty)
        let listing = await test.server.requests.first { $0.path.hasSuffix("/object/list/task-photos") }
        #expect(listing?.body.contains("\"limit\":20") == true)
        try await test.stop()
    }

    @Test("Upload through a signed URL adds a photo to the newest task")
    func signedUpload() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        try await test.addTask("Paint the door")
        let task = try await test.task(titled: "Paint the door")
        try await eventually("it reaches the server") { await test.server.rows(of: Tables.tasks).count == 1 }

        // When
        test.app.sync.run(.signedUpload, newestTaskID: task.id)
        try await eventually("Try it says so") { test.app.sync.tryItResults[.signedUpload] != nil }

        // Then
        #expect(test.app.sync.tryItResults[.signedUpload] == "Uploaded through a signed URL, and added to your newest task.")
        #expect(await test.server.objectPaths().count == 1)
        #expect(await test.server.requests.contains { $0.method == "PUT" && $0.path.contains("/object/upload/sign/task-photos/") })
        try await eventually("its row is there") { await test.server.rows(of: Tables.taskPhotos).count == 1 }
        try await test.stop()
    }
}
