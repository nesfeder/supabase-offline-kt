// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import PhotosUI
import SupabaseOffline
import SwiftUI

/// One task: its live row and photos, the title and notes saved as you type, the done switch,
/// photo uploads and deletion.
@Observable
final class TaskDetailModel {

    /// What the device knows about the task.
    enum Lookup: Equatable {

        /// Nothing read yet.
        case unknown

        /// It does not exist: deleted here or on the server, or refused when it was sent.
        case missing

        /// The task as the device holds it.
        case found(TaskItem)
    }

    /// Where one photo's image stands.
    enum PhotoImage {

        /// Being read or fetched.
        case loading

        /// Read from the device's copy of the file.
        case loaded(UIImage)

        /// Not on the device, and the server cannot be asked now.
        case notOnDevice

        /// Neither the device nor the bucket has it.
        case missing

        /// It could not be loaded.
        case unavailable
    }

    /// How long typing must pause before an edit is saved.
    static let autosaveDelay = Duration.milliseconds(600)

    /// The task shown.
    let taskID: String

    /// The task as the device holds it.
    var lookup: Lookup {
        guard let taskRows else { return .unknown }
        guard let row = taskRows.first(where: { $0.id == taskID }) else { return .missing }
        return .found(row.task(pendingIDs: isTaskPending ? [taskID] : []))
    }

    /// The rows of the list query; `nil` while nothing is known.
    private var taskRows: [TaskRow]?

    /// Whether a write to the task waits in the queue.
    private var isTaskPending = false

    /// The title as typed; saved after a pause unless blank.
    private(set) var draftTitle = ""

    /// The notes as typed; saved after a pause, and clearing them clears them.
    private(set) var draftNotes = ""

    /// The task's photos, oldest first.
    private(set) var photos: [TaskPhoto] = []

    /// How many photos are on their way up.
    private(set) var uploadCount = 0

    /// Each photo's image, by photo id.
    private(set) var images: [String: PhotoImage] = [:]

    /// Every photo row the device holds, and which of them wait in the queue.
    private var photoRows: [TaskPhotoRow] = []
    private var pendingPhotoIDs: Set<String> = []

    /// The title and notes the device holds, which the draft is compared with.
    @ObservationIgnored private var stored: (title: String, notes: String)?

    /// The save waiting for typing to pause.
    @ObservationIgnored private var pendingSave: Task<Void, Never>?

    /// Reads and writes.
    @ObservationIgnored private let repository: TaskRepository

    /// Where a failure goes to be shown.
    @ObservationIgnored private let failures: FailureSink

    /// The model of task `taskID`.
    init(taskID: String, repository: TaskRepository, failures: FailureSink) {
        self.taskID = taskID
        self.repository = repository
        self.failures = failures
    }

    /// Whether the title is blank, so it will not be saved until it has some text.
    var isTitleBlank: Bool {
        draftTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    // MARK: - Reading

    /// Follows the task, from the list query the Tasks screen reads: a query of its own would
    /// have nothing to show offline until it had been fetched once.
    func observeTask() async {
        do {
            for try await snapshot in repository.taskSnapshots() {
                taskRows = snapshot.result
                if let task = lookup.task { adoptStoredText(of: task) }
            }
        } catch is CancellationError {
            return
        } catch {
            failures.report(error)
        }
    }

    /// Follows whether a write to the task waits in the queue.
    func observePendingTask() async {
        do {
            for try await mutations in repository.pendingMutations(table: Tables.tasks) {
                isTaskPending = mutations.contains { $0.rowIDs.contains(taskID) }
            }
        } catch is CancellationError {
            return
        } catch {
            failures.report(error)
        }
    }

    /// Follows the task's photos.
    func observePhotos() async {
        await withDiscardingTaskGroup { group in
            group.addTask { await self.observePhotoRows() }
            group.addTask { await self.observePendingPhotos() }
        }
    }

    /// Loads `photo`'s image: from the device's copy, fetched first when there is none and the
    /// server can be asked.
    func loadImage(for photo: TaskPhoto) async {
        if images[photo.id] == nil { images[photo.id] = .loading }
        switch await repository.photoFile(for: photo) {
        case .onDevice(let file):
            do {
                let data = try await repository.photoData(of: file, for: photo)
                images[photo.id] = await Self.thumbnail(of: data).map(PhotoImage.loaded) ?? .unavailable
            } catch {
                images[photo.id] = .unavailable
            }
        case .notOnDevice:
            images[photo.id] = .notOnDevice
        case .missing:
            images[photo.id] = .missing
        case .unavailable:
            images[photo.id] = .unavailable
        }
    }

    // MARK: - Editing

    /// Takes the title as typed, cut at the schema's limit; it is saved after a pause.
    func editTitle(_ title: String) {
        draftTitle = TaskRules.truncated(title, to: TaskRules.titleMaxLength)
        scheduleSave()
    }

    /// Takes the notes as typed, cut at the schema's limit; they are saved after a pause.
    func editNotes(_ notes: String) {
        draftNotes = TaskRules.truncated(notes, to: TaskRules.notesMaxLength)
        scheduleSave()
    }

    /// Saves an edit typed less than the autosave delay ago, as the screen closes.
    func saveNow() {
        pendingSave?.cancel()
        save()
    }

    /// Marks the task done or open again.
    func setDone(_ isDone: Bool) {
        Task { await failures.perform { try await repository.setTaskDone(id: taskID, isDone: isDone) } }
    }

    /// Deletes the task, its photos' files first when online. The screen closes at once; a
    /// refusal is shown, and the task is back in the list.
    func delete() {
        pendingSave?.cancel()
        Task { await failures.perform { try await repository.deleteTask(id: taskID) } }
    }

    /// Re-encodes the picked image as a JPEG and uploads it.
    func attach(_ item: PhotosPickerItem) {
        uploadCount += 1
        Task {
            defer { uploadCount -= 1 }
            await failures.perform {
                guard let data = try await item.loadTransferable(type: Data.self) else {
                    throw SampleFailure.photoUnreadable
                }
                let photo = try await PhotoEncoder.encode(data)
                try await repository.attachPhoto(taskID: taskID, photo: photo)
            }
        }
    }

    /// Deletes `photo`'s file and then its row; online only.
    func deletePhoto(_ photo: TaskPhoto) {
        Task { await failures.perform { try await repository.deletePhoto(photo) } }
    }

    // MARK: - Helpers

    /// Takes the device's text into the draft, where the user has not typed over it.
    private func adoptStoredText(of task: TaskItem) {
        let incoming = (title: task.title, notes: task.notes ?? "")
        if stored == nil || (draftTitle == stored?.title && draftNotes == stored?.notes) {
            draftTitle = incoming.title
            draftNotes = incoming.notes
        }
        stored = incoming
    }

    /// Saves the draft once typing pauses.
    private func scheduleSave() {
        pendingSave?.cancel()
        pendingSave = Task {
            try? await Task.sleep(for: Self.autosaveDelay)
            guard !Task.isCancelled else { return }
            save()
        }
    }

    /// Saves the draft, unless it is what the device holds or its title is blank.
    private func save() {
        guard let stored, draftTitle != stored.title || draftNotes != stored.notes, !isTitleBlank else { return }
        let title = draftTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        let notes = draftNotes.trimmingCharacters(in: .whitespacesAndNewlines)
        self.stored = (draftTitle, draftNotes)
        // A task of its own: an edit made just before leaving is still saved.
        Task {
            await failures.perform {
                try await repository.updateTask(id: taskID, title: title, notes: notes.isEmpty ? nil : notes)
            }
        }
    }

    /// Follows every photo row the device holds; this task's are picked out.
    private func observePhotoRows() async {
        do {
            for try await snapshot in repository.photoSnapshots() {
                photoRows = snapshot.result ?? []
                updatePhotos()
            }
        } catch is CancellationError {
            return
        } catch {
            failures.report(error)
        }
    }

    /// Follows which photo rows wait in the queue.
    private func observePendingPhotos() async {
        do {
            for try await mutations in repository.pendingMutations(table: Tables.taskPhotos) {
                pendingPhotoIDs = Set(mutations.flatMap(\.rowIDs))
                updatePhotos()
            }
        } catch is CancellationError {
            return
        } catch {
            failures.report(error)
        }
    }

    /// This task's photos, oldest first, from the rows and the queue.
    private func updatePhotos() {
        photos = photoRows
            .filter { $0.taskID == taskID }
            .map { $0.photo(pendingIDs: pendingPhotoIDs) }
            .sorted { $0.createdAt < $1.createdAt }
    }

    /// A thumbnail of the image in `data`, decoded off the main actor.
    @concurrent
    nonisolated private static func thumbnail(of data: Data) async -> UIImage? {
        PhotoEncoder.downsampled(data, maxPixelSize: 480).map(UIImage.init(cgImage:))
    }
}

extension TaskDetailModel.Lookup {

    /// The task, when the device has it.
    var task: TaskItem? {
        guard case .found(let task) = self else { return nil }
        return task
    }
}

/// Where a task's latest change stands, for the line under its title.
enum TaskSyncState: Equatable {

    /// On the server.
    case synced

    /// Changed here while offline: kept on the device until the network is back.
    case savedOnDevice

    /// Changed here; it is sent in a moment.
    case waitingToSync

    /// Being sent now.
    case syncing

    /// The state of `task` under `status`.
    static func of(_ task: TaskItem, status: SyncStatus) -> TaskSyncState {
        guard task.hasPendingMutations else { return .synced }
        if status.network == .offline || status.network == .disabled { return .savedOnDevice }
        return status.isSyncing ? .syncing : .waitingToSync
    }

    /// The line the detail screen shows.
    var text: String {
        switch self {
        case .synced: return "Synced"
        case .savedOnDevice: return "Saved on this device — will sync when online"
        case .waitingToSync: return "Waiting to sync"
        case .syncing: return "Syncing…"
        }
    }
}
