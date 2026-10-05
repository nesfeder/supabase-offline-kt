// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import PhotosUI
import SupabaseOffline
import SwiftUI

/// One task: title and notes saved as you type, the done switch, its photos, and delete.
struct TaskDetailView: View {

    @State private var model: TaskDetailModel
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var pickedPhoto: PhotosPickerItem?
    @State private var isConfirmingDelete = false

    /// The screen for `model`'s task.
    init(model: TaskDetailModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        content
            .navigationTitle(model.lookup.task.map { Wording.title($0.title) } ?? "")
            .navigationBarTitleDisplayMode(.inline)
            .task { await model.observeTask() }
            .task { await model.observePendingTask() }
            .task { await model.observePhotos() }
            .onDisappear { model.saveNow() }
    }

    /// The form, or the state that stands in for it.
    @ViewBuilder
    private var content: some View {
        switch model.lookup {
        case .unknown:
            ProgressView()
        case .missing:
            ContentUnavailableView {
                Label("This task is gone", systemImage: "trash")
            } description: {
                Text("It was deleted, here or on the server, or the server refused it.")
            }
        case .found(let task):
            form(task)
        }
    }

    /// Whether photos can be added or deleted now: Storage writes need the network.
    private var canChangePhotos: Bool {
        app.repository.canReachNetwork && app.session.userID != nil
    }

    /// The task's fields, done switch and photos, with Add photo and Delete below.
    private func form(_ task: TaskItem) -> some View {
        Form {
            Section {
                TextField("Title", text: Binding(get: { model.draftTitle }, set: { model.editTitle($0) }))
                    .accessibilityIdentifier("detail-title")
                TextField("Notes", text: Binding(get: { model.draftNotes }, set: { model.editNotes($0) }), axis: .vertical)
                    .lineLimit(2...10)
            } footer: {
                if model.isTitleBlank {
                    Text("A task needs a title")
                } else {
                    Label(TaskSyncState.of(task, status: app.syncStatus).text, systemImage: syncSymbol(for: task))
                        .accessibilityIdentifier("detail-sync-state")
                }
            }
            Section {
                Toggle("Done", isOn: Binding(get: { task.isDone }, set: { model.setDone($0) }))
            } footer: {
                Text("Done tasks move to the end of the list")
            }
            Section {
                photoGrid
            } header: {
                Text("Photos")
            } footer: {
                Text(
                    canChangePhotos
                        ? "Photos are uploaded as you add them."
                        : "Photos upload when you’re online. The ones already here still show."
                )
            }
        }
        .toolbar {
            ToolbarItem(placement: .bottomBar) {
                PhotosPicker(selection: $pickedPhoto, matching: .images) {
                    Label("Add photo", systemImage: "photo.badge.plus")
                }
                .disabled(!canChangePhotos)
            }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                Button("Delete task", systemImage: "trash", role: .destructive) { isConfirmingDelete = true }
            }
        }
        .onChange(of: pickedPhoto) { _, item in
            guard let item else { return }
            model.attach(item)
            pickedPhoto = nil
        }
        .confirmationDialog("Delete this task?", isPresented: $isConfirmingDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                model.delete()
                dismiss()
            }
        } message: {
            Text(deleteMessage)
        }
    }

    /// The photos, oldest first, then a placeholder for each upload on its way.
    @ViewBuilder
    private var photoGrid: some View {
        if model.photos.isEmpty, model.uploadCount == 0 {
            Text("No photos yet")
                .foregroundStyle(.secondary)
        } else {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 88), spacing: 8)], spacing: 8) {
                ForEach(model.photos) { photo in
                    PhotoThumbnail(
                        image: model.images[photo.id] ?? .loading,
                        canDelete: canChangePhotos
                    ) {
                        model.deletePhoto(photo)
                    }
                    .task(id: PhotoLoadKey(photoID: photo.id, canReachNetwork: canChangePhotos)) {
                        await model.loadImage(for: photo)
                    }
                }
                ForEach(0..<model.uploadCount, id: \.self) { _ in
                    UploadPlaceholder()
                }
            }
            .padding(.vertical, 4)
        }
    }

    /// What deleting does to the task's photos, as the delete confirmation says it.
    private var deleteMessage: String {
        let base = "It’s removed from this device now and from the server when it syncs."
        guard !model.photos.isEmpty else { return base }
        guard app.repository.canReachNetwork else {
            return base + " You’re offline, so its photo files stay on the server; delete it while online to remove them too."
        }
        return base + " Its photos are deleted too."
    }

    /// The symbol beside the sync state.
    private func syncSymbol(for task: TaskItem) -> String {
        task.hasPendingMutations ? "icloud.and.arrow.up" : "checkmark.icloud"
    }
}

/// What a thumbnail's loading depends on: the photo, and whether the server can be asked.
private struct PhotoLoadKey: Hashable {
    let photoID: String
    let canReachNetwork: Bool
}

/// One photo, or what stands in for it.
struct PhotoThumbnail: View {

    /// The photo's image as it stands.
    let image: TaskDetailModel.PhotoImage

    /// Whether Delete photo is on: Storage writes need the network.
    let canDelete: Bool

    /// Deletes the photo.
    let delete: () -> Void

    var body: some View {
        Color(.secondarySystemFill)
            .aspectRatio(1, contentMode: .fit)
            .overlay { picture }
            .clipShape(.rect(cornerRadius: 12))
            .contextMenu {
                Button("Delete photo", systemImage: "trash", role: .destructive, action: delete)
                    .disabled(!canDelete)
            }
    }

    /// The image, or a symbol and its words.
    @ViewBuilder
    private var picture: some View {
        switch image {
        case .loading:
            ProgressView()
        case .loaded(let uiImage):
            Image(uiImage: uiImage)
                .resizable()
                .scaledToFill()
                .accessibilityLabel("Task photo, available offline")
        case .notOnDevice:
            placeholder("icloud.and.arrow.down", "Not on this device yet; it loads when you’re online")
        case .missing:
            placeholder("questionmark.square.dashed", "This photo’s file is missing")
        case .unavailable:
            placeholder("exclamationmark.triangle", "This photo couldn’t be loaded")
        }
    }

    /// A symbol standing in for the photo, with its words for VoiceOver.
    private func placeholder(_ systemImage: String, _ description: String) -> some View {
        Image(systemName: systemImage)
            .font(.title2)
            .foregroundStyle(.secondary)
            .accessibilityLabel(description)
    }
}

/// A photo on its way up.
struct UploadPlaceholder: View {

    var body: some View {
        Color(.secondarySystemFill)
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                VStack(spacing: 6) {
                    ProgressView()
                    Text("Uploading").font(.caption).foregroundStyle(.secondary)
                }
            }
            .clipShape(.rect(cornerRadius: 12))
    }
}
