// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import PhotosUI
import SupabaseOffline
import SwiftUI

/// One photo, full screen, with Share, Share link, Details, Replace, Move, Duplicate and Delete;
/// while it waits to upload, with Share, Retry and Remove only.
struct PhotoViewer: View {

    @State private var model: PhotoViewerModel
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var shared: SharedItems?
    @State private var isShowingDetails = false
    @State private var picking: TaskPickerPurpose?
    @State private var isPickingReplacement = false
    @State private var replacement: PhotosPickerItem?
    @State private var isConfirmingDelete = false

    /// The viewer for `model`'s photo.
    init(model: PhotoViewerModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        PhotoPicture(image: model.images.image(of: model.photo), contentMode: .fit)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle(taskTitle)
            .navigationBarTitleDisplayMode(.inline)
            .task(id: app.repository.canReachNetwork) { await model.load() }
            .toolbar { toolbar }
            .safeAreaInset(edge: .bottom) {
                if let pendingUpload = model.pendingUpload { PendingUploadNote(state: pendingUpload) }
            }
            .overlay {
                if model.isWorking { ProgressView().controlSize(.large) }
            }
            .onChange(of: model.isGone) { _, isGone in if isGone { dismiss() } }
            .onChange(of: replacement) { _, item in
                guard let item else { return }
                replacement = nil
                Task { await model.replace(with: item) }
            }
            .photosPicker(isPresented: $isPickingReplacement, selection: $replacement, matching: .images)
            .sheet(item: $shared) { ShareSheet(items: $0.items) }
            .sheet(isPresented: $isShowingDetails) { PhotoDetailsSheet(model: model) }
            .sheet(item: $picking) { purpose in
                TaskPickerSheet(purpose: purpose, tasks: otherTasks) { task in
                    Task {
                        switch purpose {
                        case .move: await model.move(to: task)
                        case .copy: await model.copy(to: task)
                        }
                    }
                }
            }
            .confirmationDialog("Delete this photo?", isPresented: $isConfirmingDelete, titleVisibility: .visible) {
                Button("Delete photo", role: .destructive) { Task { await model.delete() } }
            } message: {
                Text("Its file is deleted from the bucket, then its row.")
            }
    }

    /// Share, and the menu of everything else; while the photo waits to upload, Share, Retry and
    /// Remove.
    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .primaryAction) {
            Button("Share", systemImage: "square.and.arrow.up") {
                Task { shared = await model.shareableFile().map { SharedItems(items: [$0]) } }
            }
        }
        if let pendingUpload = model.pendingUpload {
            pendingToolbar(pendingUpload)
        } else {
            storedToolbar
        }
    }

    /// Retry and Remove, for a photo that waits to upload.
    @ToolbarContentBuilder
    private func pendingToolbar(_ state: PendingPhoto.State) -> some ToolbarContent {
        if case .failed(_, let isRetryable) = state {
            ToolbarItem(placement: .secondaryAction) {
                Button("Retry", systemImage: "arrow.clockwise") { model.retryUpload() }
                    .disabled(!isRetryable)
            }
        }
        ToolbarItem(placement: .secondaryAction) {
            Button("Remove", systemImage: "trash", role: .destructive) { Task { await model.removePendingPhoto() } }
                .disabled(state == .uploading)
        }
    }

    /// Everything but Share, for a photo whose file is in the bucket.
    @ToolbarContentBuilder
    private var storedToolbar: some ToolbarContent {
        ToolbarItem(placement: .secondaryAction) {
            Button("Share link", systemImage: "link") {
                Task { shared = await model.shareLink().map { SharedItems(items: [$0]) } }
            }
            .disabled(!model.canChange)
        }
        ToolbarItem(placement: .secondaryAction) {
            Button("Details", systemImage: "info.circle") { isShowingDetails = true }
        }
        ToolbarItem(placement: .secondaryAction) {
            Button("Replace photo", systemImage: "photo.badge.arrow.down") { isPickingReplacement = true }
                .disabled(!model.canChange)
        }
        ToolbarItem(placement: .secondaryAction) {
            Button("Move to another task", systemImage: "arrow.right.doc.on.clipboard") { picking = .move }
                .disabled(!model.canChange || otherTasks.isEmpty)
        }
        ToolbarItem(placement: .secondaryAction) {
            Button("Duplicate to another task", systemImage: "plus.square.on.square") { picking = .copy }
                .disabled(!model.canChange || otherTasks.isEmpty)
        }
        ToolbarItem(placement: .secondaryAction) {
            Button("Delete photo", systemImage: "trash", role: .destructive) { isConfirmingDelete = true }
                .disabled(!model.canChange)
        }
    }

    /// The task the photo belongs to, by its title.
    private var taskTitle: String {
        app.tasks.rows?.first { $0.id == model.photo.taskID }.map { Wording.title($0.title) } ?? "Photo"
    }

    /// The user's other tasks, which the photo can be moved or copied to.
    private var otherTasks: [TaskItem] {
        (app.tasks.rows ?? []).filter { $0.id != model.photo.taskID }.map { $0.task(pendingIDs: []) }
    }
}

/// Why the task picker is open.
enum TaskPickerPurpose: Identifiable {
    case move
    case copy

    var id: Self { self }
}

/// The user's other tasks, to move or copy a photo to.
struct TaskPickerSheet: View {

    /// Move or copy.
    let purpose: TaskPickerPurpose

    /// The tasks to choose from.
    let tasks: [TaskItem]

    /// Takes the task chosen.
    let choose: (TaskItem) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(tasks) { task in
                Button(Wording.title(task.title)) {
                    choose(task)
                    dismiss()
                }
            }
            .navigationTitle(purpose == .move ? "Move to" : "Duplicate to")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// What Storage and the device know about a photo's file.
struct PhotoDetailsSheet: View {

    /// The viewer, which reads the details.
    let model: PhotoViewerModel

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                bucket
                links
                device
            }
            .navigationTitle("Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .task { await model.loadDetails() }
        }
    }

    /// Storage's facts about the object.
    @ViewBuilder
    private var bucket: some View {
        Section {
            if let object = model.details?.inBucket {
                LabeledContent("Name", value: object.name)
                LabeledContent("Size", value: object.size.formatted(.byteCount(style: .file)))
                LabeledContent("Type", value: object.contentType)
                LabeledContent("Uploaded", value: object.createdAt.formatted(date: .abbreviated, time: .shortened))
                if let updatedAt = object.updatedAt {
                    LabeledContent("Updated", value: updatedAt.formatted(date: .abbreviated, time: .shortened))
                }
                if let lastModified = object.lastModified {
                    LabeledContent("Last modified", value: lastModified.formatted(date: .abbreviated, time: .shortened))
                }
                LabeledContent("Bucket", value: object.bucketId ?? "—")
                LabeledContent("Version", value: object.version)
                LabeledContent("ETag", value: object.etag ?? "—")
                LabeledContent("Cache control", value: object.cacheControl ?? "—")
            }
            if let exists = model.details?.existsInBucket {
                LabeledContent("In the bucket", value: exists ? "Yes" : "No")
            }
        } header: {
            Text("In the bucket")
        } footer: {
            if model.details != nil, model.details?.inBucket == nil {
                Text("Storage’s facts need the network.")
            }
        }
    }

    /// The object's two URLs.
    @ViewBuilder
    private var links: some View {
        if let details = model.details, let authenticatedURL = details.authenticatedURL {
            Section {
                LabeledContent("With your sign-in") { Text(authenticatedURL.absoluteString).textSelection(.enabled) }
                if let publicURL = details.publicURL {
                    LabeledContent("Public") { Text(publicURL.absoluteString).textSelection(.enabled) }
                }
            } header: {
                Text("Addresses")
            } footer: {
                Text("The bucket is private, so the public address answers with an error. Share link makes one that works for an hour.")
            }
        }
    }

    /// The device's copy.
    private var device: some View {
        Section("On this device") {
            if let file = model.details?.onDevice {
                Text(Wording.deviceCopy(file, now: .now))
                LabeledContent("Path") { Text(file.path).textSelection(.enabled) }
            } else if model.details != nil {
                Text("Not on this device yet")
            } else {
                ProgressView()
            }
        }
    }
}

/// What the viewer says under a photo that waits to upload: where it stands, why a failed one
/// failed, and why most actions are missing.
struct PendingUploadNote: View {

    /// Where its upload stands.
    let state: PendingPhoto.State

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            PendingUploadMark(state: state)
            if case .failed(let reason, let isRetryable) = state {
                Text(isRetryable ? reason : "\(reason) Trying again won’t change that: remove it.")
                    .font(.footnote)
            }
            Text("It isn’t on the server yet, so Details, Share link, Replace, Move and Duplicate wait until it is.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.bar)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("pending-upload-note")
    }
}
