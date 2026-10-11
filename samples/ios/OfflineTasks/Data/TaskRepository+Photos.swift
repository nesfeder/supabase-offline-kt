// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// The photos: files in the private `task-photos` bucket, through the offline client's Storage,
/// which keeps every download on the device. Storage writes are never queued: each one here needs
/// the network, and a photo's row is written only once its file is where the row says.
extension TaskRepository {

    /// How long a shared link to a photo works.
    static let shareLinkLifetimeSeconds = 60 * 60

    /// The width of the copy a shared link serves.
    static let shareLinkWidth = 512

    // --8<-- [start:bucket]
    /// The bucket every photo lives in, through the offline client: its downloads are kept on the
    /// device and served from there offline.
    var photoBucket: OfflineBucketApi {
        client.storage.from(PhotoPath.bucket)
    }
    // --8<-- [end:bucket]

    // --8<-- [start:upload]
    /// Uploads `photo` for the task, then writes its row. The file goes first, so a row never names
    /// a file that is not there; a row the server refuses at once takes its file with it.
    ///
    /// - Throws: ``SampleFailure/needsNetwork`` at once while offline: uploads are never queued.
    @discardableResult
    func attachPhoto(taskID: String, photo: EncodedPhoto) async throws -> TaskPhoto {
        guard canReachNetwork else { throw SampleFailure.needsNetwork }
        let userID = try signedInUserID()
        let photoID = DeviceID.make()
        let objectPath = PhotoPath.objectPath(userID: userID, taskID: taskID, photoID: photoID)
        do {
            try await photoBucket.upload(
                objectPath,
                data: photo.jpeg,
                options: OfflineBucketApi.UploadOptions(
                    // The bucket takes JPEGs only.
                    contentType: "image/jpeg",
                    // A path is never reused, so there is nothing to write over.
                    upsert: false,
                    // Kept with the file, for Storage's own listing and the Details sheet.
                    userMetadata: ["task_id": .string(taskID)]
                )
            )
        } catch {
            throw SampleFailure.storage(error)
        }
        let row = TaskPhotoRow(photoID: photoID, userID: userID, taskID: taskID, objectPath: objectPath, photo: photo)
        try await insertRow(of: row)
        return row.photo(pendingIDs: [])
    }
    // --8<-- [end:upload]

    /// Deletes a photo's file, then its row. The file needs the network.
    func deletePhoto(_ photo: TaskPhoto) async throws {
        guard canReachNetwork else { throw SampleFailure.needsNetwork }
        try await deleteFiles([photo.objectPath])
        try await write(taskTitle: nil) {
            try await client.from(Tables.taskPhotos).delete().eq("id", value: photo.id).execute()
        }
    }

    // --8<-- [start:cached-file]
    /// Where a photo's file stands on this device: kept, or fetched now and kept when it is not
    /// and the server can be asked.
    ///
    /// A private file is asked for only once a user is signed in: without the user's token,
    /// Storage answers "not found", which would drop a copy kept earlier.
    func photoFile(for photo: TaskPhoto) async -> PhotoFileState {
        do {
            if let file = try await photoBucket.cachedFile(path: photo.objectPath) { return .onDevice(file) }
            guard session.userID != nil, canReachNetwork else { return .notOnDevice }
            return .onDevice(try await photoBucket.ensureCachedFile(path: photo.objectPath))
        } catch {
            switch SampleFailure.storage(error) as? SampleFailure {
            case .needsNetwork?: return .notOnDevice
            case .photoMissing?: return .missing
            default: return .unavailable
            }
        }
    }
    // --8<-- [end:cached-file]

    // --8<-- [start:download]
    /// The bytes of `file`, the device's copy of `photo`'s file: read from disk at its URL, or, in
    /// demo mode, through the bucket's download, which answers from the device's copy when the
    /// network fails.
    func photoData(of file: CachedFile, for photo: TaskPhoto) async throws -> Data {
        switch photoBytes {
        case .cachedFile:
            return try await Self.contents(of: file.url)
        case .bucketDownload:
            return try await photoBucket.download(path: photo.objectPath)
        }
    }
    // --8<-- [end:download]

    /// The contents of the file at `url`, read off the main actor.
    @concurrent
    nonisolated private static func contents(of url: URL) async throws -> Data {
        try Data(contentsOf: url)
    }

    // MARK: - Sharing

    // --8<-- [start:share-photo]
    /// The device's copy of `photo`'s file, fetched first when there is none, for the share sheet:
    /// its ``CachedFile/url`` is a file URL any app can be handed. The file is the cache's own, to
    /// read and share, never to move or write.
    func shareableFile(for photo: TaskPhoto) async throws -> URL {
        do {
            return try await photoBucket.ensureCachedFile(path: photo.objectPath).url
        } catch {
            throw SampleFailure.storage(error)
        }
    }
    // --8<-- [end:share-photo]

    // --8<-- [start:share-link]
    /// A link to a 512-pixel-wide copy of `photo` that works for an hour, for anyone who has it:
    /// Storage signs it, and resizes the image when the link is opened. It needs the network.
    func shareLink(for photo: TaskPhoto) async throws -> URL {
        try await photoBucket.createSignedURL(
            path: photo.objectPath,
            expiresIn: Self.shareLinkLifetimeSeconds,
            transform: OfflineBucketApi.ImageTransformation(width: Self.shareLinkWidth, resize: .contain, quality: 80)
        )
    }
    // --8<-- [end:share-link]

    // --8<-- [start:share-all-links]
    /// Links to every photo of `photos`, each working for an hour, signed in one request; a photo
    /// Storage could not sign is left out.
    func shareLinks(for photos: [TaskPhoto]) async throws -> [URL] {
        try await photoBucket.createSignedURLs(paths: photos.map(\.objectPath), expiresIn: Self.shareLinkLifetimeSeconds)
            .filter { $0.error == nil }
            .map(\.signedURL)
    }
    // --8<-- [end:share-all-links]

    // MARK: - Details

    // --8<-- [start:photo-details]
    /// What Storage and the device know about `photo`'s file. The bucket's own facts need the
    /// network and are left out without it; the device's copy is read without asking.
    func photoDetails(for photo: TaskPhoto) async -> PhotoDetails {
        let onDevice = try? await photoBucket.cachedFile(path: photo.objectPath)
        guard canReachNetwork else { return PhotoDetails(onDevice: onDevice) }
        async let details = photoBucket.info(path: photo.objectPath)
        async let exists = photoBucket.exists(path: photo.objectPath)
        async let publicURL = photoBucket.publicURL(path: photo.objectPath)
        async let authenticatedURL = photoBucket.authenticatedURL(path: photo.objectPath)
        return PhotoDetails(
            inBucket: try? await details,
            existsInBucket: try? await exists,
            publicURL: try? await publicURL,
            authenticatedURL: try? await authenticatedURL,
            onDevice: onDevice
        )
    }
    // --8<-- [end:photo-details]

    // MARK: - Changing a photo

    // --8<-- [start:replace-photo]
    /// Writes `replacement` over `photo`'s file, at the same path, and its new size into the row.
    /// The device's copy of the old file is dropped, so the next look downloads the new one.
    func replacePhoto(_ photo: TaskPhoto, with replacement: EncodedPhoto) async throws {
        guard canReachNetwork else { throw SampleFailure.needsNetwork }
        do {
            try await photoBucket.update(
                photo.objectPath,
                data: replacement.jpeg,
                options: OfflineBucketApi.UploadOptions(contentType: "image/jpeg")
            )
        } catch {
            throw SampleFailure.storage(error)
        }
        try await write(taskTitle: nil) {
            try await client.from(Tables.taskPhotos)
                .update(["width": .int(replacement.width), "height": .int(replacement.height)])
                .eq("id", value: photo.id)
                .execute()
        }
    }
    // --8<-- [end:replace-photo]

    // --8<-- [start:move-photo]
    /// Moves `photo` to task `taskID`: the file to the path the schema gives the other task, then
    /// the row to match. A row the server refuses puts the file back.
    func movePhoto(_ photo: TaskPhoto, toTask taskID: String) async throws {
        guard canReachNetwork else { throw SampleFailure.needsNetwork }
        let destination = PhotoPath.objectPath(userID: photo.userID, taskID: taskID, photoID: photo.id)
        do {
            try await photoBucket.move(from: photo.objectPath, to: destination)
        } catch {
            throw SampleFailure.storage(error)
        }
        do {
            try await write(taskTitle: nil) {
                try await client.from(Tables.taskPhotos)
                    .update(["task_id": .string(taskID), "object_path": .string(destination)])
                    .eq("id", value: photo.id)
                    .execute()
            }
        } catch {
            try? await photoBucket.move(from: destination, to: photo.objectPath)
            throw error
        }
    }
    // --8<-- [end:move-photo]

    // --8<-- [start:copy-photo]
    /// Copies `photo` to task `taskID`, as a new photo: the file to a new path, then its row.
    func copyPhoto(_ photo: TaskPhoto, toTask taskID: String) async throws {
        guard canReachNetwork else { throw SampleFailure.needsNetwork }
        let photoID = DeviceID.make()
        let destination = PhotoPath.objectPath(userID: photo.userID, taskID: taskID, photoID: photoID)
        do {
            try await photoBucket.copy(from: photo.objectPath, to: destination)
        } catch {
            throw SampleFailure.storage(error)
        }
        let row = TaskPhotoRow(
            id: photoID,
            userID: photo.userID,
            taskID: taskID,
            objectPath: destination,
            width: photo.width,
            height: photo.height,
            createdAt: .now
        )
        try await insertRow(of: row)
    }
    // --8<-- [end:copy-photo]

    // MARK: - The bucket's own listing

    // --8<-- [start:list-bucket]
    /// The entries of the user's folder `folder` in the bucket (a task's photos, or, for `""`, a
    /// folder per task), a page at a time: Storage filters by name, sorts and pages them.
    func bucketEntries(in folder: String, page: BucketPage) async throws -> [OfflineBucketApi.FileObject] {
        let userID = try signedInUserID()
        let path = folder.isEmpty ? userID : "\(userID)/\(folder)"
        return try await photoBucket.list(
            path: path,
            options: OfflineBucketApi.StorageListFilter(
                limit: page.limit,
                offset: page.offset,
                sortBy: OfflineBucketApi.StorageListFilter.SortBy(column: page.sortColumn, order: page.newestFirst ? .desc : .asc),
                search: page.search.isEmpty ? nil : page.search
            )
        )
    }
    // --8<-- [end:list-bucket]

    // --8<-- [start:signed-upload]
    /// Adds `photo` to task `taskID` through a signed upload URL: the app asks Storage for a
    /// one-time URL and token, then sends the file to it. The way to hand an upload to code that
    /// holds no session, such as a share extension.
    func attachPhotoThroughSignedURL(taskID: String, photo: EncodedPhoto) async throws {
        guard canReachNetwork else { throw SampleFailure.needsNetwork }
        let userID = try signedInUserID()
        let photoID = DeviceID.make()
        let objectPath = PhotoPath.objectPath(userID: userID, taskID: taskID, photoID: photoID)
        let uploaded: OfflineBucketApi.FileUploadResponse
        do {
            let upload = try await photoBucket.createSignedUploadURL(path: objectPath)
            uploaded = try await photoBucket.uploadToSignedURL(
                upload.path,
                token: upload.token,
                data: photo.jpeg,
                options: OfflineBucketApi.UploadOptions(contentType: "image/jpeg")
            )
        } catch {
            throw SampleFailure.storage(error)
        }
        // The row names the file where Storage says it put it.
        try await insertRow(of: TaskPhotoRow(photoID: photoID, userID: userID, taskID: taskID, objectPath: uploaded.path, photo: photo))
    }
    // --8<-- [end:signed-upload]

    // MARK: - Helpers

    /// Writes a photo's row once its file is in the bucket. A row the server refuses at once
    /// (its task deleted on the server meanwhile, say) takes its file with it, as far as Storage
    /// allows; a row that is only queued keeps its file.
    func insertRow(of row: TaskPhotoRow) async throws {
        do {
            try await write(taskTitle: nil) {
                try await client.from(Tables.taskPhotos).insert(row).execute()
            }
        } catch let refusal as SampleFailure {
            if case .writeRefused = refusal { try? await photoBucket.remove(paths: [row.objectPath]) }
            throw refusal
        }
    }

    /// Deletes the files at `paths`, in batches; a missing file is no error.
    func deleteFiles(_ paths: [String]) async throws {
        for start in stride(from: 0, to: paths.count, by: Self.storageDeleteBatch) {
            let batch = Array(paths[start..<min(start + Self.storageDeleteBatch, paths.count)])
            do {
                try await photoBucket.remove(paths: batch)
            } catch {
                throw SampleFailure.storage(error)
            }
        }
    }
}

/// What the Details sheet shows about a photo's file.
nonisolated struct PhotoDetails: Sendable {

    /// Storage's facts about the object; `nil` offline or when it could not be read.
    var inBucket: OfflineBucketApi.FileObjectV2?

    /// Whether the bucket has the object; `nil` when Storage could not be asked.
    var existsInBucket: Bool?

    /// The object's public URL. The bucket is private, so this one answers with an error: the
    /// authenticated URL, or a signed link, is how the file is reached.
    var publicURL: URL?

    /// The object's URL for a request that carries the user's token.
    var authenticatedURL: URL?

    /// The device's copy, when it has one.
    var onDevice: CachedFile?
}

/// One page of the bucket's listing, as the Storage section's browser asks for it.
nonisolated struct BucketPage: Hashable, Sendable {

    /// How many entries a page holds.
    static let size = 20

    /// Text the entries' names must hold; blank for all.
    var search = ""

    /// Whether the newest come first, rather than by name.
    var newestFirst = false

    /// How many entries come before this page.
    var offset = 0

    /// The most entries to ask for.
    var limit: Int { Self.size }

    /// The column Storage sorts by.
    var sortColumn: String { newestFirst ? "created_at" : "name" }

    /// The page after this one.
    var next: BucketPage {
        var page = self
        page.offset += Self.size
        return page
    }
}

extension TaskPhotoRow {

    /// The row of a new photo `photoID` of task `taskID`, at `objectPath`, created now.
    init(photoID: String, userID: String, taskID: String, objectPath: String, photo: EncodedPhoto) {
        self.init(
            id: photoID,
            userID: userID,
            taskID: taskID,
            objectPath: objectPath,
            width: photo.width,
            height: photo.height,
            createdAt: .now
        )
    }
}
