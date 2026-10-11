// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation

// --8<-- [start:pending-photo-record]
/// A photo picked on this device whose file is not on the server yet. Its record is kept as JSON
/// beside the photo's JPEG, both named by the photo's id, until its upload lands.
nonisolated struct PendingPhoto: Codable, Hashable, Identifiable, Sendable {

    /// Where its upload stands.
    enum State: Codable, Hashable, Sendable {

        /// Waiting for the network, or for the photos picked before it.
        case waiting

        /// Being uploaded now.
        case uploading

        /// The last attempt failed, for `reason`, in the app's words. When `isRetryable` is false
        /// the server refused it in a way another attempt cannot change.
        case failed(reason: String, isRetryable: Bool)

        /// The failure's keys in the record.
        enum FailedCodingKeys: String, CodingKey {
            case reason
            case isRetryable = "retryable"
        }
    }

    /// The photo's id, made on the device: its row's id, and its files' names.
    let id: String

    /// The task it is attached to.
    let taskID: String

    /// The user who picked it.
    let userID: String

    /// The path its file will have in the bucket.
    let objectPath: String

    /// When it was picked: photos upload in this order.
    let pickedAt: Date

    /// The file's content type; the bucket takes JPEGs only.
    let contentType: String

    /// Its width in pixels, for its row.
    let width: Int

    /// Its height in pixels.
    let height: Int

    /// Where its upload stands.
    var state: State = .waiting

    /// How many attempts to upload it have failed.
    var attempts = 0

    /// The record's keys in its JSON file.
    enum CodingKeys: String, CodingKey {
        case id = "photo_id"
        case taskID = "task_id"
        case userID = "user_id"
        case objectPath = "object_path"
        case pickedAt = "picked_at"
        case contentType = "content_type"
        case width
        case height
        case state
        case attempts
    }
}
// --8<-- [end:pending-photo-record]

/// The photos picked on this device that wait to upload: the app's own queue, since the library
/// never queues a Storage write. Each photo is a JPEG and a JSON record in ``folder``, written
/// before anything else happens to it, and deleted once its upload lands.
///
/// The folder is the app's, outside the library's data directory, so the library's
/// `clearLocalData()` never touches it: the app empties it itself.
@Observable
final class PendingPhotoQueue {

    /// The app's folder for them: `PendingPhotos` in Application Support, beside the library's data
    /// directory, not inside it.
    static let appFolder = URL.applicationSupportDirectory.appending(path: "PendingPhotos", directoryHint: .isDirectory)

    /// The photos waiting, oldest picked first.
    private(set) var photos: [PendingPhoto] = []

    /// Whether new uploads are held, while Start over runs.
    private(set) var isHeld = false

    /// Where the files are.
    @ObservationIgnored let folder: URL

    /// A queue kept in `folder`; ``load()`` reads what an earlier launch left there.
    init(folder: URL = appFolder) {
        self.folder = folder
    }

    /// The photo `id`, while it waits.
    func photo(id: String) -> PendingPhoto? {
        photos.first { $0.id == id }
    }

    /// The photos of task `taskID` that wait, oldest picked first.
    func photos(ofTask taskID: String) -> [PendingPhoto] {
        photos.filter { $0.taskID == taskID }
    }

    /// The ids of the photos waiting, oldest picked first; none while uploads are held.
    var idsReadyToUpload: [String] {
        isHeld ? [] : photos.filter { $0.state == .waiting }.map(\.id)
    }

    /// Where the JPEG of photo `id` is.
    func jpegURL(ofPhoto id: String) -> URL {
        folder.appending(path: "\(id).jpg")
    }

    /// The JPEG of `photo`, read off the main actor.
    ///
    /// - Throws: ``SampleFailure/photoUnreadable`` when its file cannot be read.
    func jpeg(of photo: PendingPhoto) async throws -> Data {
        do {
            return try await Self.contents(of: jpegURL(ofPhoto: photo.id))
        } catch {
            throw SampleFailure.photoUnreadable
        }
    }

    // MARK: - Changing

    /// Reads the records an earlier launch left. A photo that was uploading when the app closed
    /// waits again, and a JPEG whose record was never written is deleted.
    ///
    /// - Throws: ``SampleFailure/pendingPhotoUnreadable(count:)`` when a record cannot be read; the
    ///   ones that can are listed all the same.
    func load() throws {
        try createFolder()
        let names = Set(try FileManager.default.contentsOfDirectory(atPath: folder.path(percentEncoded: false)))
        var loaded: [PendingPhoto] = []
        var unreadable = 0
        for name in names where name.hasSuffix(".json") {
            do {
                let record = try Data(contentsOf: folder.appending(path: name))
                var photo = try PendingPhotoCoding.decoder.decode(PendingPhoto.self, from: record)
                if photo.state == .uploading { photo.state = .waiting }
                loaded.append(photo)
            } catch {
                unreadable += 1
            }
        }
        for name in names where name.hasSuffix(".jpg") && !names.contains(name.replacing(".jpg", with: ".json")) {
            try FileManager.default.removeItem(at: folder.appending(path: name))
        }
        photos = loaded.sorted { $0.pickedAt < $1.pickedAt }
        if unreadable > 0 { throw SampleFailure.pendingPhotoUnreadable(count: unreadable) }
    }

    /// Adds `photo` with its JPEG: the JPEG is written first, then the record, so a record always
    /// has its file.
    func add(_ photo: PendingPhoto, jpeg: Data) async throws {
        try createFolder()
        try await Self.write(jpeg, to: jpegURL(ofPhoto: photo.id))
        try writeRecord(of: photo)
        photos.append(photo)
    }

    /// The next photo of `userID` to upload, marked as uploading; `nil` when none waits, or while
    /// uploads are held.
    func startNextUpload(of userID: String) throws -> PendingPhoto? {
        guard !isHeld, let next = photos.first(where: { $0.userID == userID && $0.state == .waiting }) else { return nil }
        try update(next.id) { $0.state = .uploading }
        return photo(id: next.id)
    }

    /// Records that `photo`'s upload failed for `reason`, and whether another attempt can succeed.
    func markFailed(_ photo: PendingPhoto, reason: String, isRetryable: Bool) throws {
        try update(photo.id) {
            $0.state = .failed(reason: reason, isRetryable: isRetryable)
            $0.attempts += 1
        }
    }

    /// Puts `photo` back to waiting: an upload that stopped short, or Retry on a failed one.
    func markWaiting(_ photo: PendingPhoto) throws {
        try update(photo.id) { $0.state = .waiting }
    }

    /// Puts every failed photo that another attempt can help back to waiting, as the network
    /// comes back.
    func markRetryableFailuresWaiting() throws {
        for photo in photos {
            guard case .failed(_, let isRetryable) = photo.state, isRetryable else { continue }
            try markWaiting(photo)
        }
    }

    /// Removes photo `id`: its JPEG and its record.
    func remove(id: String) throws {
        for url in [recordURL(ofPhoto: id), jpegURL(ofPhoto: id)] where FileManager.default.fileExists(atPath: url.path(percentEncoded: false)) {
            try FileManager.default.removeItem(at: url)
        }
        photos.removeAll { $0.id == id }
    }

    /// Removes every photo of task `taskID`.
    func removePhotos(ofTask taskID: String) throws {
        for photo in photos(ofTask: taskID) { try remove(id: photo.id) }
    }

    /// Removes every photo, and the folder with whatever else it holds.
    func removeAll() throws {
        if FileManager.default.fileExists(atPath: folder.path(percentEncoded: false)) {
            try FileManager.default.removeItem(at: folder)
        }
        photos = []
    }

    // MARK: - Holding uploads

    /// Holds new uploads, and returns once the one in progress, if any, has ended.
    func holdUploads() async {
        isHeld = true
        for await isUploading in Observations({ self.photos.contains { $0.state == .uploading } }) where !isUploading {
            return
        }
    }

    /// Lets uploads start again.
    func releaseUploads() {
        isHeld = false
    }

    // MARK: - Files

    /// Changes photo `id`'s record with `change`, on disk and here; nothing when it is gone.
    private func update(_ id: String, _ change: (inout PendingPhoto) -> Void) throws {
        guard let index = photos.firstIndex(where: { $0.id == id }) else { return }
        var photo = photos[index]
        change(&photo)
        try writeRecord(of: photo)
        photos[index] = photo
    }

    /// Writes `photo`'s record, whole or not at all.
    private func writeRecord(of photo: PendingPhoto) throws {
        try PendingPhotoCoding.encoder.encode(photo).write(to: recordURL(ofPhoto: photo.id), options: .atomic)
    }

    /// Where the record of photo `id` is.
    private func recordURL(ofPhoto id: String) -> URL {
        folder.appending(path: "\(id).json")
    }

    /// Creates the folder, kept out of the device's backups as the library's data is: a record
    /// names this device's user.
    private func createFolder() throws {
        guard !FileManager.default.fileExists(atPath: folder.path(percentEncoded: false)) else { return }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var folder = folder
        try folder.setResourceValues(values)
    }

    /// Writes `data` to `url`, whole or not at all, off the main actor.
    @concurrent
    nonisolated private static func write(_ data: Data, to url: URL) async throws {
        try data.write(to: url, options: .atomic)
    }

    /// The contents of the file at `url`, read off the main actor.
    @concurrent
    nonisolated private static func contents(of url: URL) async throws -> Data {
        try Data(contentsOf: url)
    }
}

/// How a pending photo's record is written: its times as ISO 8601 text to the millisecond, so
/// photos picked together keep their order.
nonisolated enum PendingPhotoCoding {

    /// The format of the records' times.
    static let timeFormat = Date.ISO8601FormatStyle(includingFractionalSeconds: true)

    /// Writes a record.
    static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(date.formatted(timeFormat))
        }
        return encoder
    }

    /// Reads a record.
    static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            try timeFormat.parse(try decoder.singleValueContainer().decode(String.self))
        }
        return decoder
    }
}
