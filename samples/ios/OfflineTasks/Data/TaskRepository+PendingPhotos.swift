// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// Photos picked on this device, offline too: each is filed in ``TaskRepository/pendingPhotos``
/// at once, and shown from there, until ``PendingPhotoUploader`` has uploaded it, written its row
/// and handed it to the library's file cache. The library never queues a Storage write; this queue
/// is the app's.
extension TaskRepository {

    // --8<-- [start:pending-photo-add]
    /// Files `photo` for task `taskID` as waiting: its JPEG and its record are written to the
    /// device before anything else, and the uploader sends it when the network allows. Nothing is
    /// sent here, so it works offline.
    @discardableResult
    func filePhoto(_ photo: EncodedPhoto, forTask taskID: String) async throws -> PendingPhoto {
        let userID = try signedInUserID()
        let photoID = DeviceID.make()
        let pending = PendingPhoto(
            id: photoID,
            taskID: taskID,
            userID: userID,
            objectPath: PhotoPath.objectPath(userID: userID, taskID: taskID, photoID: photoID),
            pickedAt: .now,
            contentType: "image/jpeg",
            width: photo.width,
            height: photo.height
        )
        try await pendingPhotos.add(pending, jpeg: photo.jpeg)
        return pending
    }
    // --8<-- [end:pending-photo-add]

    // --8<-- [start:pending-photo-upload]
    /// Uploads `pending`, then writes its row, then has the library's file cache download it, and
    /// then deletes the device's own copy. The row is written only once the file is stored, so the
    /// server never holds a row whose file is missing; a row the server refuses at once takes the
    /// file with it, and a queued row waits behind its task's queued insert.
    func uploadPendingPhoto(_ pending: PendingPhoto) async throws {
        if await !isStoredByAnEarlierTry(pending) {
            try await uploadFile(of: pending, jpeg: try await pendingPhotos.jpeg(of: pending))
        }
        guard pendingPhotos.photo(id: pending.id) != nil else {
            // Removed while its file was on the way (Remove, Delete task, Clear): no row, no file.
            try? await photoBucket.remove(paths: [pending.objectPath])
            return
        }
        try await insertRow(of: TaskPhotoRow(pending))
        // The upload did not put its bytes in the cache; this downloads them, so the photo shows
        // offline from the library's copy. If it fails, the photo loads like any other when online.
        _ = try? await photoBucket.ensureCachedFile(path: pending.objectPath)
        try pendingPhotos.remove(id: pending.id)
    }

    /// Whether an earlier try at `pending` stored its file, its answer lost on the way: asked of
    /// Storage on a retry only. Storage's "already exists" to the upload counts as stored too.
    private func isStoredByAnEarlierTry(_ pending: PendingPhoto) async -> Bool {
        guard pending.attempts > 0 else { return false }
        return (try? await photoBucket.exists(path: pending.objectPath)) == true
    }
    // --8<-- [end:pending-photo-upload]

    /// Retry on a failed photo: it waits again, and uploads when the network allows.
    func retryUpload(of photo: TaskPhoto) throws {
        guard let pending = pendingPhotos.photo(id: photo.id) else { return }
        try pendingPhotos.markWaiting(pending)
    }

    /// Remove on a photo waiting to upload: its file and record are deleted from the device, and
    /// nothing is sent.
    func removePendingPhoto(_ photo: TaskPhoto) throws {
        try pendingPhotos.remove(id: photo.id)
    }

    /// The device's own copy of `photo`'s JPEG while it waits to upload; `nil` once it has gone.
    func pendingPhotoData(of photo: TaskPhoto) async -> Data? {
        guard let pending = pendingPhotos.photo(id: photo.id) else { return nil }
        return try? await pendingPhotos.jpeg(of: pending)
    }

    // --8<-- [start:pending-photo-merge]
    /// Task `taskID`'s photos as the screens show them, oldest first: the rows the library holds,
    /// with a mark on each whose row waits in the mutation queue (`queuedRowIDs`), and the photos
    /// picked on this device that wait to upload (`pending`). A photo in both is shown from its
    /// record until its local copy is deleted, so it never shows twice.
    static func photos(
        ofTask taskID: String,
        rows: [TaskPhotoRow],
        queuedRowIDs: Set<String>,
        pending: [PendingPhoto]
    ) -> [TaskPhoto] {
        let waiting = pending.filter { $0.taskID == taskID }
        let waitingIDs = Set(waiting.map(\.id))
        let stored = rows
            .filter { $0.taskID == taskID && !waitingIDs.contains($0.id) }
            .map { $0.photo(pendingIDs: queuedRowIDs) }
        return (stored + waiting.map(\.photo)).sorted { $0.createdAt < $1.createdAt }
    }

    /// Every photo the Photos screen shows, newest first: the rows read so far, and every photo
    /// that waits to upload, merged as ``photos(ofTask:rows:queuedRowIDs:pending:)`` merges one
    /// task's.
    static func allPhotos(rows: [TaskPhotoRow], queuedRowIDs: Set<String>, pending: [PendingPhoto]) -> [TaskPhoto] {
        let waitingIDs = Set(pending.map(\.id))
        let stored = rows.filter { !waitingIDs.contains($0.id) }.map { $0.photo(pendingIDs: queuedRowIDs) }
        return (stored + pending.map(\.photo)).sorted { $0.createdAt > $1.createdAt }
    }
    // --8<-- [end:pending-photo-merge]
}

nonisolated extension PendingPhoto {

    /// The photo as the screens show it, marked with where its upload stands.
    var photo: TaskPhoto {
        TaskPhoto(
            id: id,
            userID: userID,
            taskID: taskID,
            objectPath: objectPath,
            width: width,
            height: height,
            createdAt: pickedAt,
            hasPendingMutations: false,
            pendingUpload: state
        )
    }
}

extension TaskPhotoRow {

    /// The row of `pending`, once its file is stored: created when it was picked, so it keeps its
    /// place among the task's photos.
    nonisolated init(_ pending: PendingPhoto) {
        self.init(
            id: pending.id,
            userID: pending.userID,
            taskID: pending.taskID,
            objectPath: pending.objectPath,
            width: pending.width,
            height: pending.height,
            createdAt: pending.pickedAt
        )
    }
}
