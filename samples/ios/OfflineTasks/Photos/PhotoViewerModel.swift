// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import PhotosUI
import SupabaseOffline
import SwiftUI

/// One photo, full screen: its image, what Storage and the device know about it, and what can be
/// done with it. Every change to the file needs the network.
@Observable
final class PhotoViewerModel {

    /// The photo shown; a move changes its task and path.
    private(set) var photo: TaskPhoto

    /// Its image.
    let images: PhotoImages

    /// What Storage and the device know about its file, once asked.
    private(set) var details: PhotoDetails?

    /// Whether a change to the file is on its way.
    private(set) var isWorking = false

    /// Whether the photo was deleted or moved away from here, so the viewer closes.
    private(set) var isGone = false

    /// Reads and writes.
    @ObservationIgnored private let repository: TaskRepository

    /// Where a failure, or the result of a change, goes to be shown.
    @ObservationIgnored private let failures: FailureSink

    /// The viewer of `photo`.
    init(photo: TaskPhoto, repository: TaskRepository, failures: FailureSink) {
        self.photo = photo
        self.repository = repository
        self.failures = failures
        images = PhotoImages(repository: repository, maxPixelSize: 2_048)
    }

    /// Whether the file can be changed now: Storage writes need the network.
    var canChange: Bool {
        repository.canReachNetwork && repository.session.userID != nil
    }

    /// Loads the image.
    func load() async {
        await images.load(photo)
    }

    /// Reads what Storage and the device know about the file.
    func loadDetails() async {
        details = await repository.photoDetails(for: photo)
    }

    /// The file, for the share sheet; `nil` when it could not be had.
    func shareableFile() async -> URL? {
        var file: URL?
        await failures.perform { file = try await repository.shareableFile(for: photo) }
        return file
    }

    /// A link to a 512-pixel-wide copy that works for an hour; `nil` when it could not be made.
    func shareLink() async -> URL? {
        var link: URL?
        await failures.perform { link = try await repository.shareLink(for: photo) }
        return link
    }

    /// Writes the picked image over the photo, then shows the new one.
    func replace(with item: PhotosPickerItem) async {
        await change("Photo replaced") {
            guard let data = try await item.loadTransferable(type: Data.self) else { throw SampleFailure.photoUnreadable }
            try await repository.replacePhoto(photo, with: try await PhotoEncoder.encode(data))
            await images.load(photo, forgetting: true)
        }
    }

    /// Moves the photo to `task`; the viewer closes, as the photo has left the task it was opened from.
    func move(to task: TaskItem) async {
        await change("Moved to “\(Wording.title(task.title))”") {
            try await repository.movePhoto(photo, toTask: task.id)
            isGone = true
        }
    }

    /// Copies the photo to `task`, as a new photo of that task.
    func copy(to task: TaskItem) async {
        await change("Copied to “\(Wording.title(task.title))”") {
            try await repository.copyPhoto(photo, toTask: task.id)
        }
    }

    /// Deletes the photo's file, then its row; the viewer closes.
    func delete() async {
        await change("Photo deleted") {
            try await repository.deletePhoto(photo)
            isGone = true
        }
    }

    /// Runs `work` as one change to the file, and says `done` once it is through.
    private func change(_ done: String, _ work: () async throws -> Void) async {
        isWorking = true
        defer { isWorking = false }
        await failures.perform {
            try await work()
            failures.announce(done)
        }
    }
}
